# Configuration GCP et Firebase — guide interactif

Ce guide décrit les étapes manuelles. **Aucune ressource cloud ne doit être créée sans votre autorisation explicite.**

## Valeurs proposées

| Paramètre | Valeur suggérée |
|-----------|-----------------|
| Nom projet | VoxCrew |
| Project ID | `voxcrew-private` (ou variante disponible mondialement) |
| Région Cloud Run | `europe-west1` |
| Service | `voxcrew-signaling` |
| Package Android | `com.nblaisot.voxcrew` |
| Firestore | Native mode, emplacement **`eur3`** (irréversible) |

## Étape 1 — Compte et facturation

1. Ouvrir [Google Cloud Console](https://console.cloud.google.com/)
2. Vérifier le compte Google actif (coin supérieur droit)
3. Confirmer qu'un compte de facturation est lié
4. Comprendre que Cloud Run, Firestore, Artifact Registry peuvent engendrer des coûts

## Étape 2 — Budget et alertes

1. Billing → Budgets → Create budget
2. Montant mensuel faible (ex. 10–20 €)
3. Seuils : 50 %, 90 %, 100 % → notification email

**Note :** un budget n'arrête pas automatiquement la facturation.

## Étape 3 — Créer le projet GCP

1. Sélecteur de projet → New Project
2. Nom : `VoxCrew`
3. Project ID : choisir un ID disponible (noter la valeur exacte)
4. Lier le compte de facturation

Noter le `project_id` dans `infrastructure/terraform/terraform.tfvars` (fichier local, hors Git).

## Étape 4 — Activer les APIs

Activer uniquement :

- Cloud Run Admin API
- Artifact Registry API
- Cloud Build API (si build cloud)
- Firestore API
- Secret Manager API (si secrets)
- Firebase Management API (via console Firebase)

```bash
gcloud config set project <PROJECT_ID>
gcloud services enable run.googleapis.com artifactregistry.googleapis.com firestore.googleapis.com secretmanager.googleapis.com
```

## Étape 5 — Firebase

1. [Firebase Console](https://console.firebase.google.com/) → Add project → lier le projet GCP existant
2. Ajouter une app Android :
   - Package : `com.nblaisot.voxcrew`
   - Télécharger `google-services.json`
3. Placer le fichier dans :

   ```text
   android/app/google-services.json
   ```

   **Ce fichier est hors Git** (voir `.gitignore`).

4. Authentication → Sign-in method → Email/Password → Enable
5. Créer 2 comptes de test (Users)
6. Noter les **UID** Firebase (non secrets) pour `ALLOWED_FIREBASE_UIDS`

## Étape 6 — Firestore

**Avant création :** confirmer l'emplacement `eur3` (Europe multi-région). Ce choix est durable.

1. Firestore Database → Create database
2. Mode : Native
3. Location : `eur3`
4. Règles restrictives (exemple) :

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

Le backend accède via Admin SDK ; le client Android n'écrit pas librement.

## Étape 7 — Compte de service backend

Pour Cloud Run, utiliser un compte de service dédié avec permissions minimales (voir Terraform).

Pour le développement local :

```bash
gcloud auth application-default login
```

Ne pas créer de clé JSON sauf nécessité démontrée.

## Étape 8 — GitHub Actions (futur)

Préférer Workload Identity Federation — pas de JSON de compte de service dans GitHub Secrets.

## Vérification locale

Après configuration :

```bash
# Non sensible
gcloud config get-value project
# Tester backend local avec ALLOWED_FIREBASE_UIDS contenant les UID notés
```

## Ce qu'il ne faut pas partager dans le chat

Mots de passe Google, tokens OAuth, clés privées, contenu complet de credentials.
