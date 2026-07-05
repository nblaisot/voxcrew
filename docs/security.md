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
- Messages `p2p_*` et relais binaire routés uniquement au `recipientId` indiqué.
- Client Android : règles Firestore restrictives (pas d'écriture arbitraire).

## TLS

- Cloud Run : HTTPS/WSS obligatoire en production.
- Certificats gérés par Google.

## Audio

- LAN : TCP/UDP sur réseau local (pas de chiffrement applicatif MVP).
- Cloud direct : UDP hole-punched entre pairs (Opus).
- Relais cloud : trames binaires opaques forwardées par le serveur (dernier recours).
- Chaque terminal participant décode l'audio localement.
- Ne pas logger le contenu audio ni les payloads binaires.

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

**Ne pas logger :** tokens Firebase, mots de passe, audio, payloads binaires relay.

## Menaces principales

| Menace | Mitigation MVP |
|--------|----------------|
| Usurpation signaling | Auth Firebase + allowlist |
| Écoute signaling | TLS/WSS |
| Injection messages | Validation Zod, limites taille |
| Flood WebSocket | Rate limiting basique (JSON + relais binaire séparés) |
| Accès Firestore client | Règles deny-by-default |
| MITM audio LAN | Risque accepté MVP ; chiffrement transport à évaluer |
| Abus relais cloud | Rate limit binaire, trames opaques non stockées |

## Limites du MVP

- Hole punching UDP insuffisant sur certains NAT (relais WebSocket en fallback).
- Pas d'audit trail complet des sessions.
- Métadonnées de présence visibles par le backend.
- Deux comptes de test seulement — pas de gestion utilisateurs avancée.
- Pas de certificate pinning Android (évaluer pour prod).

## Dépendances

Vérifier régulièrement `npm audit` et les advisories Gradle.
