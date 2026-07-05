# Roadmap VoxCrew

## MVP (en cours)

- [x] Documentation et conventions
- [x] Backend signaling local + Docker
- [ ] GCP / Firebase (configuration manuelle — voir docs/gcp-setup.md)
- [x] Terraform + scripts déploiement Cloud Run
- [x] Android scaffold Compose
- [x] Client signaling authentifié
- [x] Intercom LAN (UDP beacon + TCP Opus)
- [x] Repli cloud (hole punching UDP + relais WebSocket)
- [x] Push-to-talk et Vox (interfaces)
- [x] Foreground service
- [x] CI GitHub
- [ ] Validation terrain Galaxy réels (local ↔ cloud)

## Post-MVP proche

- VAD (Voice Activity Detection) sur le pipeline Opus
- Reconnexion robuste après changement réseau
- 3+ participants
- Chiffrement applicatif LAN (évaluer)
- Workload Identity Federation pour CI deploy

## Moyen terme

- Profils audio (vent, extérieur)
- Wear OS (exploration)
- Gestion groupes / invitations

## Long terme

- SFU pour grands groupes
- Reconnaissance locuteur (hors scope actuel)
- Whisper / transcription (opt-in explicite uniquement)
- Publication Play Store (si un jour public)

## Hors scope permanent (sauf décision produit)

- Enregistrement conversations par défaut
- Stockage audio cloud
- OpenClaw / assistants vocaux intégrés
