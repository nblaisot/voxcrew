# Signaling local (LAN)

## Hôte

Le téléphone hôte exécute `LocalSignalingServer` (Ktor CIO) :

- WebSocket `/ws`
- Bind interface LAN (Wi‑Fi)
- Port dynamique
- Protocole v1 identique au cloud
- Aucun audio traité
- Arrêt à la fin de session

## Authentification locale

Extension `authenticate` :

```json
{
  "payload": {
    "authKind": "local",
    "sessionId": "<id>",
    "localToken": "<secret>",
    "participantId": "<firebase-uid>"
  }
}
```

Le secret est généré par `SecureRandom`, TTL court, jamais loggé.

## QR code

```text
voxcrew://join-local?host=192.168.x.x&port=38472&sessionId=...&token=...
```

Fallback : saisie manuelle host/port/token.

## NSD

Service : `_voxcrew._tcp`

TXT minimal : `protocolVersion`, `sessionIdHash`, `hostRole`, `instanceId`

Ne pas publier : token, email, UID complet.

## Sécurité

- Secret session temporaire
- Validation avant tout signaling WebRTC
- Cleartext WS limité aux IPs privées via `networkSecurityConfig`

## Hotspot

L'utilisateur active manuellement le hotspot. L'app affiche l'adresse LAN et le QR ; ne confond pas « hotspot actif » et « chemin validé ».
