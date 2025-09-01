# Security Policy

## Environment Configuration

### ⚠️ IMPORTANT: Never commit sensitive credentials to version control

This repository contains template files for configuration. Follow these security practices:

### Safe Files (included in repository):

- ✅ `.env.local` - Contains only dummy LocalStack credentials (`test`/`test`)
- ✅ `.env.template` - Template with placeholder values
- ✅ All source code and CloudFormation templates

### Sensitive Files (excluded from repository):

- ❌ `.env.aws` - Contains your real AWS profile and configuration
- ❌ `.env.prod` - Production environment configuration
- ❌ `.env.staging` - Staging environment configuration
- ❌ Any files with real AWS credentials, account IDs, or sensitive data

## Setup Instructions

1. **Copy the template:**

   ```bash
   cp .env.template .env.aws
   ```

2. **Edit `.env.aws` with your values:**

   ```bash
   # Replace with your actual AWS profile
   AWS_PROFILE=your-aws-profile-name

   # Use your preferred region
   AWS_REGION=us-east-1

   # Customize other settings as needed
   ```

3. **Verify `.env.aws` is gitignored:**
   ```bash
   git status
   # .env.aws should NOT appear in untracked files
   ```

## AWS Security Best Practices

### IAM Permissions

- Use least-privilege IAM policies
- Create dedicated IAM roles for the load testing application
- Enable CloudTrail for audit logging
- Use temporary credentials when possible

### DynamoDB Security

- Enable encryption at rest
- Use VPC endpoints for private communication
- Monitor access patterns with CloudWatch
- Set up appropriate backup and recovery procedures

### ECS Security

- Use private subnets for ECS tasks
- Enable container insights for monitoring
- Regularly update container images
- Use secrets manager for sensitive configuration

## Reporting Security Issues

If you discover a security vulnerability, please:

1. **Do NOT** create a public GitHub issue
2. Email the maintainer privately
3. Include detailed information about the vulnerability
4. Allow time for the issue to be addressed before public disclosure

## Security Checklist

Before deploying to production:

- [ ] All sensitive data is in `.env.aws` (not committed)
- [ ] IAM roles follow least-privilege principle
- [ ] DynamoDB encryption is enabled
- [ ] VPC endpoints are configured
- [ ] CloudWatch monitoring is enabled
- [ ] Backup procedures are in place
- [ ] Security groups restrict access appropriately
- [ ] Container images are from trusted sources
- [ ] Regular security updates are planned
