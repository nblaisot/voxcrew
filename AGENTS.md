# AGENTS.md — Règles pour les agents et contributeurs VoxCrew

## Stack imposée

- **Android** : Kotlin, Jetpack Compose, Material 3, Coroutines, StateFlow.
- **Audio** : Opus sur sockets TCP natifs (LAN) avec bascule optionnelle Tailscale — jamais de flux audio HTTP, Firestore ou Cloud Storage.
- **Pas de backend cloud managé** : pas de Cloud Run, Firebase, signaling serveur ni Terraform dans le produit. Un **relais TLS auto-hébergé** optionnel (`relay/`, Mac Mini / Ubuntu / PC) peut servir de 3ᵉ chemin de dial par UUID — la découverte reste LAN-only. **Install / deploy (agents) :** [`docs/relay-deploy.md`](docs/relay-deploy.md) et [`relay/AGENTS.md`](relay/AGENTS.md).

## Principes produit

- PTT et VAD sont des **politiques** sur le **même** pipeline audio (`TransmissionPolicy` → `AudioCapture`) :
  `shouldTransmit = openMic OR pttPressed OR (voiceActivated AND vadDetectsSpeech)`
- Pas d'enregistrement audio par défaut.
- Pas de SFU.
- Le modèle de session accepte N participants ; l’audio réel est un mesh client-side (TCP par pair).

## Qualité

- Tests obligatoires pour les machines d'état Android testables.
- Build et tests verts après chaque étape significative.
- Changements petits, vérifiables, documentés avec le code.
- Aucune dépendance ajoutée sans justification dans le commit ou la PR.

## Sécurité

- Aucun secret dans Git (`.env`, `local.properties`, clés de service).
- Identité locale uniquement (UUID + nom) — pas d’auth cloud.
- Ne jamais logger audio ou secrets.

## Phases de travail

1. Documentation avant code structurant si ambigu.
2. Micro ouvert avant PTT.
3. PTT écran allumé avant foreground service.
4. VAD après PTT fiable — implémenté (Silero VAD, voir `docs/android-audio.md`) ; validation terrain outdoor restant à faire par l'utilisateur.

## Ce qu'il ne faut pas prétendre

- Avoir testé sur téléphone physique, Bluetooth, ou écran éteint sans retour utilisateur.
- Avoir validé la latence ou la qualité audio en conditions réelles.

## Commits

- Un commit cohérent par phase ou sous-ensemble logique.
- Vérifier le diff pour l'absence de secrets avant commit.
- Ne pas pousser sur `main` sans demande explicite.
