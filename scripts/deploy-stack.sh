#!/bin/bash

# Deploy Stack Script - Deploy/Update Infrastructure Stack
# Usage: ./deploy-stack.sh [local|aws] [create|update|status] [options]
# Example: ./deploy-stack.sh local create
# Example: ./deploy-stack.sh aws update --aws-profile prod

set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default options
ENVIRONMENT="local"
ACTION="update"
AWS_PROFILE=""

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
Usage: $0 [environment] [action] [options]

Arguments:
  environment           Target environment (local, aws) - default: local
  action               Action to perform (create, update, status) - default: update

Options:
  --aws-profile PROFILE AWS profile to use for AWS operations
  -h, --help           Show this help message

Actions:
  create               Create new infrastructure stack
  update               Update existing infrastructure stack (default)
  status               Show current infrastructure status

Examples:
  $0 local create                       # Create LocalStack infrastructure
  $0 aws update --aws-profile prod      # Update AWS infrastructure
  $0 aws status                         # Show AWS infrastructure status

EOF
}

# Parse command line arguments
parse_arguments() {
    # First argument is environment if it doesn't start with -
    if [[ $# -gt 0 && ! "$1" =~ ^- ]]; then
        ENVIRONMENT="$1"
        shift
    fi
    
    # Second argument is action if it doesn't start with -
    if [[ $# -gt 0 && ! "$1" =~ ^- ]]; then
        ACTION="$1"
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
    
    # Validate action
    if [[ ! "$ACTION" =~ ^(create|update|status)$ ]]; then
        log_error "Invalid action: $ACTION. Must be 'create', 'update', or 'status'"
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
    
    # Generate dynamic values if not set
    if [[ -z "${TEMPLATE_S3_BUCKET:-}" ]]; then
        # Get AWS account ID for bucket name
        local aws_cmd="aws"
        if [[ -n "$AWS_PROFILE" ]]; then
            aws_cmd="aws --profile $AWS_PROFILE"
        fi
        
        local account_id
        account_id=$($aws_cmd sts get-caller-identity --query Account --output text 2>/dev/null)
        if [[ -n "$account_id" && "$account_id" != "null" ]]; then
            export TEMPLATE_S3_BUCKET="dynamodb-load-test-templates-${account_id}"
            log_info "Generated S3 bucket name: $TEMPLATE_S3_BUCKET"
        else
            log_error "Failed to get AWS account ID for S3 bucket name generation"
            exit 1
        fi
    fi
    
    log_success "Environment configuration loaded from: $env_file"
}

# Validate prerequisites
validate_prerequisites() {
    log_info "Validating prerequisites..."
    
    # Check if AWS CLI is installed
    if ! command -v aws &> /dev/null; then
        log_error "AWS CLI is not installed. Please install it first"
        exit 1
    fi
    
    # Validate AWS credentials
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    if [[ "$ENVIRONMENT" == "local" ]]; then
        # For local, check LocalStack connectivity
        if ! AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 --region us-east-1 sts get-caller-identity &> /dev/null; then
            log_error "LocalStack is not accessible. Make sure LocalStack is running on localhost:4566"
            exit 1
        fi
    else
        # For AWS, check credentials
        if ! $aws_cmd sts get-caller-identity &> /dev/null; then
            log_error "AWS credentials not configured or invalid"
            exit 1
        fi
    fi
    
    log_success "Prerequisites validation passed"
}

# Sync CloudFormation parameters
sync_cfn_parameters() {
    log_info "Synchronizing CloudFormation parameters..."
    
    local param_file="${PROJECT_ROOT}/cloudformation/parameters-template.json"
    local backup_file="${param_file}.backup.$(date +%Y%m%d_%H%M%S)"
    
    # Create backup
    cp "$param_file" "$backup_file"
    log_info "Backup created: $backup_file"
    
    # Update parameters based on environment variables
    local temp_file=$(mktemp)
    cp "$param_file" "$temp_file"
    
    # Function to update JSON parameter
    update_json_parameter() {
        local key="$1"
        local value="$2"
        local file="$3"
        
        # Use jq to update the parameter value
        jq --arg key "$key" --arg value "$value" '
            map(if .ParameterKey == $key then .ParameterValue = $value else . end)
        ' "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
    }
    
    # Update key parameters
    if [[ -n "${TABLE_NAME:-}" ]]; then
        # For CloudFormation, use base name without environment prefix
        local base_table_name="${TABLE_NAME}"
        if [[ "$base_table_name" == aws-* ]]; then
            base_table_name="${base_table_name#aws-}"
        fi
        update_json_parameter "TableName" "$base_table_name" "$temp_file"
    fi
    
    if [[ -n "${CONCURRENCY_LIMIT:-}" ]]; then
        update_json_parameter "ConcurrencyLimit" "$CONCURRENCY_LIMIT" "$temp_file"
    fi
    
    if [[ -n "${TOTAL_ITEMS:-}" ]]; then
        update_json_parameter "TotalItems" "$TOTAL_ITEMS" "$temp_file"
    fi
    
    if [[ -n "${TASK_CPU:-}" ]]; then
        update_json_parameter "TaskCpu" "$TASK_CPU" "$temp_file"
    fi
    
    if [[ -n "${TASK_MEMORY:-}" ]]; then
        update_json_parameter "TaskMemory" "$TASK_MEMORY" "$temp_file"
    fi
    
    # Move updated file back
    mv "$temp_file" "$param_file"
    
    log_success "CloudFormation parameters synchronized"
}

# Upload templates to S3 (AWS only)
upload_templates() {
    if [[ "$ENVIRONMENT" == "local" ]]; then
        log_info "Skipping template upload for local environment"
        return 0
    fi
    
    log_info "Uploading CloudFormation templates to S3..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    # Check if S3 bucket exists
    if ! $aws_cmd s3api head-bucket --bucket "$TEMPLATE_S3_BUCKET" 2>/dev/null; then
        log_error "S3 bucket does not exist: $TEMPLATE_S3_BUCKET"
        log_error "Create the bucket first or update TEMPLATE_S3_BUCKET in .env.aws"
        exit 1
    fi
    
    # Upload all CloudFormation templates
    local template_dir="${PROJECT_ROOT}/cloudformation"
    for template in "$template_dir"/*.yaml; do
        if [[ -f "$template" ]]; then
            local template_name=$(basename "$template")
            local s3_key="${TEMPLATE_S3_KEY_PREFIX}${template_name}"
            
            log_info "Uploading: $template_name"
            $aws_cmd s3 cp "$template" "s3://${TEMPLATE_S3_BUCKET}/${s3_key}"
        fi
    done
    
    log_success "Templates uploaded successfully"
}

# Deploy local infrastructure (DynamoDB only)
deploy_local_infrastructure() {
    log_info "Deploying local infrastructure (DynamoDB table only)..."
    
    # Function to execute AWS commands with proper environment
    execute_aws_command() {
        AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 "$@"
    }
    
    local table_name="$TABLE_NAME"
    
    # Check if table already exists
    if execute_aws_command dynamodb describe-table --table-name "$table_name" &>/dev/null; then
        log_info "DynamoDB table already exists: $table_name"
        log_success "Local infrastructure deployment completed successfully"
        return 0
    fi
    
    # Create DynamoDB table
    log_info "Creating DynamoDB table: $table_name"
    execute_aws_command dynamodb create-table \
        --table-name "$table_name" \
        --attribute-definitions \
            AttributeName=PK,AttributeType=S \
        --key-schema \
            AttributeName=PK,KeyType=HASH \
        --provisioned-throughput \
            ReadCapacityUnits="$READ_CAPACITY_UNITS" \
            WriteCapacityUnits="$WRITE_CAPACITY_UNITS" \
        --region "$AWS_REGION"
    
    # Wait for table to be active
    log_info "Waiting for table to become active..."
    execute_aws_command dynamodb wait table-exists --table-name "$table_name" --region "$AWS_REGION"
    
    log_success "DynamoDB table created successfully: $table_name"
    log_success "Local infrastructure deployment completed successfully"
}

# Deploy or update stack
deploy_stack() {
    log_info "Deploying infrastructure stack..."
    
    local stack_name="$STACK_NAME"
    local template_file="${PROJECT_ROOT}/cloudformation/main-template.yaml"
    local parameters_file="${PROJECT_ROOT}/cloudformation/parameters-template.json"
    
    # Function to execute AWS commands with proper environment
    execute_aws_command() {
        if [[ "$ENVIRONMENT" == "local" ]]; then
            AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 "$@"
        elif [[ -n "$AWS_PROFILE" ]]; then
            aws --profile "$AWS_PROFILE" "$@"
        else
            aws "$@"
        fi
    }
    
    # Check if stack exists
    local stack_exists=false
    if execute_aws_command cloudformation describe-stacks --stack-name "$stack_name" &>/dev/null; then
        stack_exists=true
    fi
    
    local operation
    if [[ "$stack_exists" == "true" ]]; then
        operation="update-stack"
        log_info "Updating existing stack: $stack_name"
    else
        operation="create-stack"
        log_info "Creating new stack: $stack_name"
    fi
    
    # Deploy stack
    local template_body
    if [[ "$ENVIRONMENT" == "local" ]]; then
        template_body="--template-body file://$template_file"
    else
        template_body="--template-url https://${TEMPLATE_S3_BUCKET}.s3.amazonaws.com/${TEMPLATE_S3_KEY_PREFIX}main-template.yaml"
    fi
    
    execute_aws_command cloudformation $operation \
        --stack-name "$stack_name" \
        $template_body \
        --parameters "file://$parameters_file" \
        --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM
    
    # Wait for completion
    log_info "Waiting for stack operation to complete..."
    local wait_condition
    if [[ "$stack_exists" == "true" ]]; then
        wait_condition="stack-update-complete"
    else
        wait_condition="stack-create-complete"
    fi
    
    execute_aws_command cloudformation wait $wait_condition --stack-name "$stack_name"
    
    log_success "Stack deployment completed successfully"
}

# Show infrastructure status
show_status() {
    log_info "Checking infrastructure status..."
    
    # Function to execute AWS commands with proper environment
    execute_aws_command() {
        if [[ "$ENVIRONMENT" == "local" ]]; then
            AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url http://localhost:4566 "$@"
        elif [[ -n "$AWS_PROFILE" ]]; then
            aws --profile "$AWS_PROFILE" "$@"
        else
            aws "$@"
        fi
    }
    
    if [[ "$ENVIRONMENT" == "local" ]]; then
        # For local environment, check DynamoDB table status
        local table_name="$TABLE_NAME"
        
        if execute_aws_command dynamodb describe-table --table-name "$table_name" &>/dev/null; then
            log_info "DynamoDB Table Status:"
            execute_aws_command dynamodb describe-table \
                --table-name "$table_name" \
                --query 'Table.[TableName,TableStatus,CreationDateTime,ProvisionedThroughput.ReadCapacityUnits,ProvisionedThroughput.WriteCapacityUnits]' \
                --output table
        else
            log_warning "DynamoDB table does not exist: $table_name"
        fi
    else
        # For AWS environment, check CloudFormation stack
        local stack_name="$STACK_NAME"
        
        if execute_aws_command cloudformation describe-stacks --stack-name "$stack_name" &>/dev/null; then
            log_info "Stack Status:"
            execute_aws_command cloudformation describe-stacks \
                --stack-name "$stack_name" \
                --query 'Stacks[0].[StackName,StackStatus,CreationTime,LastUpdatedTime]' \
                --output table
            
            log_info "Stack Outputs:"
            execute_aws_command cloudformation describe-stacks \
                --stack-name "$stack_name" \
                --query 'Stacks[0].Outputs[*].[OutputKey,OutputValue,Description]' \
                --output table
        else
            log_warning "Stack does not exist: $stack_name"
        fi
    fi
}

# Clean up parameter backups after successful deployment
cleanup_parameter_backups() {
    log_info "Cleaning up parameter template backups..."
    
    local param_dir="${PROJECT_ROOT}/cloudformation"
    local backup_pattern="${param_dir}/parameters-template.json.backup.*"
    
    # Find and remove backup files
    local backup_count=0
    for backup_file in $backup_pattern; do
        if [[ -f "$backup_file" ]]; then
            rm -f "$backup_file"
            log_info "Removed backup: $(basename "$backup_file")"
            ((backup_count++))
        fi
    done
    
    if [[ $backup_count -gt 0 ]]; then
        log_success "Cleaned up $backup_count parameter backup file(s)"
    else
        log_info "No parameter backup files found to clean up"
    fi
}

# Main execution function
main() {
    log_info "Starting infrastructure management"
    
    # Parse command line arguments
    parse_arguments "$@"
    
    log_info "Environment: $ENVIRONMENT"
    log_info "Action: $ACTION"
    if [[ -n "$AWS_PROFILE" ]]; then
        log_info "AWS Profile: $AWS_PROFILE"
    fi
    
    # Load environment configuration
    load_environment_config "$ENVIRONMENT"
    
    # Validate prerequisites
    validate_prerequisites
    
    case "$ACTION" in
        "create"|"update")
            if [[ "$ENVIRONMENT" == "local" ]]; then
                # For local environment, use simplified deployment
                deploy_local_infrastructure
                # Clean up backups after successful deployment
                cleanup_parameter_backups
            else
                # For AWS environment, use CloudFormation
                sync_cfn_parameters
                upload_templates
                deploy_stack
                # Clean up backups after successful deployment
                cleanup_parameter_backups
            fi
            ;;
        "status")
            show_status
            ;;
    esac
    
    log_success "Infrastructure management completed successfully"
}

# Handle script interruption
trap 'log_error "Script interrupted"; exit 1' INT TERM

# Execute main function with all arguments
main "$@"