# Protocole de signaling VoxCrew — version 1

Transport : WebSocket JSON text frames sur `wss://<host>/ws`.

## Enveloppe commune

Tous les messages client → serveur et serveur → client partagent cette structure :

```json
{
  "version": 1,
  "type": "<message_type>",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "optional-session-id",
  "senderId": "optional-firebase-uid",
  "recipientId": "optional-firebase-uid",
  "payload": {}
}
```

| Champ | Obligatoire | Description |
|-------|-------------|-------------|
| `version` | oui | Toujours `1` pour cette spec |
| `type` | oui | Type de message |
| `requestId` | client→serveur : oui | UUID pour corrélation |
| `sessionId` | selon type | ID de session |
| `senderId` | serveur→client | UID Firebase de l'émetteur |
| `recipientId` | pair-à-pair | UID du destinataire (`p2p_*`, relais binaire) |
| `payload` | oui | Corps typé selon `type` |

## Authentification

### `authenticate` (client → serveur)

Premier message après ouverture WebSocket. Timeout serveur : 10 s.

```json
{
  "version": 1,
  "type": "authenticate",
  "requestId": "...",
  "payload": {
    "token": "<firebase-id-token>"
  }
}
```

### `authenticated` (serveur → client)

```json
{
  "version": 1,
  "type": "authenticated",
  "requestId": "...",
  "senderId": "<uid>",
  "payload": {
    "uid": "<uid>",
    "displayName": "optional"
  }
}
```

### `authentication_error` (serveur → client)

```json
{
  "version": 1,
  "type": "authentication_error",
  "requestId": "...",
  "payload": {
    "code": "TOKEN_INVALID | TOKEN_EXPIRED | NOT_ALLOWED | TIMEOUT",
    "message": "Human readable"
  }
}
```

## Sessions

### `create_session` (client → serveur)

```json
{
  "version": 1,
  "type": "create_session",
  "requestId": "...",
  "payload": {
    "name": "optional-display-name"
  }
}
```

### `session_created` (serveur → client)

```json
{
  "version": 1,
  "type": "session_created",
  "requestId": "...",
  "sessionId": "<id>",
  "payload": {
    "sessionId": "<id>",
    "participants": ["<uid>"]
  }
}
```

### `join_session` (client → serveur)

```json
{
  "version": 1,
  "type": "join_session",
  "requestId": "...",
  "payload": {
    "sessionId": "<id>"
  }
}
```

### `session_joined` (serveur → client joignant)

```json
{
  "version": 1,
  "type": "session_joined",
  "requestId": "...",
  "sessionId": "<id>",
  "payload": {
    "sessionId": "<id>",
    "participants": ["<uid-a>", "<uid-b>"]
  }
}
```

### `participant_joined` (serveur → autres participants)

```json
{
  "version": 1,
  "type": "participant_joined",
  "sessionId": "<id>",
  "senderId": "<new-participant-uid>",
  "payload": {
    "participantId": "<new-participant-uid>"
  }
}
```

### `participant_left` (serveur → session)

```json
{
  "version": 1,
  "type": "participant_left",
  "sessionId": "<id>",
  "senderId": "<uid>",
  "payload": {
    "participantId": "<uid>",
    "reason": "leave | disconnect | timeout"
  }
}
```

### `leave_session` (client → serveur)

```json
{
  "version": 1,
  "type": "leave_session",
  "requestId": "...",
  "sessionId": "<id>",
  "payload": {}
}
```

## Rendez-vous P2P (repli cloud)

Messages uid-à-uid, **sans session active requise**. Utilisés par `LanIntercomEngine` pour le hole punching UDP.

### `p2p_connect_request` (client → serveur → client)

Demande à un équipier de tenter une connexion directe.

```json
{
  "version": 1,
  "type": "p2p_connect_request",
  "requestId": "...",
  "recipientId": "<peer-uid>",
  "payload": {}
}
```

### `p2p_endpoints` (client → serveur → client)

Échange d'adresses après découverte STUN.

