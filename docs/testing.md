# Tests VoxCrew

## Backend (automatisés)

```bash
cd backend && npm test
```

Couverture cible :

- `GET /health`, `GET /ready`
- Validation configuration
- WebSocket : auth acceptée/refusée, allowlist
- Création/rejoindre session
- Présence (register, heartbeat, snapshot, offline)
- Rendez-vous P2P (`p2p_connect_request`, `p2p_endpoints`)
- Relais binaire uid-à-uid
- ping/pong, déconnexion, nettoyage
- Erreurs de protocole

## Android (automatisés)

```bash
cd android && ./gradlew testDebugUnitTest
```

Couverture cible :

- Sérialisation messages signaling
- Protocole `PeerLink`, `LanProtocol`, `SendBuffer`
- Transports : `RelayTransport`, `UdpP2pTransport`, `StunClient`
- `TransmissionPolicy` (PTT press/release)
- Parsing erreurs

Capture micro, Bluetooth, hole punching réel : tests manuels sur appareil.

## Checklist manuelle — deux Samsung Galaxy

### Prérequis

- [ ] APK debug installé sur les deux téléphones
- [ ] `google-services.json` présent sur la build
- [ ] Deux comptes Firebase dans l'allowlist
- [ ] Backend déployé ou accessible (URL publique)
- [ ] Permissions micro accordées

### Auth, roster et LAN

- [ ] Connexion compte A et B
- [ ] WebSocket `authenticated` sur les deux
- [ ] Équipiers visibles dans la liste (cloud + LAN si même réseau)
- [ ] Tap sur équipier → lien intercom établi

### Audio LAN

- [ ] Badge chemin **Local** (Wifi)
- [ ] Audio bidirectionnel audible
- [ ] PTT : appui → transmission, relâchement → coupure
- [ ] Toggle Vox désactive PTT

### Routage audio — Galaxy Z Fold 5 / Galaxy S24

- [ ] Sans écouteurs : micro téléphone actif, haut-parleur téléphone, aucune icône micro PTT
- [ ] Bluetooth avec micro : branchement détecté, audio Bluetooth, icône micro Bluetooth, PTT et Vox OK
- [ ] Déconnexion Bluetooth : retour micro téléphone/haut-parleur sans crash, icône supprimée
- [ ] USB avec micro : branchement détecté, audio USB, icône micro USB, PTT et Vox OK
- [ ] Déconnexion USB : retour route précédente ou téléphone sans crash, icône mise à jour
- [ ] Écouteurs sortie seule : audio dans les écouteurs, capture par micro téléphone, aucune icône micro
- [ ] Bluetooth + USB avec micros : micro Bluetooth prioritaire, icône Bluetooth
- [ ] Refus `RECORD_AUDIO` : PTT/VOX capture désactivés, message d'autorisation affiché, reprise après accord
- [ ] Refus `BLUETOOTH_CONNECT` (Android 12+) : message d'autorisation affiché pour route Bluetooth micro, reprise après accord
- [ ] Branchement/débranchement pendant une session active : routage ré-appliqué dynamiquement

### Repli cloud

- [ ] Couper LAN (Wi-Fi / hotspot) → bascule **Internet direct** ou **Relais cloud**
- [ ] RTT et jauge backlog affichés
- [ ] Retour LAN → reprise automatique **Local**

### Reconnexion

- [ ] Couper réseau brièvement → reconnexion signaling
- [ ] Logs exportables sans secrets

### Foreground service

- [ ] Session active, verrouiller écran
- [ ] Notification visible
- [ ] Audio/PTT selon capacités écran verrouillé
- [ ] Retour app → état restauré

### Échec — informations à collecter

- Horodatage, modèle téléphone, version Android
- État UI (connexion, chemin, mode transmission)
- Export diagnostics expurgé (pas de tokens)

## CI

Voir `.github/workflows/` — builds PR sans déploiement automatique.
