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
- [ ] Même Wi-Fi ou hotspot partagé pour la découverte
- [ ] Permissions micro accordées
- [ ] Nom de profil configuré sur chaque appareil

### Roster et LAN

- [ ] Équipiers visibles dans la liste après quelques secondes
- [ ] Un même UUID ne crée jamais de doublon
- [ ] Peer hors portée : passage hors ligne après environ 15 s
- [ ] Retour du peer : la même ligne UUID redevient disponible au prochain beacon
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
- [ ] Installation propre avec permissions refusées : l’activité reste ouverte, sans crash du foreground service

### Tailscale (optionnel)

- [ ] Après rencontre LAN, perte du LAN avec overlay annoncé → chemin **VPN** pour la session en cours
- [ ] Retour LAN → reprise **Local**
- [ ] Deux démarrages uniquement via Tailscale ne se découvrent pas (limite connue, pas une régression)

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
