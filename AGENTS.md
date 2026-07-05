# AGENTS.md — Règles pour les agents et contributeurs VoxCrew

## Stack imposée

- **Android** : Kotlin, Jetpack Compose, Material 3, Coroutines, StateFlow.
- **Backend** : Node.js LTS, TypeScript strict, Fastify.
- **Audio** : Opus sur sockets TCP/UDP natifs (LAN) avec repli cloud (hole punching UDP + relais WebSocket binaire) — jamais de flux audio HTTP, Firestore ou Cloud Storage.
- **Cloud** : Cloud Run pour signaling, présence, rendez-vous P2P et relais binaire uniquement.
- **Infra** : Terraform pour les ressources GCP ciblées.

## Principes produit

- PTT et VAD sont des **politiques** sur le **même** pipeline audio (`TransmissionPolicy` → `AudioCapture`) :
  `shouldTransmit = openMic OR pttPressed OR (voiceActivated AND vadDetectsSpeech)`
- Pas d'enregistrement audio par défaut.
- Pas de SFU dans le MVP.
- Le modèle de session accepte N participants ; le MVP n'implémente l'audio réel qu'entre deux pairs.

## Qualité

- Tests obligatoires pour la logique backend et les machines d'état Android testables.
- Build et tests verts après chaque étape significative.
- Changements petits, vérifiables, documentés avec le code.
- Aucune dépendance ajoutée sans justification dans le commit ou la PR.

## Sécurité

- Aucun secret dans Git (`.env`, `terraform.tfvars`, `google-services.json`, clés de service).
- Auth Firebase validée côté serveur + allowlist UID.
- Validation stricte de tous les messages WebSocket.
- Ne jamais logger tokens, audio, ou secrets.

## Phases de travail

1. Documentation avant code structurant si ambigu.
2. Backend local avant GCP.
3. Signaling avant audio cloud fallback.
4. Micro ouvert avant PTT.
5. PTT écran allumé avant foreground service.
6. VAD après PTT fiable — implémenté (Silero VAD, voir `docs/android-audio.md`) ; validation terrain outdoor restant à faire par l'utilisateur.

## Ce qu'il ne faut pas prétendre

- Avoir configuré GCP sans confirmation utilisateur.
- Avoir testé sur téléphone physique, Bluetooth, ou écran éteint sans retour utilisateur.
- Avoir validé la latence ou la qualité audio en conditions réelles.

## Commits

- Un commit cohérent par phase ou sous-ensemble logique.
- Vérifier le diff pour l'absence de secrets avant commit.
- Ne pas pousser sur `main` sans demande explicite.
