#!/bin/bash

# LocalStack Setup Script
# This script sets up DynamoDB tables and SSM parameters using values from .env.local

set -e

echo "Starting LocalStack setup..."

# Function to load environment variables from .env.local
load_env_file() {
    local env_file=".env.local"
    
    if [ ! -f "$env_file" ]; then
        echo "Error: $env_file not found!"
        exit 1
    fi
    
    echo "Loading environment variables from $env_file..."
    
    # Unset AWS_PROFILE to prevent conflicts with LocalStack
    if [[ -n "${AWS_PROFILE:-}" ]]; then
        echo "Unsetting AWS_PROFILE (was: $AWS_PROFILE) for LocalStack compatibility"
        unset AWS_PROFILE
    fi
    
    # Export variables from .env.local, ignoring comments and empty lines
    while IFS= read -r line || [ -n "$line" ]; do
        # Skip comments and empty lines
        if [[ "$line" =~ ^[[:space:]]*# ]] || [[ -z "${line// }" ]]; then
            continue
        fi
        
        # Export the variable
        if [[ "$line" =~ ^[[:space:]]*([^=]+)=(.*)$ ]]; then
            var_name="${BASH_REMATCH[1]// /}"
            var_value="${BASH_REMATCH[2]}"
            export "$var_name"="$var_value"
            echo "Loaded: $var_name"
        fi
    done < "$env_file"
}

# Function to wait for LocalStack to be ready
wait_for_localstack() {
    echo "Waiting for LocalStack to be ready..."
    local max_attempts=30
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        if curl -f -s "http://localhost:4566/_localstack/health" > /dev/null 2>&1; then
            echo "LocalStack is ready!"
            return 0
        fi
        
        echo "Attempt $attempt/$max_attempts: LocalStack not ready yet, waiting..."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo "Error: LocalStack failed to become ready after $max_attempts attempts"
    exit 1
}

# Function to create DynamoDB table
create_dynamodb_table() {
    echo "Creating DynamoDB table: $TABLE_NAME"
    
    # Check if table already exists
    if aws dynamodb describe-table --table-name "$TABLE_NAME" --endpoint-url "http://localhost:4566" > /dev/null 2>&1; then
        echo "Table $TABLE_NAME already exists, skipping creation"
        return 0
    fi
    
    aws dynamodb create-table \
        --table-name "$TABLE_NAME" \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
        --provisioned-throughput \
            ReadCapacityUnits="$READ_CAPACITY_UNITS",WriteCapacityUnits="$WRITE_CAPACITY_UNITS" \
        --endpoint-url "http://localhost:4566"
    
    echo "DynamoDB table '$TABLE_NAME' created successfully"
}

# Function to create SSM parameters
create_ssm_parameters() {
    echo "Creating SSM parameters..."
    
    local ssm_prefix="/local/dynamodb-load-test"
    
    # Array of parameter name-value pairs
    declare -a parameters=(
        "table-name:$TABLE_NAME"
        "concurrency-limit:$CONCURRENCY_LIMIT"
        "total-items:$TOTAL_ITEMS"
        "max-concurrency-percentage:$MAX_CONCURRENCY_PERCENTAGE"
        "duplicate-percentage:$DUPLICATE_PERCENTAGE"
        "cleanup-after-test:$CLEANUP_AFTER_TEST"
        "read-capacity-units:$READ_CAPACITY_UNITS"
        "write-capacity-units:$WRITE_CAPACITY_UNITS"
        "aws-region:$AWS_REGION"
        "environment:$ENVIRONMENT"
    )
    
    for param in "${parameters[@]}"; do
        IFS=':' read -r param_name param_value <<< "$param"
        full_param_name="$ssm_prefix/$param_name"
        
        echo "Creating SSM parameter: $full_param_name = $param_value"
        
        # Use --overwrite to update if parameter already exists
        aws ssm put-parameter \
            --name "$full_param_name" \
            --value "$param_value" \
            --type "String" \
            --overwrite \
            --endpoint-url "http://localhost:4566"
    done
    
    echo "SSM parameters created successfully"
}

# Function to verify setup
verify_setup() {
    echo "Verifying setup..."
    
    # Verify DynamoDB table
    echo "Checking DynamoDB table..."
    aws dynamodb describe-table \
        --table-name "$TABLE_NAME" \
        --endpoint-url "http://localhost:4566" \
        --query 'Table.{TableName:TableName,Status:TableStatus,ReadCapacity:ProvisionedThroughput.ReadCapacityUnits,WriteCapacity:ProvisionedThroughput.WriteCapacityUnits}' \
        --output table
    
    # Verify SSM parameters
    echo "Checking SSM parameters..."
    aws ssm get-parameters-by-path \
        --path "/local/dynamodb-load-test" \
        --endpoint-url "http://localhost:4566" \
        --query 'Parameters[].{Name:Name,Value:Value}' \
        --output table
    
    echo "Setup verification completed successfully!"
}

# Main execution
main() {
    echo "========================================="
    echo "LocalStack DynamoDB & SSM Setup Script"
    echo "========================================="
    
    # Load environment variables
    load_env_file
    
    # Wait for LocalStack to be ready
    wait_for_localstack
    
    # Create DynamoDB table
    create_dynamodb_table
    
    # Create SSM parameters
    create_ssm_parameters
    
    # Verify setup
    verify_setup
    
    echo "========================================="
    echo "LocalStack setup completed successfully!"
    echo "========================================="
}

# Run main function
main "$@"