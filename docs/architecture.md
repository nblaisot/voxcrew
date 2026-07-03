# Architecture VoxCrew

## Vue générale

```mermaid
flowchart TB
  subgraph clients [Clients Android]
    AppA[App Participant A]
    AppB[App Participant B]
  end
  subgraph gcp [Google Cloud]
    CR[Cloud Run voxcrew-signaling]
    FA[Firebase Auth]
    FS[(Firestore)]
  end
  AppA -->|HTTPS WSS TLS| CR
  AppB -->|HTTPS WSS TLS| CR
  AppA -->|Firebase ID token| FA
  AppB -->|Firebase ID token| FA
  AppA <-->|SRTP WebRTC audio P2P| AppB
  CR --> FS
```

Le backend ne reçoit, ne stocke ni ne traite l'audio en fonctionnement normal.

## Responsabilités

| Composant | Rôle |
|-----------|------|
| Firebase Auth | Identité utilisateur, jetons ID |
| Cloud Run | Signaling WebSocket, sessions, présence éphémère |
| Firestore | Données persistantes minimales (si nécessaire) |
| WebRTC P2P | Transport audio chiffré SRTP |

## Établissement d'une connexion WebRTC

```mermaid
sequenceDiagram
  participant A as Galaxy A
  participant S as Signaling Server
  participant B as Galaxy B
  A->>S: authenticate(token)
  S-->>A: authenticated
  A->>S: create_session
  S-->>A: session_created
  B->>S: join_session(sessionId)
  S-->>B: session_joined
  S-->>A: participant_joined(B)
  A->>S: offer(SDP)
  S-->>B: offer
  B->>S: answer(SDP)
  S-->>A: answer
  A->>S: ice_candidate
  S-->>B: ice_candidate
  B->>S: ice_candidate
  S-->>A: ice_candidate
  Note over A,B: PeerConnection CONNECTED
  A-->>B: WebRTC media/data direct
```

## Cycle de vie d'une session

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Authenticating: open WebSocket
  Authenticating --> Authenticated: token OK
  Authenticating --> Idle: auth_error
  Authenticated --> InSession: create/join session
  InSession --> WebRTCConnecting: peer present
  WebRTCConnecting --> WebRTCConnected: ICE connected
  WebRTCConnected --> InSession: peer left
  InSession --> Authenticated: leave_session
  Authenticated --> Idle: disconnect
  WebRTCConnected --> WebRTCReconnecting: ICE failed
  WebRTCReconnecting --> WebRTCConnected: recovered
```

## États Android (couche application)

```mermaid
stateDiagram-v2
  [*] --> Login
  Login --> Home: Firebase auth OK
  Home --> Session: create/join
  Session --> SessionActive: signaling + WebRTC OK
  SessionActive --> Session: recoverable error
  Session --> Home: leave
  Home --> Login: sign out
```

Couches découplées : `AuthRepository`, `SignalingClient`, `WebRtcSession`, `TransmissionPolicy`, UI Compose.

## Politique de transmission audio

Un seul pipeline :

```
AudioSource → WebRTC AudioTrack → PeerConnection
                    ↑
         TransmissionPolicy.shouldTransmit
```

Modes MVP : `OPEN_MIC`, `PUSH_TO_TALK`. Futur : `VOICE_ACTIVATED` (VAD).

## Évolution vers les groupes

### Deux participants (MVP)

Connexion WebRTC directe full-mesh à 1 lien.

### Petit groupe (futur)

Mesh P2P : chaque paire établit un PeerConnection.

- Avantages : pas d'infrastructure SFU
- Inconvénients : upload × (N−1), batterie, complexité signaling

### Gros groupe (futur)

SFU (Selective Forwarding Unit) : chaque client envoie une piste au SFU qui redistribue.

```mermaid
flowchart LR
  A[Client A] --> SFU[SFU]
  B[Client B] --> SFU
  C[Client C] --> SFU
  SFU --> A
  SFU --> B
  SFU --> C
```

Le protocole de signaling identifie déjà `sessionId`, `senderId`, `recipientId` pour supporter le routage pair-à-pair et futur SFU.

## ICE / NAT

- MVP : STUN public configurable (développement uniquement).
- Production : TURN requis pour de nombreux réseaux mobiles/NAT symétrique.
- Diagnostics : afficher type de candidat sélectionné (`host`, `srflx`, `relay`).

## Stockage et confidentialité

Par défaut : aucun audio enregistré, aucun transit audio par le backend, aucune transcription.

## Connectivité local-first (post-MVP cloud)

```mermaid
flowchart LR
  A[Galaxy A] <-->|Local WebRTC| B[Galaxy B]
  A <-->|Cloud control optional| CR[Cloud Run]
  B <-->|Cloud control optional| CR
  A -->|NSD or QR| LS[Local Ktor signaling on host]
  B --> LS
```

Voir [connectivity-orchestration.md](connectivity-orchestration.md) et [local-signaling.md](local-signaling.md).

- Préférence LAN stable ; fallback cloud transparent
- Même session logique (`sessionId`, `participantId`) à travers les bascules
- `ConnectivityOrchestrator` + `WebRtcConnectionSwitcher` + générations
- Audio toujours via un seul pipeline WebRTC actif
