# Tests VoxCrew

## Android (automatisés)

```bash
cd android && ./gradlew testDebugUnitTest
```

Couverture cible :

- Protocole `PeerLink`, `LanProtocol`, `SendBuffer`
- `TransmissionPolicy` (PTT press/release), VOX / VAD
- Roster / affichage disponibilité (LAN / VPN)
- Telecom / catalogue de routes audio

Capture micro, Bluetooth, LAN réel : tests manuels sur appareil.

## Checklist manuelle — deux appareils

### Prérequis

- [ ] APK debug installé sur les deux téléphones
- [ ] Même Wi-Fi ou hotspot partagé (ou Tailscale pour le chemin VPN)
- [ ] Permissions micro accordées
- [ ] Nom de profil configuré sur chaque appareil

### Roster et LAN

- [ ] Équipiers visibles dans la liste après quelques secondes
- [ ] Tap = inclure/muet · double tap = solo · appui long = oublier
- [ ] Badge chemin **Local** (Wifi) une fois connecté

### Audio LAN

- [ ] Audio bidirectionnel audible
- [ ] PTT : appui → transmission, relâchement → coupure
- [ ] Toggle Vox désactive PTT / active l’écoute

### Routage audio

- [ ] Sans écouteurs : micro téléphone, haut-parleur
- [ ] Bluetooth avec micro : route + icône Bluetooth, PTT/Vox OK
- [ ] Déconnexion Bluetooth : retour téléphone sans crash
- [ ] USB avec micro : route + icône USB
- [ ] Refus `RECORD_AUDIO` : message d’autorisation, reprise après accord
- [ ] Refus `BLUETOOTH_CONNECT` (Android 12+) : message, reprise après accord

### Tailscale (optionnel)

- [ ] Peer hors LAN avec overlay annoncé → chemin **VPN**
- [ ] Retour LAN → reprise **Local**

### Foreground service

- [ ] Session active, verrouiller écran
- [ ] Notification visible
- [ ] Audio/PTT selon capacités écran verrouillé
- [ ] Retour app → état restauré

### Échec — informations à collecter

- Horodatage, modèle téléphone, version Android
- État UI (chemin, mode transmission)
- Export diagnostics expurgé (pas de secrets)

## CI

Voir `.github/workflows/android-ci.yml` — builds PR sans déploiement automatique.
