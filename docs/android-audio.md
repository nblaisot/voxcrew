# Audio Android — pipeline et politiques

## Règle fondamentale

Un seul pipeline WebRTC pour tous les modes :

```text
shouldTransmit =
    mode == OPEN_MIC
    OR pttPressed
    OR (mode == VOICE_ACTIVATED AND vadDetectsSpeech)
```

Pas de second flux micro. Pas d'enregistrement fichier. Pas d'upload backend.

## Interface TransmissionPolicy

```kotlin
interface TransmissionPolicy {
    val mode: TransmissionMode
    val shouldTransmit: StateFlow<Boolean>
}

enum class TransmissionMode {
    OPEN_MIC,
    PUSH_TO_TALK,
    VOICE_ACTIVATED
}
```

## Implémentations

### OpenMicTransmissionPolicy

`shouldTransmit` toujours `true` pendant la session active.

### PushToTalkTransmissionPolicy

- `press` → `true` immédiatement
- `release` → `false` (courte protection fin de mot optionnelle, ~100–200 ms)
- Glissement hors du bouton = release
- Haptique léger à press/release
- La VAD ne coupe pas en mode PTT

### VoiceActivatedTransmissionPolicy (futur)

```text
capture → traitement acoustique → VAD fenêtres 10–20 ms
    → lissage → shouldTransmit → même AudioTrack WebRTC
```

Paramètres futurs : seuil, hangover, pré-buffer, profils (calme, extérieur, vent).

## Intégration WebRTC

```kotlin
// WebRtcSession observe shouldTransmit
policy.shouldTransmit
    .onEach { transmit ->
        localAudioTrack.setEnabled(transmit)
    }
    .launchIn(scope)
```

Le module WebRTC ne connaît pas l'UI ; seulement `shouldTransmit`.

## Permissions

- `RECORD_AUDIO` demandée avant capture
- Refus → message clair, pas de crash

## Routes audio

Priorité MVP : écouteur, haut-parleur, Bluetooth si disponible.

`AudioManager.MODE_IN_COMMUNICATION` pour la session.

## Diagnostics (panneau repliable)

- État `PeerConnection`, ICE
- Candidat sélectionné : `host` / `srflx` / `relay`
- Codec, paquets, jitter, RTT, pertes, débit

## Foreground service (Phase 11)

- Type `microphone`
- Notification persistante avec action Quitter
- Maintien session écran verrouillé (sous réserve restrictions OEM)

## Ce qui n'est pas dans le MVP

- Enregistrement conversation
- Whisper / transcription
- Reconnaissance locuteur
- Double capture micro