```json
{
  "version": 1,
  "type": "p2p_endpoints",
  "requestId": "...",
  "recipientId": "<peer-uid>",
  "payload": {
    "publicHost": "203.0.113.5",
    "publicPort": 40000,
    "localHost": "192.168.1.10",
    "localPort": 50000
  }
}
```

| Champ | Obligatoire | Description |
|-------|-------------|-------------|
| `publicHost` | oui | Adresse publique (STUN) |
| `publicPort` | oui | Port UDP public |
| `localHost` | non | Adresse LAN |
| `localPort` | non | Port UDP LAN |

## Relais binaire (dernier recours cloud)

Trames **binaires** WebSocket (pas JSON). Format wire :

```text
[recipientUidLen:1][recipientUid UTF-8][opaque payload]
```

- Le serveur forward uid-à-uid sans parser le payload audio.
- Limite serveur : 4 KiB par trame, 64 KiB/s par client (backstop).
- Budget rate-limit JSON séparé du relais binaire.

## Présence équipier

Messages rétrocompatibles — ignorés par les clients/serveurs qui ne les implémentent pas.

### `presence_register` (client → serveur)

Après authentification, enregistre l’email et le transport préféré.

```json
{
  "version": 1,
  "type": "presence_register",
  "requestId": "...",
  "payload": {
    "email": "user@example.com",
    "transportHint": "cloud | local_lan | none"
  }
}
```

### `presence_heartbeat` (client → serveur)

Toutes les ~10 s. TTL serveur : 30 s sans heartbeat → hors ligne.

```json
{
  "version": 1,
  "type": "presence_heartbeat",
  "requestId": "...",
  "payload": {
    "transportHint": "cloud | local_lan | none"
  }
}
```

### `presence_snapshot` (serveur → client)

Liste complète des membres connus.

```json
{
  "version": 1,
  "type": "presence_snapshot",
  "payload": {
    "members": [
      {
        "uid": "firebase-uid",
        "email": "user@example.com",
        "transportHint": "cloud",
        "online": true,
        "lastSeenMs": 1710000000000
      }
    ]
  }
}
```

### `presence_updated` / `presence_offline` (serveur → client)

Deltas lors d’une connexion, heartbeat ou déconnexion.

## Keepalive

### `ping` (client ou serveur)

```json
{
  "version": 1,
  "type": "ping",
  "requestId": "...",
  "payload": {
    "timestamp": 1710000000000
  }
}
```

### `pong`

```json
{
  "version": 1,
  "type": "pong",
  "requestId": "...",
  "payload": {
    "timestamp": 1710000000000
  }
}
```

## Erreurs

### `error` (serveur → client)

```json
{
  "version": 1,
  "type": "error",
  "requestId": "...",
  "payload": {
    "code": "INVALID_MESSAGE | NOT_IN_SESSION | SESSION_NOT_FOUND | UNAUTHORIZED | RATE_LIMITED | INTERNAL",
    "message": "Description",
    "details": {}
  }
}
```

## Codes d'erreur

| Code | Signification |
|------|---------------|
| `TOKEN_INVALID` | Jeton Firebase invalide |
| `TOKEN_EXPIRED` | Jeton expiré |
| `NOT_ALLOWED` | UID absent de l'allowlist |
| `TIMEOUT` | Auth non reçue à temps |
| `INVALID_MESSAGE` | Schéma ou version incorrecte |
| `NOT_IN_SESSION` | Action hors session active |
| `SESSION_NOT_FOUND` | Session inexistante |
| `UNAUTHORIZED` | Non authentifié |
| `RATE_LIMITED` | Trop de messages |
| `INTERNAL` | Erreur serveur |

## Limites MVP

- Taille max message : 64 KiB
- Le serveur valide tous les champs avec Zod
- Pas de diffusion aveugle : vérification session + destinataire

## Reconnexion

1. Nouvelle connexion WebSocket
2. `authenticate` avec jeton rafraîchi
3. `join_session` avec `sessionId` précédent si applicable (roster / sessions legacy)
4. `LanIntercomEngine` reprend le rendez-vous P2P et le relais si nécessaire
