#!/usr/bin/env bash
# VoxCrew GCP setup helper — prints checklist (no cloud resources created).
set -euo pipefail

cat <<'EOF'
VoxCrew GCP / Firebase checklist
================================

Before starting, confirm:
  [ ] Correct Google account in browser
  [ ] Billing account available
  [ ] Budget + email alerts configured (50/90/100%)

Steps (see docs/gcp-setup.md):
  1. Create GCP project (note project_id)
  2. Enable APIs: run, artifactregistry, firestore, secretmanager
  3. Link Firebase to GCP project
  4. Add Android app com.nblaisot.voxcrew
  5. Download google-services.json → android/app/ (NOT in Git)
  6. Enable Email/Password auth, create 2 test users, note UIDs
  7. Create Firestore Native in eur3 (irreversible location)
  8. Deploy firestore rules: infrastructure/firestore.rules
  9. Copy terraform.tfvars.example → terraform.tfvars (local only)
 10. Set ALLOWED_FIREBASE_UIDS in backend .env and terraform.tfvars

Non-sensitive values to record locally:
  - GCP project_id
  - Cloud Run region (europe-west1)
  - Firebase user UIDs

Do NOT paste secrets, passwords, or service account JSON in chat.
EOF
