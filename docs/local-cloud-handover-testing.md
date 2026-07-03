# Tests manuels bascule local/cloud

Appareils cibles : Samsung Galaxy Z Fold 5 (hôte hotspot) + Galaxy S25.

## Préparation

- [ ] Même build debug/release sur les deux téléphones
- [ ] Comptes Firebase dans l'allowlist
- [ ] `google-services.json` installé dans `android/app/`
- [ ] Permissions : micro, notifications, caméra (QR), Wi‑Fi
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
adb logcat -s VoxCrew ConnectivityOrchestrator SignalingClient WebRtcConnectionSwitcher \
  | grep -v -E 'token|localToken|secret|Bearer'
```

## A — Hotspot local avec Internet

1. Activer hotspot sur Fold 5
2. Connecter S25 au hotspot
3. Créer session locale sur Fold 5
4. Vérifier NSD ou scanner QR sur S25
5. Établir WebRTC ; badge **Local**
6. Tester micro ouvert et PTT
7. Couper données mobiles du Fold 5
8. Vérifier audio continue ; badge **Local — hors ligne**

## B — Local vers cloud

1. Réactiver Internet sur les deux
2. Session en mode local
3. Éloigner S25 ou couper son Wi‑Fi
4. Observer transition **Transition** puis **Internet direct**
5. Vérifier absence de double audio
6. Exporter diagnostics expurgés

## C — Cloud vers local

1. Démarrer session cloud (réseau mobile)
2. Activer hotspot ; reconnecter S25
3. Attendre validation locale (4+ s)
4. Badge **Local** sans recréer session

## D — Bord de portée

- Limite hotspot ; vérifier pas de bascule permanente
- Noter RTT, pertes, raison dernière bascule

## E — Callback obsolète

- Basculer rapidement ; vérifier dans logs `obsolete_generation_event_ignored`

## F — Écran verrouillé

- Avec foreground service actif : verrouiller, tester PTT si possible

## Collecte en cas d'échec

- Modèle, version Android, horodatage
- Badge transport, génération active/candidate
- Export diagnostics (sans tokens)
- Type candidat ICE

## Limites CI

NSD et Wi‑Fi réel non testés en CI unitaire — cette checklist est obligatoire avant validation MVP.
