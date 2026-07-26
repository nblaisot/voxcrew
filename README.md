# VoxCrew

Application Android privée de communication vocale de groupe pour activités extérieures (ski, gyroroue, randonnée, vélo). Expérience type intercom : session vocale persistante, micro transmis selon une politique locale (PTT, VOX/VAD, ou micro ouvert).

## Architecture

- **LAN d’abord** : intercom direct entre participants — découverte par broadcast UDP, audio Opus sur socket TCP. Aucun serveur, aucun compte cloud.
- **Overlay Tailscale (optionnel)** : une adresse overlay annoncée pendant une rencontre LAN peut servir de repli TCP pour la session en cours. Tailscale ne transporte pas le broadcast de découverte.
- **Identité locale** : UUID stable + nom d’affichage choisis sur l’appareil (`LocalProfileRepository`).
- **Android** : Kotlin, Jetpack Compose, sockets TCP/UDP natifs, codec Opus, Jetpack Telecom pour le routage audio.

```
Galaxy A <== UDP beacon (découverte) + TCP audio Opus (LAN / hotspot / Tailscale) ==> Galaxy B
```

## Mode local (LAN intercom)

Détail dans [android/app/src/main/java/com/nblaisot/voxcrew/lanlink/](android/app/src/main/java/com/nblaisot/voxcrew/lanlink/) :

- **Découverte** : `LanBeacon` diffuse `uid`, nom et port TCP en broadcast UDP (port `47100`) immédiatement puis toutes les 3 s. Un seul enregistrement transitoire existe par UUID.
- **Protocole** : `PeerLink` (séquences, `SendBuffer`, RTT, backlog) au-dessus de `LanTcpClient` / `LanTcpServer`.
- **Reprise** : à la reconnexion, rejeu du gap de séquences — pas de perte, seulement du retard.
- **Audio** : Opus 20 ms, Telecom pour le duplex, PTT / VOX (Silero VAD).
- **Arrière-plan** : `SessionForegroundService` + `LanIntercomEngine` en portée application.
- **Roster** : UUID + dernier nom persistés pour les lignes hors ligne ; aucune adresse, aucun port et aucun chemin réseau ne sont stockés.

## État actuel

| Composant | Statut |
|-----------|--------|
| Documentation | En place (LAN-only) |
| Android — LAN + Tailscale | Intercom, PTT/VOX, Telecom, service permanent |
| CI GitHub | Workflow Android |

## Prérequis

- **Java 17+**
- **Android SDK** (`ANDROID_HOME` → `~/Library/Android/sdk`)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

## Commandes principales

```bash
cd android
cp local.properties.example local.properties   # ajuster sdk.dir si besoin
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Screenshots Play Store : voir [play-screenshots/README.md](play-screenshots/README.md).

## Sécurité / secrets

Ne jamais committer :

- `android/local.properties`, `android/app/google-services.json` (obsolète, ne plus utiliser)
- Mots de passe, clés, tokens

Voir [docs/security.md](docs/security.md) et [AGENTS.md](AGENTS.md).

## Documentation

| Document | Contenu |
|----------|---------|
| [docs/architecture.md](docs/architecture.md) | Vue d’ensemble LAN |
| [docs/android-audio.md](docs/android-audio.md) | Pipeline audio, Telecom, VOX |
| [docs/testing.md](docs/testing.md) | Tests unitaires et manuels |
| [docs/security.md](docs/security.md) | Secrets et bonnes pratiques |
| [docs/roadmap.md](docs/roadmap.md) | Pistes produit |
