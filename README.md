# VoxCrew

Application Android privée de communication vocale de groupe pour activités extérieures (ski, gyroroue, randonnée, vélo). Expérience type intercom : session vocale persistante, micro transmis selon une politique locale (PTT, VAD future, ou micro ouvert).

## Architecture (MVP)

- **Mode local (priorité)** : intercom LAN direct entre participants — découverte par broadcast UDP, audio sur socket TCP. Aucun signaling, aucun transit par le backend tant que le LAN fonctionne. Voir [Mode local (LAN intercom)](#mode-local-lan-intercom) ci-dessous.
- **Repli cloud (fallback)** : quand le LAN est indisponible ou se dégrade, le même protocole (`PeerLink`) bascule sur un chemin assisté par le backend — hole punching UDP (STUN + rendez-vous via WebSocket) puis, en dernier recours, relais WebSocket binaire. Le backend ne relaie jamais l'audio en clair sauf sur ce dernier chemin, et uniquement des trames opaques (non stockées, non parsées). Voir [Repli cloud](#repli-cloud-fallback) ci-dessous.
- **Mode WebRTC (parqué)** : ancien chemin peer-to-peer WebRTC avec signaling Cloud Run — code conservé dans le dépôt et accessible depuis les outils développeur, mais plus sur le chemin par défaut de l'écran principal (remplacé par le repli cloud ci-dessus).
- **Backend** : Node.js / TypeScript / Fastify sur Google Cloud Run — signaling WebSocket, auth Firebase, rendez-vous P2P (`p2p_connect_request`/`p2p_endpoints`) et relais binaire pour le repli cloud, sessions WebRTC parquées.
- **Android** : Kotlin, Jetpack Compose, Firebase Authentication, sockets TCP/UDP natifs + codec Opus pour le mode local et le repli cloud, WebRTC natif pour le mode parqué.

```
LAN (priorité) :
Galaxy A <== UDP beacon (découverte) + TCP audio Opus (LAN/hotspot) ==> Galaxy B

Repli cloud (si LAN indisponible ou dégradé) :
Galaxy A <== UDP hole-punch (STUN) ==> Galaxy B         [1er repli]
Galaxy A <====== relais WebSocket (Cloud Run) ======> Galaxy B   [dernier recours]
     \                                              /
      \---- WSS signaling + rendez-vous (Cloud Run) ----/

Mode WebRTC parqué :
Galaxy A <====== WebRTC audio ======> Galaxy B
     \                              /
      \---- WSS signaling (Cloud Run) ----/
```

## Mode local (LAN intercom)

Le mode local a été reconstruit autour d'un principe simple : **préserver la qualité et la complétude de l'audio avant la latence**, façon « téléchargement progressif ». Le détail est dans [android/app/src/main/java/com/nblaisot/voxcrew/lanlink/](android/app/src/main/java/com/nblaisot/voxcrew/lanlink/) :

- **Découverte** : `LanBeacon` diffuse un identifiant (`uid`, nom, port TCP) en broadcast UDP (port `47100`) toutes les 2 s, et écoute les mêmes annonces. Fonctionne aussi bien entre deux appareils sur le même Wi-Fi qu'en hotspot (l'appareil hotspot et l'appareil connecté partagent le même domaine de broadcast).
- **Protocole transport-agnostique** : `PeerLink` porte la logique de protocole (numéros de séquence, `SendBuffer`, RTT, backlog) indépendamment du transport ; `FrameTransport` est l'interface implémentée par `LanTcpTransport` (LAN), `UdpP2pTransport` (repli cloud, hole punching) et `RelayTransport` (repli cloud, relais WebSocket) — un seul espace de séquence survit à chaque changement de chemin.
- **Reprise après coupure** : chaque trame audio porte un `seq` croissant ; l'émetteur garde les trames non confirmées dans un `SendBuffer` (~2 min, ring buffer, avec horodatage pour le backlog) ; à la reconnexion (même sur un autre transport), chaque côté annonce le dernier `seq` reçu et l'autre rejoue exactement le manque — aucun audio perdu, seulement retardé.
- **Audio** : codec Opus (`OpusCodec`, implémentation pure Java via Concentus pour compatibilité `minSdk`) par trames de 20 ms (`AudioCapture`/`AudioPlayback`), routé sur le flux **multimédia** (haut-parleur par défaut, bascule automatique vers écouteurs/Bluetooth s'ils sont branchés) plutôt que le flux d'appel.
- **Arrière-plan** : `LanIntercomEngine` vit dans `AppContainer` (portée application), piloté par un `SessionForegroundService` permanent dès la connexion — la réception audio et l'émission VOX continuent écran éteint ou app en arrière-plan ; la notification affiche l'état VOX (actif/désactivé) et le statut de la liaison.
- **Roster** : les équipiers détectés en LAN apparaissent directement (icône Wifi) dès réception de leur beacon, indépendamment du cloud. Le dernier équipier sélectionné est persisté (`SharedPreferences`) et redevient la cible dès le lancement suivant.

## Repli cloud (fallback)

Quand `LanIntercomEngine` détecte que le LAN est indisponible (aucune connexion locale après 5 s) ou perdu (déconnecté depuis plus de 3 s), ou que la liaison locale s'est dégradée (RTT > 2 s ou trame la plus ancienne non confirmée depuis > 1 s), il déclenche un repli cloud sans jamais faire transiter l'audio par le backend en clair :

1. **Rendez-vous** : ouverture (à la demande) de la connexion WebSocket signaling vers Cloud Run, envoi de `p2p_connect_request` puis de `p2p_endpoints` (découverte STUN de l'adresse publique) — le backend relaie ces messages uid-à-uid sans les interpréter côté audio.
2. **Hole punching UDP** (`UdpP2pTransport`) : les deux appareils s'envoient des paquets sur les adresses échangées pour ouvrir les routes NAT ; premier chemin essayé, le plus direct.
3. **Relais WebSocket** (`RelayTransport`) : si le hole punching n'a pas abouti après quelques secondes, l'audio (déjà encodé Opus) est envoyé en trames binaires opaques sur le WebSocket signaling existant — dernier recours, le backend ne fait que forwarder (limite de débit, jamais stocké ni parsé).
4. **Retour au local** : le beacon LAN continue d'émettre/écouter en permanence sur tous les chemins ; dès qu'il retrouve l'équipier, `LanTcpTransport` redial et reprend la main automatiquement (« make-before-break »), sans perte ni doublon grâce au `seq` partagé.
5. **Changement de réseau** : un `NetworkMonitor` redéclenche l'annonce d'endpoints dès que l'interface réseau change (ex. retour au Wi-Fi après un hotspot), pour retrouver rapidement le meilleur chemin.

L'UI affiche, à côté de l'e-mail de l'équipier sélectionné, le RTT courant (ms) et le chemin actif (Local / P2P cloud / Relais cloud), ainsi qu'une jauge horizontale représentant le backlog audio non encore accusé réception (plafonnée à 10 s), visible quel que soit le chemin utilisé.

## État actuel

| Composant | Statut |
|-----------|--------|
| Documentation | En place |
| Backend signaling | Auth + presence + rendez-vous P2P + relais binaire, tests, déployé sur Cloud Run |
| Terraform / GCP | Configuration prête, déploiement manuel |
| Android — mode local | Intercom LAN (UDP beacon + TCP audio Opus), reprise sur coupure, service permanent, PTT/Vox |
| Android — repli cloud | Hole punching UDP (STUN) + relais WebSocket, bascule auto vers/depuis le local, RTT + backlog affichés |
| Android — mode WebRTC | Compose, auth, signaling, WebRTC (parqué, accessible via outils développeur) |
| CI GitHub | Workflows backend, Android, Terraform |

## Prérequis

- **Java 17+**
- **Android SDK** (`ANDROID_HOME` → `~/Library/Android/sdk`)
- **Node.js 22 LTS** (recommandé ; `engines` dans `backend/package.json`)
- **Docker** (build image backend)
- **Terraform** (infrastructure)
- **gcloud CLI** + **gh** (déploiement)
- **google-services.json** (hors Git — voir [docs/gcp-setup.md](docs/gcp-setup.md))

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

## Commandes principales

### Backend

```bash
cd backend
cp .env.example .env   # éditer localement, jamais committer
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm run dev            # développement local
docker build -t voxcrew-signaling .
```

### Android

```bash
cd android
cp local.properties.example local.properties   # ajuster sdk.dir si besoin
./gradlew lintDebug testDebugUnitTest assembleDebug
```

### Infrastructure

```bash
cd infrastructure/terraform
cp terraform.tfvars.example terraform.tfvars   # hors Git
terraform fmt
terraform init
terraform plan
# terraform apply  — uniquement après validation explicite
```

## Secrets — ne jamais committer

- `backend/.env`, `terraform.tfvars`
- `android/app/google-services.json`, `local.properties`
- Clés de compte de service, tokens Firebase, mots de passe

Voir [docs/security.md](docs/security.md) et [AGENTS.md](AGENTS.md).

## Documentation

- [architecture.md](docs/architecture.md)
- [signaling-protocol.md](docs/signaling-protocol.md)
- [android-audio.md](docs/android-audio.md)
- [gcp-setup.md](docs/gcp-setup.md)
- [security.md](docs/security.md)
- [testing.md](docs/testing.md)
- [roadmap.md](docs/roadmap.md)
- [cost-control.md](docs/cost-control.md)

## Configuration par défaut

| Paramètre | Valeur |
|-----------|--------|
| Application | VoxCrew |
| Android package | `com.nblaisot.voxcrew` |
| Région GCP | `europe-west1` |
| Service Cloud Run | `voxcrew-signaling` |
| Firestore | Native, `eur3` |

## Licence

Dépôt privé — pas de licence open source par défaut.
