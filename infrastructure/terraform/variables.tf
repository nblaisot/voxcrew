variable "project_id" {
  type        = string
  description = "GCP project ID"
}

variable "region" {
  type        = string
  description = "GCP region for Cloud Run and Artifact Registry"
  default     = "europe-west1"
}

variable "service_name" {
  type        = string
  description = "Cloud Run service name"
  default     = "voxcrew-signaling"
}

variable "environment" {
  type        = string
  description = "Deployment environment label"
  default     = "dev"
}

variable "allowed_firebase_uids" {
  type        = string
  description = "Comma-separated Firebase UIDs allowed to connect"
  sensitive   = true
}

variable "container_image" {
  type        = string
  description = "Container image URI"
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "max_instances" {
  type        = number
  description = "Maximum Cloud Run instances"
  default     = 3
}

variable "request_timeout_seconds" {
  type        = number
  description = "Request timeout (WebSocket friendly)"
  default     = 3600
}

variable "allow_unauthenticated" {
  type        = bool
  description = "Allow public HTTPS access (required for Android WSS in MVP)"
  default     = true
}
