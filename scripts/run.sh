#!/bin/bash

# Run Script - Run Application (Container for Local, ECS Task for AWS)
# Usage: ./run.sh [local|aws] [options]
# Example: ./run.sh local
# Example: ./run.sh aws --aws-profile prod --wait --logs

set -euo pipefail

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default options
ENVIRONMENT="local"
AWS_PROFILE=""
WAIT_FOR_COMPLETION=false
STREAM_LOGS=false
CONTAINER_NAME="dynamodb-load-test"

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
  --wait               Wait for task completion (AWS only)
  --logs               Stream logs during execution
  --container-name NAME Container name for local runs (default: dynamodb-load-test)
  -h, --help           Show this help message

Examples:
  $0 local                              # Run container locally
  $0 local --logs                       # Run container locally with log streaming
  $0 aws --aws-profile prod             # Run ECS task in AWS
  $0 aws --wait --logs                  # Run ECS task and wait with log streaming

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
            --wait)
                WAIT_FOR_COMPLETION=true
                shift
                ;;
            --logs)
                STREAM_LOGS=true
                shift
                ;;
            --container-name)
                if [[ -n "${2:-}" ]]; then
                    CONTAINER_NAME="$2"
                    shift 2
                else
                    log_error "--container-name requires a name"
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

# Update SSM parameters (AWS only)
update_ssm_parameters() {
    if [[ "$ENVIRONMENT" != "aws" ]]; then
        return 0
    fi
    
    log_info "Updating SSM parameters..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    local param_prefix="${SSM_PARAMETER_PREFIX:-/dynamodb-load-test}"
    
    # Function to update parameter
    update_parameter() {
        local param_name="$1"
        local param_value="$2"
        local description="$3"
        
        log_info "Updating parameter: $param_name = $param_value"
        
        if $aws_cmd ssm put-parameter \
            --name "$param_name" \
            --value "$param_value" \
            --type "String" \
            --description "$description" \
            --overwrite \
            > /dev/null 2>&1; then
            log_info "Updated: $param_name"
        else
            log_error "Failed to update: $param_name"
            return 1
        fi
    }
    
    # Update core parameters
    update_parameter "${param_prefix}/table-name" "${TABLE_NAME}" "DynamoDB table name for load testing"
    update_parameter "${param_prefix}/concurrency-limit" "${CONCURRENCY_LIMIT}" "Maximum concurrency limit for load testing"
    update_parameter "${param_prefix}/total-items" "${TOTAL_ITEMS}" "Total number of items to write during load test"
    update_parameter "${param_prefix}/max-concurrency-percentage" "${MAX_CONCURRENCY_PERCENTAGE}" "Percentage of items to write at maximum concurrency"
    update_parameter "${param_prefix}/duplicate-percentage" "${DUPLICATE_PERCENTAGE}" "Percentage of items that should be duplicates (0.0 = no duplicates)"
    update_parameter "${param_prefix}/cleanup-after-test" "${CLEANUP_AFTER_TEST}" "Whether to cleanup test items after load test completion"
    
    log_success "SSM parameters updated successfully"
}

# Run local container using Docker Compose
run_local_container() {
    log_info "Running application locally using Docker Compose..."
    
    cd "$PROJECT_ROOT"
    
    # Check if Docker Compose is available
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
        log_error "Docker Compose is not available. Please install Docker Compose."
        exit 1
    fi
    
    # Use docker compose (newer) or docker-compose (legacy)
    local compose_cmd="docker compose"
    if ! docker compose version &> /dev/null 2>&1; then
        compose_cmd="docker-compose"
    fi
    
    # Stop any existing services
    log_info "Stopping any existing services..."
    $compose_cmd down --remove-orphans 2>/dev/null || true
    
    # Run setup script directly (using existing LocalStack instance)
    log_info "Setting up DynamoDB and SSM using existing LocalStack..."
    if ! ./scripts/setup-localstack.sh; then
        log_error "Infrastructure setup failed"
        exit 1
    fi
    
    log_success "Infrastructure setup completed successfully"
    
    # Run the application
    log_info "Starting DynamoDB Load Test application..."
    
    if [[ "$STREAM_LOGS" == "true" ]]; then
        # Run with logs in foreground
        $compose_cmd --profile app up dynamodb-load-test
    else
        # Run in background
        $compose_cmd --profile app up -d dynamodb-load-test
        log_success "Application started successfully"
        log_info "To view logs: $compose_cmd logs -f dynamodb-load-test"
        log_info "To stop all services: $compose_cmd down"
    fi
}

