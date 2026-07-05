# Architecture VoxCrew

## Vue générale

```mermaid
flowchart TB
  subgraph clients [Clients Android]
    AppA[App Participant A]
    AppB[App Participant B]
  end
  subgraph lanlink [LanIntercomEngine]
    PL[PeerLink + Opus]
    LAN[LanTcpTransport]
    UDP[UdpP2pTransport]
    REL[RelayTransport]
  end
  subgraph gcp [Google Cloud]
    CR[Cloud Run voxcrew-signaling]
    FA[Firebase Auth]
  end
  AppA --> PL
  AppB --> PL
  PL --> LAN
  PL --> UDP
  PL --> REL
  LAN <-->|TCP Opus LAN| AppB
  UDP <-->|UDP Opus direct| AppB
  REL -->|WSS binary relay| CR
  CR -->|WSS binary relay| REL
  AppA -->|Firebase ID token| FA
  AppB -->|Firebase ID token| FA
  AppA -->|WSS JSON signaling| CR
  AppB -->|WSS JSON signaling| CR
```

Le backend ne parse, ne stocke ni ne traite l'audio en fonctionnement normal. Seul le relais WebSocket binaire (dernier recours) forward des trames opaques uid-à-uid.

## Responsabilités

| Composant | Rôle |
|-----------|------|
| Firebase Auth | Identité utilisateur, jetons ID |
| Cloud Run | Signaling WebSocket, présence, rendez-vous P2P, relais binaire |
| `LanIntercomEngine` | Découverte LAN, protocole `PeerLink`, capture/playback Opus, bascule transport |
| `CloudRunSignalingTransport` | WebSocket partagé : JSON (signaling) + binaire (relais audio) |

## Chemin audio production

Priorité automatique dans `LanIntercomEngine` :

1. **Local** — `LanBeacon` (UDP discovery) + `LanTcpTransport` (TCP)
2. **Internet direct** — `UdpP2pTransport` (STUN + hole punching UDP)
3. **Relais cloud** — `RelayTransport` (trames binaires opaques sur le WebSocket existant)

Un seul espace de séquence `PeerLink` survit à chaque changement de transport (make-before-break vers le local quand il revient).

## Rendez-vous cloud fallback

```mermaid
sequenceDiagram
  participant A as Galaxy A
  participant S as Signaling Server
  participant B as Galaxy B
  A->>S: authenticate(token)
  S-->>A: authenticated
  A->>S: p2p_connect_request(recipientId=B)
  S-->>B: p2p_connect_request
  A->>S: p2p_endpoints(publicHost, publicPort)
  S-->>B: p2p_endpoints
  B->>S: p2p_endpoints(publicHost, publicPort)
  S-->>A: p2p_endpoints
  Note over A,B: UDP hole punch (Opus direct)
  alt punch fails
    A->>S: binary relay frame
    S-->>B: binary relay frame
  end
```

## Cycle de vie intercom

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Discovering: start engine
  Discovering --> LocalConnected: LAN TCP up
  Discovering --> CloudFallback: LAN timeout/degraded
  CloudFallback --> UdpConnected: hole punch OK
  CloudFallback --> RelayConnected: relay started
  LocalConnected --> CloudFallback: LAN lost
  UdpConnected --> LocalConnected: LAN returns
  RelayConnected --> LocalConnected: LAN returns
  LocalConnected --> Idle: stop
```

## États Android (couche application)

```mermaid
stateDiagram-v2
  [*] --> Login
  Login --> Main: Firebase auth OK
  Main --> Login: sign out
```

Couches découplées : `AuthRepository`, `SignalingClient`, `LanIntercomEngine`, `TransmissionPolicy`, UI Compose.

## Politique de transmission audio

Un seul pipeline Opus :

```
AudioRecord → Opus encode → PeerLink.send
                              ↑
                   TransmissionPolicy.shouldTransmit
```

Modes MVP : `OPEN_MIC`, `PUSH_TO_TALK`. Futur : `VOICE_ACTIVATED` (VAD).

## Évolution vers les groupes

### Deux participants (MVP)

Un lien audio actif entre la paire sélectionnée.

### Petit groupe (futur)

Mesh ou relais centralisé — le protocole `PeerLink` et le signaling uid-à-uid préparent le routage pair-à-pair.

### Gros groupe (futur)

SFU ou serveur de relais dédié.

## Stockage et confidentialité

Par défaut : aucun audio enregistré, aucun transit audio parsé par le backend (sauf forward binaire opaque en relais).

## UX post-login (écran principal)

Après authentification Firebase, l'utilisateur arrive sur un **écran unique** :

- Connexion signaling cloud automatique (présence / roster)
- Découverte LAN en parallèle via `LanIntercomEngine`
- Liste d'équipiers avec email et icône de transport (Wifi = LAN, Cloud = internet, hors ligne = vu précédemment)
- Tap sur un équipier → lien intercom 1:1
- Bouton PTT rouge (maintenir pour parler) ; toggle **Vox** désactive le PTT

Configurer l'URL Cloud Run via `SIGNALING_BASE_URL` dans `android/local.properties` (voir `local.properties.example`).
