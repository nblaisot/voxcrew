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

- `IntercomTelecomSession` — appel self-managed, catalogue d'endpoints et état `TelecomCallState`.
- `TelecomRouteCoordinator` — machine d'état pure où seuls les choix explicites de l'utilisateur
  peuvent demander un changement de route.
- `PcmSpeechLeveler` — nivellement borné et déterministe des seules frames transmises.
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

## Autorité Telecom et permissions

La session self-managed Jetpack Telecom est l'unique propriétaire du routage et du focus.
VoxCrew n'appelle jamais `setCommunicationDevice`, `startBluetoothSco`,
`setPreferredDevice`, ne modifie pas `AudioManager.mode` et ne demande pas le focus.
Android choisit le profil duplex concret et le microphone associé.

Références plateforme : [guide Core Telecom VoIP](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom),
[`CallControlScope`](https://developer.android.com/reference/kotlin/androidx/core/telecom/CallControlScope)
et [prétraitement audio Android](https://source.android.com/docs/core/audio/implement-pre-processing).

- `RECORD_AUDIO` est obligatoire : son absence place la session/pipeline en erreur et
  désactive PTT.
- `BLUETOOTH_CONNECT` (API 31+) est recommandé, mais non bloquant. Son refus peut masquer
  le nom d'un accessoire ; il ne ferme jamais une route courante déjà publiée, ni le
  haut-parleur ou l'USB.
- `MODIFY_AUDIO_SETTINGS` n'est plus demandé par l'application.

## États audio explicites

`AudioRouteSelectionState` sépare sans ambiguïté le catalogue, la cible choisie et la route
confirmée. La cible est un `AudioRouteTarget` (`DEVICE`, `BLUETOOTH` ou `WIRED_USB`) et
chaque accessoire conserve son identifiant Telecom exact. `ManualRouteStatus` décrit
`STARTING`, `REQUESTING`, `CONFIRMED`, `DIVERGED`, `UNAVAILABLE` ou `FAILED`.

`TelecomCallState` contient séparément la phase (`STARTING`, `ACTIVE`, `INACTIVE`,
`FAILED`, `STOPPED`), l'endpoint courant confirmé, l'endpoint sélectionné, les endpoints
disponibles et l'éventuelle erreur fatale de session.

`AudioPipelineState` est indépendant : `CLOSED`, `OPENING`,
`READY(endpointKey, observedInput, observedOutput)` ou `FAILED`. En mode PTT, l'entrée de
VoxCrew au premier plan prépare Telecom et le pipeline avant toute pression. Le bouton reste
gris et désactivé jusqu'à `ACTIVE + READY`; une fois prêt, la pression ne fait que changer
`TransmissionPolicy` et l'encodage peut commencer sur la prochaine frame de 20 ms.

**Le son passe toujours** : `mediaActive` requiert seulement `ACTIVE` + endpoint courant
connu + pas d'erreur de session. L'endpoint courant de la plateforme est par définition un
périphérique qui fonctionne ; le statut de sélection (`CONFIRMED`/`DIVERGED`/`UNAVAILABLE`)
est une information de bannière, jamais une porte sur l'audio ni sur PTT. Un seul blocage
subsiste : après un vrai échec de pipeline (`AudioPipelineState.Failed`), la réactivation
Telecom attend « Réessayer » ou un nouveau choix de sortie.

`AudioRecord.routedDevice` et `AudioTrack.routedDevice` servent aux diagnostics, aux
icônes Bluetooth/USB/filaire et à la **vérification événementielle de route** : un
`addOnRoutingChangedListener` sur le recorder et la track détecte le cas où Telecom croit
l'appel sur Bluetooth mais la plateforme route ailleurs (SCO jamais démarré). Après une
unique re-vérification de 1,5 s (temps de démarrage SCO), une bannière de type divergence
s'affiche avec l'action « Cet appareil » — l'audio continue sur le périphérique réel
(de l'audio sur le mauvais périphérique vaut mieux que pas d'audio). Aucun
`AudioDeviceInfo` n'est conservé comme décision de routage.

## Cycle de demande média

Une demande média **Telecom** existe si au moins une condition est vraie :

- VoxCrew est au premier plan en mode PTT ;
- VOX est activé, au premier plan comme en arrière-plan, car Silero doit écouter en continu.

La réception distante en arrière-plan avec VOX désactivé **ne** réactive **pas** Telecom :
les frames Opus sont jouées via un `MediaInboundPlayer` (`USAGE_MEDIA` +
`AUDIOFOCUS_GAIN_TRANSIENT`) sur la route multimédia courante (haut-parleur / casque BT
média). Deux autorités de focus coexistent donc :

- **Telecom** (duplex `STREAM_VOICE_CALL`) tant que la demande FG/VOX est vraie — préchauffé
  avant toute pression PTT (un provisionnement à la pression dépassait 2 s en pratique) ;
- **MEDIA transitoire** uniquement pour l'inbound arrière-plan + VOX off.

Le focus MEDIA n'est demandé qu'après initialisation réussie de l'`AudioTrack`, abandonné
sur échec, perte de focus (`AUDIOFOCUS_LOSS*`) ou idle ~700 ms. Le passage FG→BG sérialise
la coupure Telecom **avant** que MEDIA puisse jouer (`!telecomSession.hasCall`).

Un pipeline `Failed` déconnecte l'appel Telecom (la demande FG/VOX peut rester pour Retry)
afin de ne pas retenir la musique sur un chemin mort ; Retry réactive et re-prépare.

La première demande Telecom ajoute/active l'appel. Lorsque la dernière demande disparaît,
VoxCrew libère le duplex puis termine complètement l'appel avec `disconnect(LOCAL)`.
`setInactive()` correspond à une mise en attente et n'est pas utilisé comme état idle :
sur certains Samsung, un appel self-managed `ON_HOLD` conserve le contexte de communication
et peut perturber la sortie des autres applications. L'appel VoxCrew ne déclare donc plus
la capacité hold. Il n'existe aucun délai d'inactivité ou heuristique de focus pour Telecom.

En PTT, chaque pression réutilise le même `AudioRecord`, `AudioTrack` et appel Telecom tant
que VoxCrew reste au premier plan. Une pression/relâchement ne crée ni ne détruit de route.
Le passage en arrière-plan annule une pression éventuelle et libère Telecom (VOX off) pour
laisser la musique sur le chemin multimédia ; l'inbound utilise alors `MediaInboundPlayer`.
Les frames qui arrivent pendant que la déconnexion Telecom est encore en cours (`hasCall`)
ne sont plus perdues : elles sont mises en tampon (~2 s max) et rejouées, pilotées par
l'événement `callTornDown` — aucun polling. Le retour au premier plan arrête le lecteur
média, vide ce tampon et reconstruit le chemin Telecom. Les
changements de configuration de l'activité ne sont pas considérés comme un passage réel
en arrière-plan.

La création, l'activation et l'arrêt de l'appel sont sérialisés. Les demandes concurrentes
`start`/`refresh` partagent une seule génération Telecom et un seul `addCall`. Chaque callback
porte cette génération ; après un arrêt, un callback tardif de l'ancien appel est ignoré et
ne peut pas remplacer l'état `ACTIVE/READY` de la session courante.

`LanFrame.MediaActivity` transporte les limites début/fin de parole dans le même espace de
séquence accusé que les frames Opus. Elles survivent donc aux reconnexions et changements
LAN/UDP/relais. Au premier plan (ou VOX on), le récepteur active Telecom sur « début »,
conserve au maximum 250 événements (environ cinq secondes de parole) pendant `OPENING`,
joue toutes les frames dans l'ordre, puis traite « fin » et déconnecte l'appel Telecom
lorsque plus aucune demande n'existe. En arrière-plan + VOX off, Activity ne réveille pas
Telecom ; les frames audio sont décodées et jouées immédiatement en multimédia. Les paquets
UDP reçus hors ordre attendent désormais leur séquence manquante au lieu de faire avancer
artificiellement l'ACK.

En mode VOX, l'appareil local conserve nécessairement Telecom actif pendant que VOX est
activé afin de garder le microphone duplex disponible. Ce comportement résulte d'un choix
explicite de l'utilisateur et peut réduire, interrompre ou rerouter la musique d'une autre
application. En PTT, cet impact Telecom est borné à la présence de VoxCrew au premier plan ;
en arrière-plan, seule la parole entrante prend un focus média transitoire.

## Politique d'endpoints

`getAvailableStartingCallEndpoints()` est collecté même sans appel actif afin d'alimenter
le sélecteur audio du coin supérieur droit. Cette observation ne prend ni focus ni route.
« Cet appareil » (micro intégré + haut-parleur) est toujours sélectionné au lancement.
Les endpoints Bluetooth sont dédupliqués par **adresse MAC** (clé stable `bt:$mac`) : un
renommage ou un dédoublement HEADSET/LE_AUDIO du même accessoire n'affiche qu'une ligne,
avec le nom courant préféré. Watch et Buds restent distincts via des MAC différentes.
La MAC vient du champ interne `mMackAddress` de Jetpack Telecom (accès réflexif gardé par
un test unitaire — une montée de version qui casse le champ fait échouer la CI) avec repli
sur le nom bonded **uniquement si unique** (deux appareils de même nom → non résolu, pas
de fusion hasardeuse).
Les endpoints USB/filaire
sont ajoutés et retirés dynamiquement. La connexion d'un accessoire ne le sélectionne jamais
automatiquement. La sélection explicite est **persistée** (clé, type, MAC, nom) dans les
`SharedPreferences` (`voxcrew_audio_route`) et restaurée au démarrage du processus ; elle
est re-mappée sur le catalogue de chaque génération d'appel (« Cet appareil » → haut-parleur
par type, Bluetooth → par MAC, filaire/USB → par type + nom).

Une fois l'appel actif :

- `currentCallEndpoint` reste la vérité sur la route physique courante ; le duplex s'ouvre
  sur cet endpoint dès `ACTIVE`, que la sélection soit confirmée ou non ;
- si l'appel démarre ailleurs que sur la sélection (transitoire écouteur d'un nouvel appel
  au retour premier plan, par exemple), le coordinateur **ré-affirme la sélection une seule
  fois par génération d'appel** (`requestEndpoint`) — c'est l'exécution du choix mémorisé
  de l'utilisateur, pas de l'auto-routage. Si la plateforme atterrit encore ailleurs, l'état
  devient `DIVERGED` : bannière + action « Cet appareil », l'audio continue ;
