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
| `recipientId` | pair-à-pair | UID du destinataire (offer, answer, ICE) |
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

**Extension local-first (rétrocompatible)** — cloud ignore `authKind` absent :

```json
{
  "payload": {
    "authKind": "firebase",
    "token": "<firebase-id-token>"
  }
}
```

```json
{
  "payload": {
    "authKind": "local",
    "sessionId": "<id>",
    "localToken": "<secret-court>",
    "participantId": "<firebase-uid>"
  }
}
```

Messages WebRTC (`offer`, `answer`, `ice_candidate`) peuvent inclure un champ optionnel `generation` (long) dans `payload` pour corrélation orchestrateur.

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

## WebRTC signaling

Routage strict : le serveur ne transmet qu'aux participants de la même session et au `recipientId` indiqué.

### `offer`

```json
{
  "version": 1,
  "type": "offer",
  "requestId": "...",
  "sessionId": "<id>",
  "recipientId": "<peer-uid>",
  "payload": {
    "sdp": "<sdp-string>",
    "sdpType": "offer"
  }
}
```

### `answer`

```json
{
  "version": 1,
  "type": "answer",
  "requestId": "...",
  "sessionId": "<id>",
  "recipientId": "<peer-uid>",
  "payload": {
    "sdp": "<sdp-string>",
    "sdpType": "answer"
  }
}
```

### `ice_candidate`

```json
{
  "version": 1,
  "type": "ice_candidate",
  "requestId": "...",
  "sessionId": "<id>",
  "recipientId": "<peer-uid>",
  "payload": {
    "candidate": "<candidate-string>",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  }
}
```

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
3. `join_session` avec `sessionId` précédent si applicable
4. Renégociation WebRTC si nécessaire
