#!/bin/bash

# DynamoDB Load Test - Infrastructure Setup Script
# This script creates and configures the S3 bucket for CloudFormation templates
# Usage: ./setup-stack.sh [environment] [action]
# Example: ./setup-stack.sh local create
# Example: ./setup-stack.sh prod upload-templates

set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
CLOUDFORMATION_DIR="${PROJECT_ROOT}/cloudformation"

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
Usage: $0 [environment] [action]

Arguments:
  environment    Target environment (local, dev, prod)
  action         Action to perform (create, upload-templates, delete, status)

Actions:
  create              Create S3 bucket and configure it
  upload-templates    Upload CloudFormation templates to S3
  delete              Delete S3 bucket (WARNING: destructive)
  status              Show bucket status and contents

Environment Files:
  The script looks for environment-specific configuration files:
  - .env.local   - Local development configuration (for LocalStack)
  
  For production deployments, use CloudFormation parameters-template.json
  instead of .env files to maintain infrastructure as code best practices.

Examples:
  $0 local create           - Create S3 bucket for local environment
  $0 prod upload-templates  - Upload templates to production bucket
  $0 dev status            - Show status of development bucket

Environment Variables (can be set in .env files):
  AWS_REGION                - AWS region for S3 bucket
  TEMPLATE_S3_BUCKET       - S3 bucket name for templates
  TEMPLATE_S3_KEY_PREFIX   - S3 key prefix for templates

EOF
}

# Validate prerequisites
validate_prerequisites() {
    log_info "Validating prerequisites..."
    
    # Check if AWS CLI is installed
    if ! command -v aws &> /dev/null; then
        log_error "AWS CLI is not installed. Please install it first."
        exit 1
    fi
    
    # Check AWS credentials based on environment
    if [[ "${ENVIRONMENT:-}" == "local" ]]; then
        # For local, check LocalStack connectivity
        if ! AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 --region us-east-1 sts get-caller-identity &> /dev/null; then
            log_error "LocalStack is not accessible. Make sure LocalStack is running on localhost:4566"
            exit 1
        fi
    else
        # For AWS, check real credentials
        if ! aws sts get-caller-identity &> /dev/null; then
            log_error "AWS credentials not configured or invalid."
            exit 1
        fi
    fi
    
    # Check if CloudFormation directory exists
    if [[ ! -d "$CLOUDFORMATION_DIR" ]]; then
        log_error "CloudFormation directory not found: $CLOUDFORMATION_DIR"
        exit 1
    fi
    
    log_success "Prerequisites validation passed"
}

# Load environment configuration
load_environment_config() {
    local env="$1"
    local env_file="${PROJECT_ROOT}/.env.${env}"
    
    log_info "Loading environment configuration for: $env"
    
    if [[ ! -f "$env_file" ]]; then
        log_error "Environment file not found: $env_file"
        log_info "Please create $env_file with required configuration variables"
        exit 1
    fi
    
    # Source the environment file
    set -a  # automatically export all variables
    source "$env_file"
    set +a
    
    log_success "Environment configuration loaded from: $env_file"
}

# Execute AWS commands with proper environment configuration
execute_aws_command() {
    if [[ "${ENVIRONMENT:-}" == "local" ]]; then
        AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 "$@"
    else
        aws "$@"
    fi
}

# Generate S3 bucket name dynamically (includes account ID for uniqueness)
generate_bucket_name() {
    log_info "Generating S3 bucket name dynamically..."
    
    # Get AWS account ID
    local account_id
    if [[ "${ENVIRONMENT:-}" == "local" ]]; then
        account_id="000000000000"  # LocalStack default account ID
    else
        account_id=$(aws sts get-caller-identity --query 'Account' --output text)
        if [[ -z "$account_id" ]]; then
            log_error "Failed to get AWS account ID"
            exit 1
        fi
    fi
    
    # Generate bucket name: dynamodb-load-test-templates-{account-id}-{region}
    export TEMPLATE_S3_BUCKET="dynamodb-load-test-templates-${account_id}-${AWS_REGION}"
    
    log_info "Generated bucket name: $TEMPLATE_S3_BUCKET"
}