# Get network configuration from CloudFormation
get_network_configuration() {
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    log_info "Retrieving configuration from CloudFormation stack..."
    
    # Get stack outputs
    local stack_outputs
    stack_outputs=$($aws_cmd cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --query 'Stacks[0].Outputs' \
        --output json)
    
    if [[ -z "$stack_outputs" || "$stack_outputs" == "null" ]]; then
        log_error "Failed to retrieve CloudFormation stack outputs"
        exit 1
    fi
    
    # Extract subnet IDs
    ECS_SUBNET_IDS=$(echo "$stack_outputs" | jq -r '.[] | select(.OutputKey=="PublicSubnetId") | .OutputValue')
    if [[ -z "$ECS_SUBNET_IDS" || "$ECS_SUBNET_IDS" == "null" ]]; then
        log_error "Failed to retrieve subnet ID from CloudFormation outputs"
        exit 1
    fi
    
    # Extract security group IDs
    ECS_SECURITY_GROUP_IDS=$(echo "$stack_outputs" | jq -r '.[] | select(.OutputKey=="ECSSecurityGroupId") | .OutputValue')
    if [[ -z "$ECS_SECURITY_GROUP_IDS" || "$ECS_SECURITY_GROUP_IDS" == "null" ]]; then
        log_error "Failed to retrieve security group ID from CloudFormation outputs"
        exit 1
    fi
    
    # Extract CloudWatch log group (if not set in env)
    if [[ -z "${CLOUDWATCH_LOG_GROUP:-}" ]]; then
        CLOUDWATCH_LOG_GROUP=$(echo "$stack_outputs" | jq -r '.[] | select(.OutputKey=="CloudWatchLogGroup") | .OutputValue')
        if [[ -z "$CLOUDWATCH_LOG_GROUP" || "$CLOUDWATCH_LOG_GROUP" == "null" ]]; then
            # Fallback to constructed name
            CLOUDWATCH_LOG_GROUP="/aws/ecs/${ECS_TASK_DEFINITION}"
            log_warning "CloudWatch log group not found in outputs, using: $CLOUDWATCH_LOG_GROUP"
        fi
    fi
    
    log_info "Configuration retrieved:"
    log_info "  Subnet: $ECS_SUBNET_IDS"
    log_info "  Security Group: $ECS_SECURITY_GROUP_IDS"
    log_info "  Log Group: $CLOUDWATCH_LOG_GROUP"
}

# Run AWS ECS task
run_aws_ecs_task() {
    log_info "Running ECS task in AWS..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    # Get network configuration from CloudFormation
    get_network_configuration
    
    # Generate unique task name
    local task_name="load-test-$(date +%Y%m%d-%H%M%S)"
    
    # Launch ECS task
    log_info "Launching ECS task: $task_name"
    
    local task_arn
    task_arn=$($aws_cmd ecs run-task \
        --cluster "$ECS_CLUSTER_NAME" \
        --task-definition "$ECS_TASK_DEFINITION" \
        --launch-type FARGATE \
        --network-configuration "awsvpcConfiguration={subnets=[$ECS_SUBNET_IDS],securityGroups=[$ECS_SECURITY_GROUP_IDS],assignPublicIp=ENABLED}" \
        --started-by "$task_name" \
        --query 'tasks[0].taskArn' \
        --output text)
    
    if [[ -z "$task_arn" || "$task_arn" == "None" ]]; then
        log_error "Failed to launch ECS task"
        exit 1
    fi
    
    log_success "ECS task launched successfully"
    log_info "Task ARN: $task_arn"
    
    # Extract task ID from ARN
    local task_id="${task_arn##*/}"
    log_info "Task ID: $task_id"
    
    if [[ "$WAIT_FOR_COMPLETION" == "true" ]]; then
        log_info "Waiting for task completion..."
        
        # Start log streaming in background if requested
        local log_stream_pid=""
        if [[ "$STREAM_LOGS" == "true" ]]; then
            # Wait a bit for the task to start before streaming logs
            sleep 30
            stream_ecs_logs "$task_id" &
            log_stream_pid=$!
        fi
        
        # Monitor task status
        local timeout=3600  # 1 hour timeout
        local elapsed=0
        local poll_interval=30
        
        while [[ $elapsed -lt $timeout ]]; do
            local task_status
            task_status=$($aws_cmd ecs describe-tasks \
                --cluster "$ECS_CLUSTER_NAME" \
                --tasks "$task_arn" \
                --query 'tasks[0].lastStatus' \
                --output text)
            
            log_info "Task status: $task_status (elapsed: ${elapsed}s)"
            
            if [[ "$task_status" == "STOPPED" ]]; then
                # Stop log streaming if running
                if [[ -n "$log_stream_pid" ]]; then
                    kill $log_stream_pid 2>/dev/null || true
                fi
                
                # Check exit code
                local exit_code
                exit_code=$($aws_cmd ecs describe-tasks \
                    --cluster "$ECS_CLUSTER_NAME" \
                    --tasks "$task_arn" \
                    --query 'tasks[0].containers[0].exitCode' \
                    --output text)
                
                if [[ "$exit_code" == "0" ]]; then
                    log_success "Task completed successfully"
                else
                    log_error "Task failed with exit code: $exit_code"
                    
                    # Show recent logs for debugging
                    log_info "Recent logs from failed task:"
                    stream_ecs_logs "$task_id" --since 5m || true
                    exit 1
                fi
                break
            fi
            
            sleep $poll_interval
            elapsed=$((elapsed + poll_interval))
        done
        
        if [[ $elapsed -ge $timeout ]]; then
            # Stop log streaming if running
            if [[ -n "$log_stream_pid" ]]; then
                kill $log_stream_pid 2>/dev/null || true
            fi
            log_error "Task execution timed out after ${timeout}s"
            exit 1
        fi
    elif [[ "$STREAM_LOGS" == "true" ]]; then
        # Stream logs without waiting for completion
        log_info "Streaming logs (task will continue running)..."
        sleep 30  # Wait for task to start
        stream_ecs_logs "$task_id"
    else
        log_info "Task launched. Use --wait to wait for completion"
        log_info "To monitor: aws ecs describe-tasks --cluster $ECS_CLUSTER_NAME --tasks $task_arn"
    fi
}

