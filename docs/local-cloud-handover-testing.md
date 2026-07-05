# Tests manuels bascule local / repli cloud

Appareils cibles : Samsung Galaxy Z Fold 5 (hôte hotspot) + Galaxy S25.

## Préparation

- [ ] Même build debug/release sur les deux téléphones
- [ ] Comptes Firebase dans l'allowlist
- [ ] `google-services.json` installé dans `android/app/`
- [ ] Permissions : micro, notifications, Wi‑Fi
- [ ] Désactiver optimisation batterie agressive si possible
- [ ] Noter les UID Firebase (non secrets)

### Installation APK (sans CI store)

```bash
cd android
./gradlew assembleDebug
adb -s <serial-fold5> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial-s25> install -r app/build/outputs/apk/debug/app-debug.apk
```

Lister les appareils : `adb devices`. **Ne pas installer sur téléphones réels sans validation explicite.**

### Logs expurgés

```bash
adb logcat -s VoxCrew LanIntercomEngine SignalingClient PeerLink \
  | grep -v -E 'token|secret|Bearer'
```

## A — Hotspot local avec Internet

1. Activer hotspot sur Fold 5
2. Connecter S25 au hotspot
3. Lancer VoxCrew sur les deux, se connecter
4. Sélectionner l'équipier sur chaque appareil
5. Vérifier chemin **Local** (icône Wifi)
6. Tester PTT et Vox
7. Couper données mobiles du Fold 5 (garder hotspot actif)
8. Vérifier que le lien LAN reste actif

## B — Perte LAN → repli cloud

1. Depuis le scénario A, couper le hotspot ou éloigner les appareils du LAN
2. Attendre la bascule (5–10 s)
3. Vérifier chemin **Internet direct** ou **Relais cloud**
4. Confirmer audio bidirectionnel via le repli
5. Revenir sur le même LAN → reprise **Local** automatique

## C — Deux réseaux distincts (cloud only)

1. Chaque téléphone sur un réseau différent (ex. 4G + Wi-Fi domicile)
2. Connexion Firebase + présence cloud
3. Sélection mutuelle des équipiers
4. Vérifier rendez-vous P2P puis relais si le hole punch échoue

## Critères de succès

- Aucune perte d'audio permanente (backlog acceptable temporairement)
- Bascule local ↔ cloud sans action utilisateur
- RTT et label de chemin cohérents avec le transport réel
