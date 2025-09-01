#!/bin/bash

# Build Script - Test, Build, and Deliver Application
# Usage: ./build.sh [local|aws] [options]
# Example: ./build.sh local
# Example: ./build.sh aws --aws-profile prod

set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default options
ENVIRONMENT="local"
AWS_PROFILE=""
SKIP_TESTS=false
SKIP_BUILD=false
SKIP_DELIVERY=false
CONTAINER_ENGINE=""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Usage function
usage() {
    cat << EOF
Usage: $0 [environment] [options]

Arguments:
  environment           Target environment (local, aws) - default: local

Options:
  --aws-profile PROFILE AWS profile to use for AWS operations
  --skip-tests         Skip running tests
  --skip-build         Skip building the application
  --skip-delivery      Skip delivering image to ECR (AWS only)
  -h, --help           Show this help message

Examples:
  $0 local                              # Test and build for local development
  $0 aws --aws-profile prod             # Test, build, and deliver to ECR for AWS
  $0 aws --skip-tests                   # Build and deliver without running tests

EOF
}

# Parse command line arguments
parse_arguments() {
    # First argument is environment if it doesn't start with -
    if [[ $# -gt 0 && ! "$1" =~ ^- ]]; then
        ENVIRONMENT="$1"
        shift
    fi
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --aws-profile)
                if [[ -n "${2:-}" ]]; then
                    AWS_PROFILE="$2"
                    shift 2
                else
                    log_error "--aws-profile requires a profile name"
                    exit 1
                fi
                ;;
            --skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --skip-build)
                SKIP_BUILD=true
                shift
                ;;
            --skip-delivery)
                SKIP_DELIVERY=true
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                usage
                exit 1
                ;;
        esac
    done
    
    # Validate environment
    if [[ ! "$ENVIRONMENT" =~ ^(local|aws)$ ]]; then
        log_error "Invalid environment: $ENVIRONMENT. Must be 'local' or 'aws'"
        exit 1
    fi
}

# Detect container engine (Docker or Podman)
detect_container_engine() {
    if command -v docker &> /dev/null; then
        CONTAINER_ENGINE="docker"
        log_info "Using Docker as container engine"
    elif command -v podman &> /dev/null; then
        CONTAINER_ENGINE="podman"
        log_info "Using Podman as container engine"
    else
        log_error "Neither Docker nor Podman is installed. Please install one of them first."
        log_error "Install Docker: https://docs.docker.com/get-docker/"
        log_error "Install Podman: https://podman.io/getting-started/installation"
        exit 1
    fi
}

# Load environment configuration
load_environment_config() {
    local env="$1"
    local env_file="${PROJECT_ROOT}/.env.${env}"
    
    log_info "Loading environment configuration for: $env"
    
    if [[ ! -f "$env_file" ]]; then
        log_error "Environment file not found: $env_file"
        exit 1
    fi
    
    # Source the environment file
    set -a  # automatically export all variables
    source "$env_file"
    set +a
    
    log_success "Environment configuration loaded from: $env_file"
}

# Run tests
run_tests() {
    if [[ "$SKIP_TESTS" == "true" ]]; then
        log_info "Skipping tests as requested"
        return 0
    fi
    
    log_info "Running application tests..."
    
    cd "$PROJECT_ROOT"
    
    # Detect build tool based on project files
    if [[ -f "pom.xml" ]]; then
        log_info "Using Maven (pom.xml detected)"
        if [[ -f "mvnw" ]]; then
            ./mvnw test
        elif command -v mvn &> /dev/null; then
            mvn test
        else
            log_error "Maven project detected but mvn command not found"
            exit 1
        fi
    elif [[ -f "build.gradle" || -f "build.gradle.kts" ]]; then
        if [[ -f "gradlew" ]]; then
            ./gradlew test
        elif command -v gradle &> /dev/null; then
            gradle test
        else
            log_error "Gradle project detected but gradle command not found"
            exit 1
        fi
    else
        log_error "No build tool found (gradle/maven). Expected pom.xml or build.gradle"
        exit 1
    fi
    
    log_success "Tests completed successfully"
}

