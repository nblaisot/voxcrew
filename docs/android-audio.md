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

- `IntercomAudioSession` — façade de session audio, routage dynamique et état `AudioRouteState`.
- `AudioRouteSelector` — politique pure de sélection micro/sortie, permissions et priorité des périphériques.
- `VoiceCommunicationAudioFocus` — focus audio VoIP pendant la session.
- `CaptureAudioEffects` — AEC, NS, AGC sur la session capture.
- `VoxEchoGuard` — garde anti faux-déclenchement VOX pendant la réception.
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
- `BLUETOOTH_CONNECT` demandée à l'exécution (API 31+) pour le routage BT
- Refus → message clair, pas de crash

## Routes audio

Objectif : choisir le micro **avant** `AudioRecord`, VOX et garde d'écho, puis garder
l'état visible pendant les branchements/débranchements. La sortie audio n'est pas
affichée à l'utilisateur ; seul le micro actif peut produire une icône dans la zone PTT.

### Politique déterministe (`AudioRouteSelector.resolve`)

`AudioRouteState` expose `micKind`, `captureDevice`, `outputDevice`, `playbackUsage`,
`audioMode`, `routeReady` et `permissionIssue`.

| Situation | Capture | Sortie | Mode / usage | Icône PTT |
|---|---|---|---|---|
| Aucun périphérique externe | Micro téléphone | Haut-parleur téléphone | `MODE_NORMAL` / `USAGE_MEDIA` | Aucune |
| Bluetooth avec micro | Micro Bluetooth | Bluetooth | `MODE_IN_COMMUNICATION` / `USAGE_VOICE_COMMUNICATION` | Bluetooth |
| Bluetooth sortie seule | Micro téléphone | Bluetooth | `MODE_NORMAL` / `USAGE_MEDIA` | Aucune |
| USB avec micro | Micro USB | USB si disponible | `MODE_NORMAL` / `USAGE_MEDIA` | USB |
| USB sortie seule | Micro téléphone | USB | `MODE_NORMAL` / `USAGE_MEDIA` | Aucune |
| USB entrée seule | Micro USB | Meilleure sortie disponible | `MODE_NORMAL` / `USAGE_MEDIA` | USB |
| Bluetooth + USB avec micros | Micro Bluetooth | Bluetooth | `MODE_IN_COMMUNICATION` / `USAGE_VOICE_COMMUNICATION` | Bluetooth |

Bluetooth gagne sur USB quand les deux micros sont disponibles. Les cas filaires
analogiques/USB-C exposés par Android comme `WIRED_*` restent supportés comme sortie
média, mais ne produisent pas de nouvelle icône micro.

### Application plateforme (`IntercomAudioSession` / `AndroidAudioRouter`)

Modèle **session d'appel** (self-managed VoIP Android) : une route communication stable
pour toute la durée de l'intercom, pilotée uniquement par les callbacks plateforme — pas
de timers, pas d'inspection PCM pour changer de route.

- `enter()` au démarrage intercom : si micro Bluetooth présent → `MODE_IN_COMMUNICATION`,
  focus VoIP, `setCommunicationDevice(sink)` depuis `availableCommunicationDevices()`.
- `exit()` à la fin de session : `clearCommunicationDevice()`, restauration du mode.
- `AudioDeviceCallback` + `OnCommunicationDeviceChangedListener` ré-appliquent la politique
  à chaque ajout/retrait/changement de périphérique communication.
- `routeReady=false` tant que `communicationDevice()` ne confirme pas le sink demandé
  (callback plateforme, pas de polling applicatif).
- API 26–30 : repli `startBluetoothSco()` pour les routes Bluetooth micro.
- Routes média (speaker, USB, BT sortie seule) : `MODE_NORMAL`, `USAGE_MEDIA`.
- Échec `setCommunicationDevice` avec micro BT encore présent : `routeReady=false` (attente callback), pas de bascule téléphone.
- Phase 2 (Samsung) : session Telecom self-managed (`IntercomTelecomSession`) enregistrée pendant l'intercom pour signaler un appel VoIP au système.

**Explicitement rejeté** : détection de silence PCM pour rerouter, timers de libération
idle, split passif/actif PTT vs réception, blacklist de micros Bluetooth.

### Capture et lecture

Ordre strict de préparation (`LanIntercomEngine.prepareAudioPathLocked`) :

1. `awaitRouteReady()` — `communicationDevice()` confirme le sink BLE
2. `awaitRoutingApplied()` — thread routage a fini `setCommunicationDevice`
3. `AudioPlayback.warmUp()` — `AudioTrack` `USAGE_VOICE_COMMUNICATION` avant capture
4. `AudioCapture.attach()` — `AudioRecord` avec `setPreferredDevice(bleInput)` et `audioSessionId` partagé