- la cible sélectionnée est passée comme `preferredStartingCallEndpoint` lors de la
  création de l'appel ; si elle est absente, l'appel démarre sur le haut-parleur (il existe
  toujours un périphérique qui fonctionne) et la sélection est conservée pour restauration ;
- seul un clic dans le menu peut demander un autre changement pendant un appel, exactement
  une fois et vers l'identifiant choisi ; les autres clics sont refusés pendant cette requête ;
- si Samsung change spontanément de route après confirmation, l'état devient `DIVERGED` —
  bannière seulement, aucune déconnexion, aucune correction automatique. La confirmation
  compare l'identifiant Telecom **ou** la MAC Bluetooth (`sameTelecomEndpoint`) : un flip de
  profil SCO↔LE Audio du même accessoire ne déclenche pas de fausse divergence ;
- la perte de l'accessoire sélectionné passe `UNAVAILABLE` (bannière + « Cet appareil »),
  demande une fois le haut-parleur si la plateforme a rerouté vers l'écouteur, et **mémorise
  la sélection** : au retour de l'accessoire, une unique requête automatique le restaure ;
- un refus de requête devient `FAILED` et reconstruit la génération Telecom autour du choix ;
  la réconciliation de demande média réactive l'appel immédiatement ;
- `onSetInactive` est traité comme une demande de terminaison, et `onDisconnect` ferme
  immédiatement le duplex et annule PTT.

