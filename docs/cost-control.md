# Contrôle des coûts GCP

**Les estimations ci-dessous ne sont pas des garanties.** Surveiller la facturation réelle.

## Principes

- Cloud Run : `min_instances = 0`
- `max_instances` faible (ex. 2–5 pour MVP)
- Firestore : usage minimal, pas de collections volumineuses
- Artifact Registry : nettoyer anciennes images
- Logs : rétention par défaut, éviter logs verbeux en prod

## Postes de coût MVP

| Ressource | Coût typique MVP |
|-----------|------------------|
| Cloud Run (idle) | ~0 € si min=0 et peu de trafic |
| Cloud Run (actif) | CPU/mémoire × durée requêtes |
| Firestore | Lectures/écritures faibles |
| Artifact Registry | Stockage images Docker |
| Firebase Auth | Gratuit à faible volume |
| Secret Manager | Quelques secrets = centimes |

## Futur

| Ressource | Note |
|-----------|------|
| TURN | Bande passante significative si relay |
| SFU | VM/container dédié + bande passante |

## Budget et alertes

Configurer dans la console Billing :

- Budget mensuel bas (10–20 €)
- Alertes 50 %, 90 %, 100 %

Le budget **n'arrête pas** la facturation automatiquement.

## Réduction des coûts

```bash
# Supprimer le service Cloud Run
gcloud run services delete voxcrew-signaling --region=europe-west1

# Voir images Artifact Registry
gcloud artifacts docker images list europe-west1-docker.pkg.dev/PROJECT/REPO

# Détruire l'infra Terraform (après backup si besoin)
cd infrastructure/terraform && terraform destroy
```

## Arrêt d'urgence

1. Désactiver ou supprimer le service Cloud Run
2. Révoquer clés de compte de service inutiles
3. Vérifier Billing → Reports
4. `terraform destroy` si infra gérée par Terraform

## Monitoring

- Console → Billing → Reports
- Cloud Run → Metrics (requêtes, instances)
- Configurer alertes sur erreurs 5xx si trafic réel
