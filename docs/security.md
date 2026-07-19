# Sécurité VoxCrew

## Identité

- Identité **locale uniquement** : UUID généré sur l’appareil + nom d’affichage (`LocalProfileRepository`).
- Aucun compte cloud, aucun jeton Firebase, aucun serveur d’auth.

## Réseau

- Audio Opus en clair sur le LAN / Tailscale (réseau de confiance entre équipiers).
- Pas d’upload audio vers un serveur VoxCrew.
- Cleartext HTTP désactivé en release (`usesCleartextTraffic=false`).

## Secrets

Ne jamais committer :

| Élément | Emplacement |
|---------|-------------|
| `local.properties` | Local, gitignored |
| Mots de passe / clés API | Hors dépôt |

**Ne pas logger :** audio, secrets.

## Menaces pertinentes

| Risque | Mitigation |
|--------|------------|
| Écoute LAN hostile | Utiliser un réseau de confiance ; Tailscale pour liens hors LAN |
| Usurpation de beacon | Identité locale non authentifiée — risque accepté pour le MVP privé |
| Fuite de données via logs | Pas de log d’audio / payloads |