Le bouton PTT et l'action de barre supérieure affichent la sélection même à l'état idle ;
pendant un appel, le bouton reflète la route confirmée. Une Watch et des Buds sont deux
entrées indépendantes même si leur type est identique. « Cet appareil » résout strictement
le `TYPE_SPEAKER` fourni par Telecom, jamais l'écouteur téléphonique. `AudioDeviceInfo` ne sert qu'à
distinguer visuellement USB/filaire et aux diagnostics. Il n'existe aucune boucle de retry,
délai, debounce, timeout de confirmation ou route SCO historique : les seules requêtes
automatiques sont bornées à une par événement (une ré-affirmation par génération d'appel,
une restauration par retour d'accessoire, un repli haut-parleur par disparition).

## Pipeline duplex sérialisé

Pour chaque nouvelle clé d'endpoint courant, sous un mutex unique :

1. annulation de la transmission pendant le remplacement du graphe ;
2. arrêt/libération des deux anciens flux ;
3. création/démarrage de `AudioTrack` (`STREAM_VOICE_CALL`, parole, mono 16 kHz) ;
4. création/démarrage de `AudioRecord` (`VOICE_COMMUNICATION`, mono 16 kHz) ;
5. publication de `READY` seulement si les deux ont réussi.

PTT et VOX restent des politiques sur cette capture continuellement ouverte. Les lectures
courtes sont assemblées jusqu'à une frame PCM exacte de 20 ms ; les écritures courtes sont
drainées entièrement. Une erreur de construction, démarrage, lecture ou écriture libère
les deux côtés et publie `FAILED`. La reprise nécessite un nouvel événement plateforme ou
l'action utilisateur « Réessayer ».

## Prétraitement et niveau de parole

`VOICE_COMMUNICATION` demande le traitement de communication calibré par Android et le
constructeur du téléphone. VoxCrew n'ajoute plus de chaîne AEC/NS/AGC explicite.

Avant Opus, `PcmSpeechLeveler` stabilise uniquement les frames émises : RMS cible 2500,
porte de silence 64, gain borné de −6 dB à +18 dB, limiteur ±30000, attaque/relâchement
déterministes 0,25/0,50 par frame. Il est réinitialisé au début d'une transmission et à
chaque remplacement d'endpoint. Silero reçoit toujours le PCM brut : le niveau appliqué
ne peut donc pas modifier une décision VOX. Le PCM reçu n'est pas modifié ; le volume
reste celui du flux d'appel Android.

`VoxEchoGuard` conserve sa garde contre les faux déclenchements VOX pendant une réception.
Elle ne change ni le routage ni le PTT.

## Diagnostics et validation physique

Logcat (`IntercomTelecomSession`, `AudioCapture`, `AudioPlayback`, `LanIntercomEngine`)
expose sans données audio : endpoint confirmé, périphériques observés, RMS brut/nivelé,
taille Opus, livraison/file transport, frames reçues/décodées et résultat d'écriture.

Matrice à valider sur appareils physiques, dans les deux sens : téléphones nus, Fold avec
Galaxy Buds, écouteurs USB avec micro sur chaque téléphone, connexion/déconnexion pendant
la session, Bluetooth+USB simultanés, puis déconnexion/recréation Telecom entre deux PTT. Vérifier que
PTT ne reste jamais bloqué, que le téléphone nu utilise le haut-parleur, que les routes
accessoires sont duplex, que le PCM parlé est non silencieux et que les écritures réussissent.

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
