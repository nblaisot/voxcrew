# Sécurité VoxCrew

## Authentification

1. Client Android obtient un Firebase ID token (email/password).
2. WebSocket : premier message `authenticate` avec le token.
3. Serveur vérifie signature et validité via Firebase Admin SDK.
4. Serveur extrait l'UID et vérifie l'allowlist (`ALLOWED_FIREBASE_UIDS`).
5. Connexion refusée sinon ; fermeture après timeout 10 s sans auth.

Ne jamais faire confiance au seul contrôle côté Android.

## Autorisation

- Allowlist serveur (variable d'environnement, virgules).
- Messages WebRTC routés uniquement entre participants de la même session.
- `recipientId` obligatoire pour offer/answer/ICE.
- Client Android : règles Firestore restrictives (pas d'écriture arbitraire).

## TLS

- Cloud Run : HTTPS/WSS obligatoire en production.
- Certificats gérés par Google.

## WebRTC

- Media chiffré SRTP/DTLS entre pairs.
- Chaque terminal participant a accès au flux déchiffré localement.
- Candidats ICE peuvent révéler des adresses IP — ne pas logger en production.

## Secrets

| Secret | Stockage |
|--------|----------|
| Firebase service account | Secret Manager / env Cloud Run (pas Git) |
| `google-services.json` | Local `android/app/`, hors Git |
| Allowlist UIDs | Variable env Cloud Run |
| Tokens utilisateur | Mémoire volatile, jamais loggés |

Pas de clé de compte de service embarquée dans l'APK.

## Logs

**Logger :** niveau, timestamp, requestId, type message, sessionId, UID tronqué (8 car.), codes erreur.

**Ne pas logger :** tokens Firebase, SDP complet, candidats ICE complets en prod, mots de passe, audio, secrets.

## Menaces principales

| Menace | Mitigation MVP |
|--------|----------------|
| Usurpation signaling | Auth Firebase + allowlist |
| Écoute signaling | TLS/WSS |
| Injection messages | Validation Zod, limites taille |
| Flood WebSocket | Rate limiting basique |
| Accès Firestore client | Règles deny-by-default |
| MITM audio | DTLS-SRTP WebRTC |

## Limites du MVP

- STUN public insuffisant pour tous les réseaux (TURN requis plus tard).
- Pas d'audit trail complet des sessions.
- Métadonnées de présence visibles par le backend.
- Deux comptes de test seulement — pas de gestion utilisateurs avancée.
- Pas de certificate pinning Android (évaluer pour prod).

## Dépendances

Vérifier régulièrement `npm audit` et les advisories Gradle.