# Validate required environment variables
validate_environment_variables() {
    log_info "Validating environment variables..."
    
    local required_vars=(
        "AWS_REGION"
    )
    
    # TEMPLATE_S3_BUCKET is optional - will be generated if not provided
    
    local missing_vars=()
    
    for var in "${required_vars[@]}"; do
        if [[ -z "${!var:-}" ]]; then
            missing_vars+=("$var")
        fi
    done
    
    if [[ ${#missing_vars[@]} -gt 0 ]]; then
        log_error "Missing required environment variables:"
        for var in "${missing_vars[@]}"; do
            log_error "  - $var"
        done
        exit 1
    fi
    
    # Set defaults for optional variables
    export TEMPLATE_S3_KEY_PREFIX="${TEMPLATE_S3_KEY_PREFIX:-dynamodb-load-test/}"
    
    # Ensure TEMPLATE_S3_BUCKET is set (either from env file or generated)
    if [[ -z "${TEMPLATE_S3_BUCKET:-}" ]]; then
        log_error "TEMPLATE_S3_BUCKET is not set and could not be generated"
        exit 1
    fi
    
    log_success "Environment variables validation passed"
}

# Check if S3 bucket exists
bucket_exists() {
    local bucket_name="$1"
    execute_aws_command s3api head-bucket --bucket "$bucket_name" --region "$AWS_REGION" 2>/dev/null
}

# Get bucket region
get_bucket_region() {
    local bucket_name="$1"
    execute_aws_command s3api get-bucket-location --bucket "$bucket_name" --query 'LocationConstraint' --output text 2>/dev/null || echo "us-east-1"
}

# Create S3 bucket
create_s3_bucket() {
    local bucket_name="$1"
    
    log_info "Creating S3 bucket: $bucket_name"
    
    if bucket_exists "$bucket_name"; then
        log_warning "S3 bucket already exists: $bucket_name"
        
        # Check if it's in the correct region
        local bucket_region
        bucket_region=$(get_bucket_region "$bucket_name")
        
        if [[ "$bucket_region" != "$AWS_REGION" && ! ("$bucket_region" == "None" && "$AWS_REGION" == "us-east-1") ]]; then
            log_error "Bucket exists in different region: $bucket_region (expected: $AWS_REGION)"
            exit 1
        fi
        
        log_info "Using existing bucket in correct region"
        return 0
    fi
    
    # Create bucket with appropriate location constraint
    if [[ "$AWS_REGION" == "us-east-1" ]]; then
        # us-east-1 doesn't need location constraint
        execute_aws_command s3api create-bucket \
            --bucket "$bucket_name" \
            --region "$AWS_REGION"
    else
        # Other regions need location constraint
        execute_aws_command s3api create-bucket \
            --bucket "$bucket_name" \
            --region "$AWS_REGION" \
            --create-bucket-configuration LocationConstraint="$AWS_REGION"
    fi
    
    log_success "S3 bucket created: $bucket_name"
}

# Configure S3 bucket
configure_s3_bucket() {
    local bucket_name="$1"
    
    log_info "Configuring S3 bucket: $bucket_name"
    
    # Enable versioning
    log_info "Enabling versioning..."
    aws s3api put-bucket-versioning \
        --bucket "$bucket_name" \
        --versioning-configuration Status=Enabled \
        --region "$AWS_REGION"
    
    # Configure server-side encryption
    log_info "Configuring server-side encryption..."
    aws s3api put-bucket-encryption \
        --bucket "$bucket_name" \
        --server-side-encryption-configuration '{
            "Rules": [
                {
                    "ApplyServerSideEncryptionByDefault": {
                        "SSEAlgorithm": "AES256"
                    },
                    "BucketKeyEnabled": true
                }
            ]
        }' \
        --region "$AWS_REGION"
    
    # Block public access
    log_info "Configuring public access block..."
    aws s3api put-public-access-block \
        --bucket "$bucket_name" \
        --public-access-block-configuration \
            BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true \
        --region "$AWS_REGION"
    
    # Add lifecycle policy to clean up old versions
    log_info "Configuring lifecycle policy..."
    aws s3api put-bucket-lifecycle-configuration \
        --bucket "$bucket_name" \
        --lifecycle-configuration '{
            "Rules": [
                {
                    "ID": "DeleteOldVersions",
                    "Status": "Enabled",
                    "Filter": {
                        "Prefix": ""
                    },
                    "NoncurrentVersionExpiration": {
                        "NoncurrentDays": 30
                    }
                }
            ]
        }' \
        --region "$AWS_REGION"
    
    log_success "S3 bucket configuration completed"
}

# Upload CloudFormation templates
upload_templates() {
    local bucket_name="$1"
    
    log_info "Uploading CloudFormation templates to S3..."
    
    # Find all YAML and JSON template files
    local templates=(
        "infrastructure-template.yaml"
        "dynamodb-template.yaml"
        "ecs-template.yaml"
        "monitoring-template.yaml"
    )
    
    local uploaded_count=0
    local total_count=0
    
    for template in "${templates[@]}"; do
        local template_path="${CLOUDFORMATION_DIR}/${template}"
        local s3_key="${TEMPLATE_S3_KEY_PREFIX}${template}"
        
        ((total_count++))
        
        if [[ -f "$template_path" ]]; then
            log_info "Uploading $template to s3://${bucket_name}/${s3_key}"
            
            # Upload with metadata
            aws s3 cp "$template_path" "s3://${bucket_name}/${s3_key}" \
                --region "$AWS_REGION" \
                --metadata "project=dynamodb-load-test,uploaded=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            
            ((uploaded_count++))
        else
            log_warning "Template not found: $template_path"
        fi
    done
    
    log_success "Templates uploaded: $uploaded_count/$total_count"
    
    if [[ $uploaded_count -eq 0 ]]; then
        log_error "No templates were uploaded"
        exit 1
    fi
}

# Delete S3 bucket
delete_s3_bucket() {
    local bucket_name="$1"
    
    log_warning "This will permanently delete the S3 bucket and all its contents!"
    read -p "Are you sure you want to delete bucket '$bucket_name'? (yes/no): " confirm
    
    if [[ "$confirm" != "yes" ]]; then
        log_info "Bucket deletion cancelled"
        return 0
    fi
    
    log_info "Deleting S3 bucket: $bucket_name"
    
    if ! bucket_exists "$bucket_name"; then
        log_warning "S3 bucket does not exist: $bucket_name"
        return 0
    fi
    
    # Delete all objects and versions
    log_info "Deleting all objects and versions..."
    aws s3api delete-objects \
        --bucket "$bucket_name" \
        --delete "$(aws s3api list-object-versions \
            --bucket "$bucket_name" \
            --output json \
            --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}')" \
        --region "$AWS_REGION" 2>/dev/null || true
    
    # Delete all delete markers
    aws s3api delete-objects \
        --bucket "$bucket_name" \
        --delete "$(aws s3api list-object-versions \
            --bucket "$bucket_name" \
            --output json \
            --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}')" \
        --region "$AWS_REGION" 2>/dev/null || true
    
    # Delete the bucket
    aws s3api delete-bucket \
        --bucket "$bucket_name" \
        --region "$AWS_REGION"
    
    log_success "S3 bucket deleted: $bucket_name"
}

# Show bucket status
show_bucket_status() {
    local bucket_name="$1"
    
    log_info "Checking S3 bucket status: $bucket_name"
    
    if ! bucket_exists "$bucket_name"; then
        log_warning "S3 bucket does not exist: $bucket_name"
        return 0
    fi
    
    # Get bucket information
    local bucket_region
    bucket_region=$(get_bucket_region "$bucket_name")
    
    echo
    log_success "Bucket Information:"
    echo "  Name: $bucket_name"
    echo "  Region: $bucket_region"
    
    # Check versioning status
    local versioning_status
    versioning_status=$(aws s3api get-bucket-versioning --bucket "$bucket_name" --region "$AWS_REGION" --query 'Status' --output text 2>/dev/null || echo "Disabled")
    echo "  Versioning: $versioning_status"
    
    # Check encryption
    local encryption_status
    if aws s3api get-bucket-encryption --bucket "$bucket_name" --region "$AWS_REGION" &>/dev/null; then
        encryption_status="Enabled"
    else
        encryption_status="Disabled"
    fi
    echo "  Encryption: $encryption_status"
    
    # List templates
    echo
    log_info "CloudFormation Templates:"
    
    local objects
    objects=$(aws s3 ls "s3://${bucket_name}/${TEMPLATE_S3_KEY_PREFIX}" --region "$AWS_REGION" 2>/dev/null || echo "")
    
    if [[ -n "$objects" ]]; then
        echo "$objects"
    else
        echo "  No templates found in s3://${bucket_name}/${TEMPLATE_S3_KEY_PREFIX}"
    fi
    
    echo
}

# Main execution function
main() {
    local environment="${1:-}"
    local action="${2:-create}"
    
    # Validate arguments
    if [[ -z "$environment" ]]; then
        log_error "Environment argument is required"
        usage
        exit 1
    fi
    
    if [[ ! "$environment" =~ ^(local|dev|prod|aws)$ ]]; then
        log_error "Invalid environment: $environment. Must be one of: local, dev, prod, aws"
        exit 1
    fi
    
    if [[ ! "$action" =~ ^(create|upload-templates|delete|status)$ ]]; then
        log_error "Invalid action: $action. Must be one of: create, upload-templates, delete, status"
        exit 1
    fi
    
    log_info "Starting infrastructure setup script"
    log_info "Environment: $environment"
    log_info "Action: $action"
    
    # Execute prerequisite checks
    validate_prerequisites
    
    # Load environment configuration
    load_environment_config "$environment"
    
    # Generate bucket name if not provided (for AWS environments)
    if [[ -z "${TEMPLATE_S3_BUCKET:-}" && "$environment" != "local" ]]; then
        generate_bucket_name
    fi
    
    validate_environment_variables
    
    # Execute requested action
    case "$action" in
        "create")
            create_s3_bucket "$TEMPLATE_S3_BUCKET"
            configure_s3_bucket "$TEMPLATE_S3_BUCKET"
            upload_templates "$TEMPLATE_S3_BUCKET"
            show_bucket_status "$TEMPLATE_S3_BUCKET"
            ;;
        "upload-templates")
            if ! bucket_exists "$TEMPLATE_S3_BUCKET"; then
                log_error "S3 bucket does not exist: $TEMPLATE_S3_BUCKET"
                log_info "Run with 'create' action first to create the bucket"
                exit 1
            fi
            upload_templates "$TEMPLATE_S3_BUCKET"
            ;;
        "delete")
            delete_s3_bucket "$TEMPLATE_S3_BUCKET"
            ;;
        "status")
            show_bucket_status "$TEMPLATE_S3_BUCKET"
            ;;
    esac
    
    log_success "Infrastructure setup script completed successfully"
}

# Handle script interruption
trap 'log_error "Script interrupted"; exit 1' INT TERM

# Execute main function with all arguments
main "$@"