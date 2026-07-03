# Terraform — VoxCrew infrastructure

Configuration progressive pour GCP. Voir [../docs/gcp-setup.md](../docs/gcp-setup.md) pour la configuration manuelle Firebase.

## Fichiers

- `main.tf` — APIs, Artifact Registry, Cloud Run
- `variables.tf` — entrées
- `outputs.tf` — URL service, registry
- `terraform.tfvars.example` — modèle (copier vers `terraform.tfvars`, hors Git)

## État Terraform

MVP : état local (`.gitignore`). Migration future vers bucket GCS avec versioning documentée dans les commentaires de `main.tf`.

## Commandes

```bash
cd infrastructure/terraform
cp terraform.tfvars.example terraform.tfvars
terraform fmt
terraform init
terraform validate
terraform plan
# terraform apply  — uniquement après validation explicite
```

## Ne jamais committer

- `terraform.tfstate`, `terraform.tfstate.*`
- `.terraform/`
- `terraform.tfvars`
