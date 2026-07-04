# VoxCrew

Application Android privée de communication vocale de groupe pour activités extérieures (ski, gyroroue, randonnée, vélo). Expérience type intercom : session vocale persistante, micro transmis selon une politique locale (PTT, VAD future, ou micro ouvert).

## Architecture (MVP)

- **Mode local (priorité)** : intercom LAN direct entre participants — découverte par broadcast UDP, audio brut (PCM) sur socket TCP. Aucun WebRTC, aucun signaling, aucun transit par le backend en local. Voir [Mode local (LAN intercom)](#mode-local-lan-intercom) ci-dessous.
- **Mode cloud (parqué)** : WebRTC peer-to-peer avec signaling Cloud Run — code conservé dans le dépôt et accessible depuis les outils développeur, mais plus sur le chemin par défaut de l'écran principal.
- **Backend** : Node.js / TypeScript / Fastify sur Google Cloud Run — signaling WebSocket, auth Firebase, sessions (utilisé par le mode cloud parqué).
- **Android** : Kotlin, Jetpack Compose, Firebase Authentication, sockets TCP/UDP natifs pour le mode local, WebRTC natif pour le mode cloud.

```
Galaxy A <== UDP beacon (découverte) + TCP audio PCM (LAN/hotspot) ==> Galaxy B

Mode cloud parqué :
Galaxy A <====== WebRTC audio ======> Galaxy B
     \                              /
      \---- WSS signaling (Cloud Run) ----/
```

## Mode local (LAN intercom)

Le mode local a été reconstruit autour d'un principe simple : **préserver la qualité et la complétude de l'audio avant la latence**, façon « téléchargement progressif ». Le détail est dans [android/app/src/main/java/com/nblaisot/voxcrew/lanlink/](android/app/src/main/java/com/nblaisot/voxcrew/lanlink/) :

- **Découverte** : `LanBeacon` diffuse un identifiant (`uid`, nom, port TCP) en broadcast UDP (port `47100`) toutes les 2 s, et écoute les mêmes annonces. Fonctionne aussi bien entre deux appareils sur le même Wi-Fi qu'en hotspot (l'appareil hotspot et l'appareil connecté partagent le même domaine de broadcast).
- **Transport** : `LanAudioLink` établit une socket TCP unique entre les deux appareils (fiable, ordonnée nativement — pas besoin de ré-implémenter l'ordre ou la retransmission). Le rôle client/serveur est déterministe (l'`uid` le plus petit compose) pour éviter toute collision de connexion.
- **Reprise après coupure** : chaque trame audio porte un `seq` croissant ; l'émetteur garde les trames non confirmées dans un `SendBuffer` (~2 min, ring buffer) ; à la reconnexion, chaque côté annonce le dernier `seq` reçu et l'autre rejoue exactement le manque — aucun audio perdu, seulement retardé.
- **Audio** : PCM 16 bits mono 16 kHz brut par trames de 20 ms (`AudioCapture`/`AudioPlayback`), routé sur le flux **multimédia** (haut-parleur par défaut, bascule automatique vers écouteurs/Bluetooth s'ils sont branchés) plutôt que le flux d'appel.
- **Arrière-plan** : `LanIntercomEngine` vit dans `AppContainer` (portée application), piloté par un `SessionForegroundService` permanent dès la connexion — la réception audio et l'émission VOX continuent écran éteint ou app en arrière-plan ; la notification affiche l'état VOX (actif/désactivé) et le statut de la liaison locale.
- **Roster** : les équipiers détectés en LAN apparaissent directement (icône Wifi) dès réception de leur beacon, indépendamment du cloud.

## État actuel

| Composant | Statut |
|-----------|--------|
| Documentation | En place |
| Backend signaling | Scaffold local + tests (mode cloud parqué) |
| Terraform / GCP | Configuration prête, déploiement manuel |
| Android — mode local | Intercom LAN (UDP beacon + TCP audio), reprise sur coupure, service permanent, PTT/Vox |
| Android — mode cloud | Compose, auth, signaling, WebRTC (parqué, accessible via outils développeur) |
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
