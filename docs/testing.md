# Tests VoxCrew

## Backend (automatisés)

```bash
cd backend && npm test
```

Couverture cible :

- `GET /health`, `GET /ready`
- Validation configuration
- WebSocket : auth acceptée/refusée, allowlist
- Création/rejoindre session, offer/answer/ICE
- Routage interdit hors session
- ping/pong, déconnexion, nettoyage
- Erreurs de protocole

## Android (automatisés)

```bash
cd android && ./gradlew testDebugUnitTest
```

Couverture cible :

- Sérialisation messages signaling
- Machines d'état session / reconnexion
- `TransmissionPolicy` (PTT press/release)
- Parsing erreurs

WebRTC, micro, Bluetooth : interfaces mockables, tests manuels sur appareil.

## Checklist manuelle — deux Samsung Galaxy

### Prérequis

- [ ] APK debug installé sur les deux téléphones
- [ ] `google-services.json` présent sur la build
- [ ] Deux comptes Firebase dans l'allowlist
- [ ] Backend déployé ou accessible en local (même réseau / URL publique)
- [ ] Permissions micro accordées

### Auth et signaling

- [ ] Connexion compte A et B
- [ ] WebSocket `authenticated` sur les deux
- [ ] A crée une session ; B rejoint avec l'ID
- [ ] `participant_joined` visible des deux côtés

### WebRTC data channel (Phase 8)

- [ ] PeerConnection `CONNECTED`
- [ ] Ping/pong data channel avec RTT affiché
- [ ] Type candidat ICE visible (host/srflx/relay)

### Audio micro ouvert (Phase 9)

- [ ] Audio bidirectionnel audible
- [ ] Bascule haut-parleur / écouteur
- [ ] Bluetooth si casque appairé
- [ ] Stats diagnostics cohérentes

### Push-to-talk (Phase 10)

- [ ] Par défaut pas de transmission (hors open mic)
- [ ] Appui PTT → transmission immédiate
- [ ] Relâchement → coupure
- [ ] Glissement hors bouton = release
- [ ] Indicateur « transmission active »

### Reconnexion

- [ ] Couper Wi-Fi brièvement → reconnexion signaling
- [ ] Logs exportables sans secrets

### Foreground service (Phase 11)

- [ ] Session active, verrouiller écran
- [ ] Notification visible avec action Quitter
- [ ] Audio/PTT selon capacités écran verrouillé
- [ ] Retour app → état restauré
- [ ] Quitter proprement

### Échec — informations à collecter

- Horodatage, modèle téléphone, version Android
- État UI (connexion, ICE, mode transmission)
- Export diagnostics expurgé (pas de tokens)

## CI

Voir `.github/workflows/` — builds PR sans déploiement automatique.
