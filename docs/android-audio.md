# Audio Android — pipeline et politiques

## Règle fondamentale

Un seul pipeline Opus pour tous les modes :

```text
shouldTransmit =
    mode == OPEN_MIC
    OR pttPressed
    OR (mode == VOICE_ACTIVATED AND vadDetectsSpeech)
```

Pas de second flux micro. Pas d'enregistrement fichier. Pas d'upload backend (sauf relais binaire opaque en dernier recours cloud).

## Pipeline production

```text
AudioRecord → OpusCodec (Concentus, 20 ms frames) → PeerLink.send
                                                         ↓
                                              FrameTransport (LAN / UDP / relay)
                                                         ↓
PeerLink.incomingAudio → AudioPlayback (Opus decode)
```

Implémenté dans `lanlink/` : `AudioCapture`, `AudioPlayback`, `OpusCodec`, `PeerLink`.

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
    → lissage → shouldTransmit → même AudioCapture
```

Paramètres futurs : seuil, hangover, pré-buffer, profils (calme, extérieur, vent).

## Intégration LanIntercomEngine

`LanIntercomEngine` observe `TransmissionPolicy.shouldTransmit` et active/désactive la capture Opus en conséquence. Le module audio ne connaît pas l'UI.

## Permissions

- `RECORD_AUDIO` demandée avant capture
- Refus → message clair, pas de crash

## Routes audio

Priorité MVP : écouteur, haut-parleur, Bluetooth si disponible.

Flux **multimédia** (`AudioManager.STREAM_MUSIC`) pour le playback intercom.

## Diagnostics (UI principale)

- RTT et label de chemin (Local / Internet direct / Relais cloud)
- Backlog audio non accusé (`PeerLink.backlogMs`, jauge plafonnée à 10 s)

## Foreground service

- `SessionForegroundService` permanent pendant l'intercom
- Notification persistante avec état VOX et liaison
- Maintien session écran verrouillé (sous réserve restrictions OEM)

## Ce qui n'est pas dans le MVP

- Enregistrement conversation
- Whisper / transcription
- Reconnaissance locuteur
- Double capture micro
