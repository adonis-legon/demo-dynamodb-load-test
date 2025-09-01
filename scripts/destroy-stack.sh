#!/bin/bash

# Destroy Stack Script - Destroy Infrastructure Stack
# Usage: ./destroy-stack.sh [local|aws] [options]
# Example: ./destroy-stack.sh local
# Example: ./destroy-stack.sh aws --aws-profile prod

set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default options
ENVIRONMENT="local"
AWS_PROFILE=""
FORCE=false

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
  --force              Skip confirmation prompts
  -h, --help           Show this help message

Examples:
  $0 local                              # Destroy LocalStack infrastructure
  $0 aws --aws-profile prod             # Destroy AWS infrastructure
  $0 aws --force                        # Destroy without confirmation

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
            --force)
                FORCE=true
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

# Confirm destruction
confirm_destruction() {
    if [[ "$FORCE" == "true" ]]; then
        return 0
    fi
    
    log_warning "This will destroy all infrastructure for environment: $ENVIRONMENT"
    
    if [[ "$ENVIRONMENT" == "aws" ]]; then
        log_warning "This includes:"
        log_warning "  - CloudFormation stacks"
        log_warning "  - DynamoDB tables"
        log_warning "  - ECS clusters and services"
        log_warning "  - VPC and networking resources"
        log_warning "  - IAM roles and policies"
        log_warning "  - CloudWatch log groups"
        log_warning "  - SSM parameters"
    else
        log_warning "This includes:"
        log_warning "  - LocalStack CloudFormation stacks"
        log_warning "  - LocalStack DynamoDB tables"
        log_warning "  - Local Docker containers"
    fi
    
    echo
    read -p "Are you sure you want to proceed? (yes/no): " -r
    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        log_info "Destruction cancelled"
        exit 0
    fi
}

# Stop local containers using Docker Compose
stop_local_containers() {
    log_info "Stopping local Docker Compose services..."
    
    cd "$PROJECT_ROOT"
    
    # Check if Docker Compose is available
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
        log_warning "Docker Compose not available, trying individual container cleanup..."
        
        # Fallback to individual container cleanup
        local containers=("dynamodb-load-test-app" "dynamodb-setup" "localstack-dynamodb-test")
        
        for container in "${containers[@]}"; do
            if docker ps -aq -f name="$container" | grep -q .; then
                log_info "Stopping container: $container"
                docker stop "$container" 2>/dev/null || true
                docker rm "$container" 2>/dev/null || true
            fi
        done
        
        # Remove application images
        if docker images -q dynamodb-load-test | grep -q .; then
            log_info "Removing application images"
            docker rmi $(docker images -q dynamodb-load-test) 2>/dev/null || true
        fi
        
        log_success "Local containers stopped and removed"
        return
    fi
    
    # Use docker compose (newer) or docker-compose (legacy)
    local compose_cmd="docker compose"
    if ! docker compose version &> /dev/null 2>&1; then
        compose_cmd="docker-compose"
    fi
    
    # Stop and remove all services
    log_info "Stopping all Docker Compose services..."
    $compose_cmd down --remove-orphans --volumes 2>/dev/null || true
    
    # Remove application images
    if docker images -q dynamodb-load-test | grep -q .; then
        log_info "Removing application images"
        docker rmi $(docker images -q dynamodb-load-test) 2>/dev/null || true
    fi
    
    log_success "Local Docker Compose services stopped and removed"
}

# Delete CloudFormation stack
delete_cloudformation_stack() {
    log_info "Deleting CloudFormation stack..."
    
    local aws_cmd="aws"
    local endpoint_url=""
    
    if [[ "$ENVIRONMENT" == "local" ]]; then
        aws_cmd="AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws"
        endpoint_url="--endpoint-url http://localhost:4566"
    elif [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    local stack_name="$STACK_NAME"
    
    # Check if stack exists
    if ! $aws_cmd cloudformation describe-stacks --stack-name "$stack_name" $endpoint_url &>/dev/null; then
        log_warning "Stack does not exist: $stack_name"
        return 0
    fi
    
    # Delete stack
    log_info "Deleting stack: $stack_name"
    $aws_cmd cloudformation delete-stack --stack-name "$stack_name" $endpoint_url
    
    # Wait for deletion to complete
    log_info "Waiting for stack deletion to complete..."
    $aws_cmd cloudformation wait stack-delete-complete --stack-name "$stack_name" $endpoint_url
    
    log_success "Stack deleted successfully: $stack_name"
}

# Clean up SSM parameters (AWS only)
cleanup_ssm_parameters() {
    if [[ "$ENVIRONMENT" != "aws" ]]; then
        return 0
    fi
    
    log_info "Cleaning up SSM parameters..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    local param_prefix="${SSM_PARAMETER_PREFIX:-/dynamodb-load-test}"
    
    # Get all parameters with the prefix
    local parameters
    parameters=$($aws_cmd ssm get-parameters-by-path \
        --path "$param_prefix" \
        --query 'Parameters[*].Name' \
        --output text 2>/dev/null || echo "")
    
    if [[ -n "$parameters" ]]; then
        for param in $parameters; do
            log_info "Deleting parameter: $param"
            $aws_cmd ssm delete-parameter --name "$param" || true
        done
        log_success "SSM parameters cleaned up"
    else
        log_info "No SSM parameters found to clean up"
    fi
}

# Clean up ECR images (AWS only)
cleanup_ecr_images() {
    if [[ "$ENVIRONMENT" != "aws" ]]; then
        return 0
    fi
    
    log_info "Cleaning up ECR images..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    # Check if ECR repository exists
    if $aws_cmd ecr describe-repositories --repository-names "$ECR_REPOSITORY_NAME" &>/dev/null; then
        # Delete all images in the repository
        local image_ids
        image_ids=$($aws_cmd ecr list-images \
            --repository-name "$ECR_REPOSITORY_NAME" \
            --query 'imageIds[*]' \
            --output json 2>/dev/null || echo "[]")
        
        if [[ "$image_ids" != "[]" ]]; then
            log_info "Deleting ECR images from repository: $ECR_REPOSITORY_NAME"
            $aws_cmd ecr batch-delete-image \
                --repository-name "$ECR_REPOSITORY_NAME" \
                --image-ids "$image_ids" || true
            log_success "ECR images cleaned up"
        else
            log_info "No ECR images found to clean up"
        fi
    else
        log_info "ECR repository does not exist: $ECR_REPOSITORY_NAME"
    fi
}

# Main execution function
main() {
    log_info "Starting infrastructure destruction"
    
    # Parse command line arguments
    parse_arguments "$@"
    
    log_info "Environment: $ENVIRONMENT"
    if [[ -n "$AWS_PROFILE" ]]; then
        log_info "AWS Profile: $AWS_PROFILE"
    fi
    
    # Load environment configuration
    load_environment_config "$ENVIRONMENT"
    
    # Confirm destruction
    confirm_destruction
    
    # Perform destruction based on environment
    case "$ENVIRONMENT" in
        "local")
            stop_local_containers
            delete_cloudformation_stack
            ;;
        "aws")
            cleanup_ecr_images
            delete_cloudformation_stack
            cleanup_ssm_parameters
            ;;
    esac
    
    log_success "Infrastructure destruction completed successfully"
    
    if [[ "$ENVIRONMENT" == "aws" ]]; then
        log_info "Note: S3 buckets and some AWS resources may need manual cleanup"
        log_info "Check the AWS console for any remaining resources"
    fi
}

# Handle script interruption
trap 'log_error "Script interrupted"; exit 1' INT TERM

# Execute main function with all arguments
main "$@"