# Roadmap VoxCrew

## MVP (en cours)

- [x] Documentation et conventions
- [x] Backend signaling local + Docker
- [ ] GCP / Firebase (configuration manuelle — voir docs/gcp-setup.md)
- [x] Terraform + scripts déploiement Cloud Run
- [x] Android scaffold Compose
- [x] Client signaling authentifié
- [x] WebRTC data channel diagnostic
- [x] Audio micro ouvert
- [x] Push-to-talk
- [x] Foreground service
- [x] CI GitHub

## Post-MVP proche

- TURN serveur (coturn ou service managé)
- VAD (Voice Activity Detection) sur même pipeline
- Reconnexion WebRTC robuste
- 3+ participants (mesh signaling, audio 2-pairs puis extension)
- Workload Identity Federation pour CI deploy

## Moyen terme

- Groupes mesh WebRTC (petits groupes)
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
