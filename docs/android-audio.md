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

### VoiceActivatedTransmissionPolicy (Vox — implémenté)

Contrairement à PTT/Micro ouvert, Vox ne se contente pas de démarrer/arrêter
`AudioRecord` : le micro tourne en continu pendant que Vox est actif (nécessaire pour
écouter l'apparition de la voix), et c'est la **transmission** qui est contrôlée par la
détection :

```text
AudioRecord (continu) → frame PCM 20 ms ────────────────┐
                              │                          │
                              ▼                          ▼
                    SileroVoiceDetector          OpusCodec.Encoder
                    (VAD neuronal, 512           (toujours actif, encode
                    échantillons/32 ms)          la frame courante)
                              │                          │
                              ▼                          │
                          VoxGate                        │
                (hangover ~600 ms, onset)                │
                              │                           │
                 transmitting? ──oui──► flush pré-roll (~200 ms) + envoi frame courante
                              │
                             non ──► frame bufferisée dans le pré-roll (jamais envoyée)
```

Implémenté dans `audio/` :

- `VoiceDetector` — interface pour le modèle acoustique (découplée du reste du pipeline).
- `SileroVoiceDetector` — implémentation par défaut, basée sur
  [Silero VAD](https://github.com/snakers4/silero-vad) (réseau de neurones, ~2 Mo, ONNX
  Runtime Mobile) via le wrapper Kotlin
  [gkonovalov/android-vad](https://github.com/gkonovalov/android-vad). Choisi plutôt
  qu'une simple porte d'énergie (RMS) ou le VAD WebRTC (GMM) car ces deux approches sont
  connues pour se déclencher à tort sur du bruit non stationnaire extérieur (vent,
  circulation) — Silero, entraîné sur de la parole réelle, est nettement plus robuste
  dans ce cas d'usage.
- `VoxSensitivity` — 5 niveaux exposés à l'UI (curseur), mappés en interne sur le
  `Mode` Silero (`NORMAL`/`AGGRESSIVE`/`VERY_AGGRESSIVE`, qui fixe le seuil de confiance
  0.5/0.8/0.95) et sur `speechDurationMs` (50–200 ms, durée minimale de parole continue
  avant de considérer que c'est bien de la voix).
- `VoxGate` — machine à états pure (testée unitairement) qui ajoute un hangover
  applicatif (~600 ms, différent du lissage interne de Silero) pour ne pas couper la
  transmission entre deux mots, et signale l'instant exact où la transmission démarre
  (`onset`) pour vider le tampon de pré-roll.

Implémenté dans `lanlink/AudioCapture.attachVox` : boucle de capture continue dédiée
(distincte de `attach`, utilisée par PTT/micro ouvert), qui construit le détecteur
paresseusement sur `Dispatchers.IO` (chargement du modèle ONNX) et ne fait jamais
partir sur le réseau les frames capturées pendant le silence — elles sont uniquement
gardées en mémoire (tampon de pré-roll borné) puis jetées si la parole ne se confirme
jamais, conformément au principe « pas d'enregistrement par défaut ».

Paramètres actuellement fixes (candidats à une future itération) : hangover `VoxGate`
(600 ms), pré-roll (~200 ms), `silenceDurationMs` Silero (300 ms). Profils bruit
(vent, extérieur) : hors scope pour l'instant, la sensibilité couvre déjà l'essentiel
du besoin outdoor.

## Intégration LanIntercomEngine

`LanIntercomEngine` observe `TransmissionPolicy.shouldTransmit` et active/désactive la
capture Opus en conséquence pour PTT (`AudioCapture.attach`). Pour Vox, il pilote
directement `AudioCapture.attachVox` et reporte la décision de `VoxGate` dans
`VoiceActivatedTransmissionPolicy.setSpeechDetected(...)`, qui alimente à son tour
`shouldTransmit` — l'UI et la notification persistante n'ont donc besoin d'observer que
`shouldTransmit`/`isTransmitting`, jamais le détecteur directement. Le module audio ne
connaît pas l'UI. L'état Vox actif/inactif et la sensibilité sont persistés dans les
`SharedPreferences` (`voxcrew_lanlink`) et restaurés au démarrage.

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