# Build application
build_application() {
    if [[ "$SKIP_BUILD" == "true" ]]; then
        log_info "Skipping build as requested"
        return 0
    fi
    
    log_info "Building application..."
    
    cd "$PROJECT_ROOT"
    
    # Detect build tool based on project files
    if [[ -f "pom.xml" ]]; then
        log_info "Using Maven (pom.xml detected)"
        if [[ -f "mvnw" ]]; then
            ./mvnw package -DskipTests
        elif command -v mvn &> /dev/null; then
            mvn package -DskipTests
        else
            log_error "Maven project detected but mvn command not found"
            exit 1
        fi
    elif [[ -f "build.gradle" || -f "build.gradle.kts" ]]; then
        if [[ -f "gradlew" ]]; then
            ./gradlew build -x test
        elif command -v gradle &> /dev/null; then
            gradle build -x test
        else
            log_error "Gradle project detected but gradle command not found"
            exit 1
        fi
    else
        log_error "No build tool found (gradle/maven). Expected pom.xml or build.gradle"
        exit 1
    fi
    
    log_success "Application built successfully"
}

# Build Docker image
build_docker_image() {
    log_info "Building container image..."
    
    cd "$PROJECT_ROOT"
    
    local image_tag
    if [[ "$ENVIRONMENT" == "local" ]]; then
        image_tag="dynamodb-load-test:latest"
    else
        # For AWS, we'll tag with ECR repository URI
        image_tag="dynamodb-load-test:latest"
    fi
    
    log_info "Using $CONTAINER_ENGINE to build image: $image_tag"
    $CONTAINER_ENGINE build -t "$image_tag" .
    
    log_success "Container image built: $image_tag"
}

# Deliver to ECR (AWS only)
deliver_to_ecr() {
    if [[ "$ENVIRONMENT" != "aws" ]]; then
        log_info "Skipping ECR delivery for local environment"
        return 0
    fi
    
    if [[ "$SKIP_DELIVERY" == "true" ]]; then
        log_info "Skipping ECR delivery as requested"
        return 0
    fi
    
    log_info "Delivering image to ECR..."
    
    # Get ECR repository URI
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    local ecr_uri
    ecr_uri=$($aws_cmd ecr describe-repositories --repository-names "$ECR_REPOSITORY_NAME" --query 'repositories[0].repositoryUri' --output text 2>/dev/null || echo "")
    
    if [[ -z "$ecr_uri" ]]; then
        log_error "ECR repository not found: $ECR_REPOSITORY_NAME"
        log_error "Make sure the infrastructure is deployed first"
        exit 1
    fi
    
    # Login to ECR
    log_info "Logging in to ECR..."
    $aws_cmd ecr get-login-password --region "$AWS_REGION" | $CONTAINER_ENGINE login --username AWS --password-stdin "$ecr_uri"
    
    # Tag and push image
    local local_tag="dynamodb-load-test:latest"
    local remote_tag="${ecr_uri}:latest"
    
    log_info "Tagging image: $local_tag -> $remote_tag"
    $CONTAINER_ENGINE tag "$local_tag" "$remote_tag"
    
    log_info "Pushing image to ECR..."
    $CONTAINER_ENGINE push "$remote_tag"
    
    log_success "Image delivered to ECR: $remote_tag"
}

# Main execution function
main() {
    log_info "Starting build process"
    
    # Parse command line arguments
    parse_arguments "$@"
    
    log_info "Environment: $ENVIRONMENT"
    if [[ -n "$AWS_PROFILE" ]]; then
        log_info "AWS Profile: $AWS_PROFILE"
    fi
    
    # Load environment configuration
    load_environment_config "$ENVIRONMENT"
    
    # Detect container engine
    detect_container_engine
    
    # Execute build pipeline
    run_tests
    build_application
    build_docker_image
    deliver_to_ecr
    
    log_success "Build process completed successfully"
}

# Handle script interruption
trap 'log_error "Script interrupted"; exit 1' INT TERM

# Execute main function with all arguments
main "$@"