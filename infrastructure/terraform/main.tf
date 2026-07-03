terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # MVP: local state. Migrate to GCS backend with versioning for team use:
  # backend "gcs" {
  #   bucket = "voxcrew-terraform-state"
  #   prefix = "signaling"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_project_service" "required" {
  for_each = toset([
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "firestore.googleapis.com",
    "secretmanager.googleapis.com",
    "iam.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "voxcrew" {
  depends_on = [google_project_service.required]

  location      = var.region
  repository_id = "voxcrew"
  description   = "VoxCrew container images"
  format        = "DOCKER"
}

resource "google_service_account" "signaling" {
  depends_on = [google_project_service.required]

  account_id   = "voxcrew-signaling"
  display_name = "VoxCrew signaling Cloud Run"
}

resource "google_project_iam_member" "signaling_firestore" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.signaling.email}"
}

resource "google_project_iam_member" "signaling_firebase_admin" {
  project = var.project_id
  role    = "roles/firebaseauth.admin"
  member  = "serviceAccount:${google_service_account.signaling.email}"
}

resource "google_cloud_run_v2_service" "signaling" {
  depends_on = [google_project_service.required]

  name     = var.service_name
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.signaling.email

    scaling {
      min_instance_count = 0
      max_instance_count = var.max_instances
    }

    timeout = "${var.request_timeout_seconds}s"

    containers {
      image = var.container_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }

      env {
        name  = "NODE_ENV"
        value = "production"
      }

      env {
        name  = "GCP_PROJECT_ID"
        value = var.project_id
      }

      env {
        name  = "LOG_LEVEL"
        value = "info"
      }

      env {
        name  = "ALLOWED_FIREBASE_UIDS"
        value = var.allowed_firebase_uids
      }
    }
  }

  lifecycle {
    ignore_changes = [
      template[0].containers[0].image,
    ]
  }
}

resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  count = var.allow_unauthenticated ? 1 : 0

  name     = google_cloud_run_v2_service.signaling.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}
