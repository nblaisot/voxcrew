#!/usr/bin/env bash
# Build and deploy VoxCrew signaling to Cloud Run (requires gcloud auth + terraform applied).
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-}"
REGION="${REGION:-europe-west1}"
SERVICE="${SERVICE:-voxcrew-signaling}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

if [[ -z "$PROJECT_ID" ]]; then
  echo "Set PROJECT_ID environment variable" >&2
  exit 1
fi

IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/voxcrew/${SERVICE}:${IMAGE_TAG}"

echo "Building Docker image: ${IMAGE}"
docker build -t "${IMAGE}" ../../backend

echo "Configuring docker for Artifact Registry..."
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

echo "Pushing image..."
docker push "${IMAGE}"

echo "Deploying to Cloud Run..."
gcloud run deploy "${SERVICE}" \
  --image "${IMAGE}" \
  --region "${REGION}" \
  --project "${PROJECT_ID}" \
  --platform managed \
  --allow-unauthenticated \
  --min-instances 1 \
  --max-instances 1 \
  --timeout 3600 \
  --port 8080

URL=$(gcloud run services describe "${SERVICE}" --region "${REGION}" --project "${PROJECT_ID}" --format='value(status.url)')
echo "Deployed: ${URL}"
echo "Test: curl ${URL}/health"