- `LanIntercomEngine` ouvre la session audio au démarrage ; capture/playback s'attachent dès que `routeReady=true` (pas au premier PTT).
- PTT ne fait que basculer `TransmissionPolicy.shouldTransmit` ; le micro reste ouvert
  pendant toute la session (encode seulement quand `shouldTransmit` est vrai).
- `AudioCapture` attend `routeReady` avant d'ouvrir `AudioRecord`.
- Micro Bluetooth (guide Android BLE recording) : `AudioSource.MIC` +
  `setPreferredDevice(bleInput)` en plus de `setCommunicationDevice(sink)` ; effets AEC liés
  au `audioSessionId` de `AudioTrack` (warmUp avant capture).
- Micro USB : `setPreferredDevice` sur l'entrée USB explicite.
- Diagnostics légers sur les premières frames (`routedType`, `pcmRms`) — logging uniquement,
  aucune décision de routage.
- `AudioPlayback` : `AudioTrack` avec `playbackUsage` de la route (`VOICE_COMMUNICATION`
  en duplex BT, `USAGE_MEDIA` sinon), créé au `warmUp()` session.
- `AudioPlayback` utilise `AudioTrack.setPreferredDevice` pour les sorties média explicites
  (`MODE_NORMAL` uniquement). En duplex Bluetooth (`MODE_IN_COMMUNICATION`), la sortie suit
  `setCommunicationDevice`.
- Changement de route (callback) : `playback.refreshRoute()` + recréation capture si la clé
  de route change (`LanIntercomEngine.watchAudioRoute`).

### Bluetooth et écouteurs sans fil

- Micro Bluetooth reconnu : `BLE_HEADSET`, `BLUETOOTH_SCO`, `HEARING_AID`.
- Sortie Bluetooth reconnue : `BLE_HEADSET`, `BLUETOOTH_SCO`, `BLUETOOTH_A2DP`,
  `BLE_SPEAKER`, `HEARING_AID`.
- API 31+ : `BLUETOOTH_CONNECT` manquant devient `AudioPermissionIssue.BLUETOOTH_CONNECT`
  dès qu'une route Bluetooth micro est nécessaire ou qu'une API lève `SecurityException`.
- Le temps d'activation Bluetooth n'est pas supposé fixe : l'UI peut afficher une route
  non prête, et la capture reste désactivée jusqu'à confirmation.

### Indicateurs UI (écran principal)

Sur le bouton PTT : icône Bluetooth si `micKind == BLUETOOTH` et `routeReady`, icône USB
si `micKind == USB` et `routeReady`, aucune icône pour le micro téléphone ou une sortie
externe sans micro. Aucun état de sortie n'est affiché.

Permissions :

- `RECORD_AUDIO` manquant désactive PTT/VOX capture et affiche une action pour accorder
  le micro.
- `BLUETOOTH_CONNECT` (API 31+) est demandé à la demande pour les routes Bluetooth micro.
- Les permissions sont revérifiées au retour app et après chaque résultat de permission,
  puis le routage est ré-appliqué.

Diagnostics logcat : `IntercomAudioSession`, `AudioCapture`, `VoiceCommunicationAudioFocus`.

## Annulation d'écho (AEC)

Problème : en mains libres, l'audio reçu est diffusé sur le haut-parleur, capté par le
micro et renvoyé au correspondant (surtout en mode VOX où le micro tourne en continu).

Solution : chemin VoIP natif Android, sans dépendance tierce :

```text
LanIntercomEngine.start()
  → IntercomAudioSession.enter()     (route communication pour toute la session si BT mic)
  → routeReady                       (callback OnCommunicationDeviceChangedListener)
  → AudioPlayback.warmUp()           (AudioTrack VoIP, référence AEC)
  → AudioCapture.attach()            (AudioRecord ouvert session entière ; PTT = transmit only)
```

Effets attachés sur la session [AudioRecord] quand disponibles (`CaptureAudioEffects`) :

- `AcousticEchoCanceler` (AEC)
- `NoiseSuppressor`
- `AutomaticGainControl`

La référence écho (signal joué) est fournie par le HAL — pas de tap PCM applicatif.

Garde applicative VOX (`VoxEchoGuard`) : pendant ~100 ms après le début d'une réception,
les décisions VAD sont forcées à « non-parole » pour limiter les faux déclenchements
résiduels sur certains OEM. Le PTT n'est pas affecté.

Diagnostics logués au premier attach (`CaptureAudioEffects`) : disponibilité/activation
AEC, NS, AGC. La qualité acoustique reste à valider sur téléphone physique (voir AGENTS.md).

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
