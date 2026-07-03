# VoxCrew

Application Android privée de communication vocale de groupe pour activités extérieures (ski, gyroroue, randonnée, vélo). Expérience type intercom : session vocale persistante, micro transmis selon une politique locale (PTT, VAD future, ou micro ouvert).

## Architecture (MVP)

- **Audio** : WebRTC peer-to-peer direct entre participants (pas de transit audio par le backend).
- **Backend** : Node.js / TypeScript / Fastify sur Google Cloud Run — signaling WebSocket, auth Firebase, sessions.
- **Android** : Kotlin, Jetpack Compose, WebRTC natif, Firebase Authentication.

```
Galaxy A <====== WebRTC audio ======> Galaxy B
     \                              /
      \---- WSS signaling (Cloud Run) ----/
```

## État actuel

| Composant | Statut |
|-----------|--------|
| Documentation | En place |
| Backend signaling | Scaffold local + tests |
| Terraform / GCP | Configuration prête, déploiement manuel |
| Android | Compose, auth, signaling, WebRTC, audio, PTT, foreground service |
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