# Stream ECS logs
stream_ecs_logs() {
    local task_id="$1"
    
    log_info "Streaming logs from CloudWatch..."
    
    local aws_cmd="aws"
    if [[ -n "$AWS_PROFILE" ]]; then
        aws_cmd="aws --profile $AWS_PROFILE"
    fi
    
    # Get log group name from task definition (like run-ecs-task.sh does)
    local log_group
    log_group=$($aws_cmd ecs describe-task-definition \
        --task-definition "$ECS_TASK_DEFINITION" \
        --query 'taskDefinition.containerDefinitions[0].logConfiguration.options."awslogs-group"' \
        --output text 2>/dev/null)
    
    if [[ -z "$log_group" || "$log_group" == "None" ]]; then
        log_warning "No log group found in task definition, using fallback: ${CLOUDWATCH_LOG_GROUP:-/aws/ecs/aws-dynamodb-load-test}"
        log_group="${CLOUDWATCH_LOG_GROUP:-/aws/ecs/aws-dynamodb-load-test}"
    fi
    
    log_info "Using log group: $log_group"
    
    # Get container name from task definition
    local container_name
    container_name=$($aws_cmd ecs describe-task-definition \
        --task-definition "$ECS_TASK_DEFINITION" \
        --query 'taskDefinition.containerDefinitions[0].name' \
        --output text 2>/dev/null)
    
    if [[ -z "$container_name" || "$container_name" == "None" ]]; then
        container_name="aws-dynamodb-load-test"
        log_warning "No container name found in task definition, using fallback: $container_name"
    fi
    
    log_info "Using container name: $container_name"
    
    # Construct log stream name: ecs/container-name/task-id
    local log_stream_name="ecs/${container_name}/${task_id}"
    
    log_info "Expected log stream: $log_stream_name"
    
    # Wait for log stream to be created
    local max_wait=120
    local wait_time=0
    local stream_exists=false
    
    log_info "Waiting for log stream to be created (max ${max_wait}s)..."
    
    while [[ $wait_time -lt $max_wait ]]; do
        if $aws_cmd logs describe-log-streams \
            --log-group-name "$log_group" \
            --log-stream-name-prefix "$log_stream_name" \
            --query 'logStreams[0].logStreamName' \
            --output text 2>/dev/null | grep -q "$log_stream_name"; then
            stream_exists=true
            log_success "Log stream found: $log_stream_name"
            break
        fi
        
        sleep 10
        wait_time=$((wait_time + 10))
        log_info "Still waiting for log stream... (${wait_time}s elapsed)"
    done
    
    if [[ "$stream_exists" != "true" ]]; then
        log_error "Log stream not found after ${max_wait}s: $log_stream_name"
        log_info "Available log streams in $log_group:"
        $aws_cmd logs describe-log-streams --log-group-name "$log_group" --query 'logStreams[*].logStreamName' --output table 2>/dev/null || true
        return 1
    fi
    
    # Stream logs using the same approach as run-ecs-task.sh
    log_info "Streaming logs from: $log_group/$log_stream_name"
    $aws_cmd logs tail "$log_group" \
        --log-stream-names "$log_stream_name" \
        --follow \
        --format short \
        2>/dev/null || {
        log_warning "Log streaming ended or no new logs available"
        return 0
    }
}

# Main execution function
main() {
    log_info "Starting application run"
    
    # Parse command line arguments
    parse_arguments "$@"
    
    log_info "Environment: $ENVIRONMENT"
    if [[ -n "$AWS_PROFILE" ]]; then
        log_info "AWS Profile: $AWS_PROFILE"
    fi
    
    # Load environment configuration
    load_environment_config "$ENVIRONMENT"
    
    # Update SSM parameters for AWS
    update_ssm_parameters
    
    # Run application based on environment
    case "$ENVIRONMENT" in
        "local")
            run_local_container
            ;;
        "aws")
            run_aws_ecs_task
            ;;
    esac
    
    log_success "Application run completed successfully"
}

# Handle script interruption
trap 'log_error "Script interrupted"; exit 1' INT TERM

# Execute main function with all arguments
main "$@"