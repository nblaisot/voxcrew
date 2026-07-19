# Architecture VoxCrew

## Vue générale

```mermaid
flowchart TB
  subgraph clients [Clients Android]
    AppA[App Participant A]
    AppB[App Participant B]
  end
  subgraph lanlink [LanIntercomEngine]
    PL[PeerLink]
    LAN[LanTcpServer plus LanTcpClient]
    Beacon[LanBeacon UDP]
  end
  AppA --> PL
  AppB --> PL
  PL --> LAN
  AppA --> Beacon
  AppB --> Beacon
  LAN <-->|TCP Opus| AppB
  Beacon -.->|broadcast discovery| AppB
```

Aucun serveur applicatif : découverte et audio restent entre les appareils (LAN / hotspot / Tailscale).

## Responsabilités

| Composant | Rôle |
|-----------|------|
| `LocalProfileRepository` | UUID + nom d’affichage locaux |
| `LanIntercomEngine` | Découverte, mesh TCP, capture/playback Opus |
| `LanBeacon` | Annonces UDP périodiques |
| `PeerLink` / `LanTcp*` | Protocole + transport TCP par pair |
| Jetpack Telecom | Routage / focus audio duplex |

## Chemin audio

1. **Local** — beacon UDP + TCP Opus sur le même réseau (ou hotspot)
2. **VPN (Tailscale)** — si le peer annonce une adresse overlay et que le LAN disparaît

Un seul espace de séquence `PeerLink` survit au changement de chemin (make-before-break vers le LAN quand il revient).

## Cycle de vie intercom

```mermaid
stateDiagram-v2
  [*] --> Profile: first launch
  Profile --> Main: name saved
  Main --> Profile: change name
  Main --> [*]: quit
```

Couches découplées : `LocalProfileRepository`, `LanIntercomEngine`, `TransmissionPolicy`, UI Compose.

## UI

Après choix du nom, l’utilisateur arrive sur un **écran unique** : roster des équipiers à proximité, inclusion/muet, PTT/VOX, route audio.
