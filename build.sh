#!/bin/bash
#
# Local Build Script for GPXLogger
#
# This script replicates the GitHub Actions CI workflow for local development.
# It builds the Android app inside a Docker container using the project's Dockerfile.
#
# Requirements:
#   - Docker installed and running
#   - Up-to-date Linux distribution
#
# Usage:
#   ./build.sh [gradle_task]
#
# Examples:
#   ./build.sh                  # Build debug APK (default)
#   ./build.sh assembleRelease  # Build release APK
#   ./build.sh clean            # Clean build artifacts
#

set -e

# Configuration
DOCKER_IMAGE_NAME="android-build"
DEFAULT_GRADLE_TASK="assembleDebug"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Change to script directory (project root)
cd "$SCRIPT_DIR"

# Parse command line arguments
GRADLE_TASK="${1:-$DEFAULT_GRADLE_TASK}"

log_info "GPXLogger Local Build Script"
log_info "============================="

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    log_error "Docker is not installed or not in PATH."
    log_error "Please install Docker and ensure it is running."
    log_error "Visit: https://docs.docker.com/get-docker/"
    exit 1
fi

# Check if Docker daemon is running and accessible
if ! DOCKER_INFO_OUTPUT=$(docker info 2>&1); then
    if echo "$DOCKER_INFO_OUTPUT" | grep -qi "permission denied"; then
        log_error "Docker is installed but you do not have permission to access the Docker daemon."
        log_error "Consider running this script with 'sudo' or adding your user to the 'docker' group:"
        log_error "  sudo usermod -aG docker \"\$(whoami)\" && newgrp docker"
    else
        log_error "Failed to communicate with the Docker daemon."
    fi
    log_error "Underlying 'docker info' error:"
    echo "$DOCKER_INFO_OUTPUT" >&2
    exit 1
fi

log_info "Docker is available and running."

# Build Docker image
log_info "Building Docker image '${DOCKER_IMAGE_NAME}'..."
log_info "This may take a few minutes on first run."

if ! docker build -t "$DOCKER_IMAGE_NAME" .; then
    log_error "Failed to build Docker image."
    exit 1
fi

log_info "Docker image built successfully."

# Run Android build in Docker container
log_info "Running Gradle task: ${GRADLE_TASK}"

if ! docker run --rm \
    -v "$SCRIPT_DIR":/workspace \
    -w /workspace \
    --user "$(id -u):$(id -g)" \
    -e GRADLE_USER_HOME=/workspace/.gradle \
    "$DOCKER_IMAGE_NAME" \
    ./gradlew "$GRADLE_TASK" --no-daemon --stacktrace; then
    log_error "Build failed."
    exit 1
fi

log_info "Build completed successfully!"

# Report output location for APK builds
if [[ "$GRADLE_TASK" == *"assemble"* ]]; then
    APK_DIR="$SCRIPT_DIR/app/build/outputs/apk"
    if [ -d "$APK_DIR" ]; then
        log_info "APK output directory: $APK_DIR"
        log_info "Generated APK files:"
        find "$APK_DIR" -name "*.apk" -type f 2>/dev/null | while read -r apk; do
            echo "  - $apk"
        done
    fi
fi

log_info "Done!"
