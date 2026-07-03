output "cloud_run_url" {
  description = "Public URL of the signaling service"
  value       = google_cloud_run_v2_service.signaling.uri
}

output "artifact_registry_repository" {
  description = "Docker repository for signaling images"
  value       = google_artifact_registry_repository.voxcrew.name
}

output "service_account_email" {
  description = "Cloud Run runtime service account"
  value       = google_service_account.signaling.email
}

output "region" {
  value = var.region
}
