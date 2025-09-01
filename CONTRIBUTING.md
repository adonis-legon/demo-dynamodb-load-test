# Contributing to DynamoDB Load Test

Thank you for your interest in contributing to this project! This document provides guidelines for contributing.

## Getting Started

1. **Fork the repository**
2. **Clone your fork:**

   ```bash
   git clone https://github.com/your-username/dynamodb-load-test.git
   cd dynamodb-load-test
   ```

3. **Set up your environment:**

   ```bash
   # Copy template and configure for your AWS account
   cp .env.template .env.aws
   # Edit .env.aws with your AWS profile and settings
   ```

4. **Install dependencies:**
   ```bash
   # Java 21 and Maven are required
   mvn clean compile
   ```

## Development Workflow

### Local Development

```bash
# Test locally with LocalStack
./scripts/setup-localstack.sh
./scripts/build.sh local
./scripts/run.sh local --logs
```

### AWS Testing

```bash
# Build and test on AWS
./scripts/build.sh aws --aws-profile your-profile
./scripts/deploy-stack.sh aws create --aws-profile your-profile
./scripts/run.sh aws --aws-profile your-profile --wait --logs
```

## Code Standards

### Java Code

- Follow standard Java conventions
- Use meaningful variable and method names
- Add JavaDoc comments for public methods
- Include unit tests for new functionality
- Ensure thread safety for concurrent operations

### Scripts

- Use bash best practices
- Include error handling with `set -e`
- Add comments for complex logic
- Test scripts in both local and AWS environments

### Configuration

- Use environment variables for configuration
- Provide sensible defaults
- Document all configuration options
- Never hardcode credentials or account-specific values

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
# Local integration tests
./scripts/run.sh local --logs

# AWS integration tests (requires AWS setup)
./scripts/run.sh aws --aws-profile your-profile --wait
```

### Load Testing

Test different scenarios:

- Various concurrency levels (5, 10, 20, 50)
- Different item counts (100, 1000, 10000)
- Different duplicate percentages (0%, 5%, 15%, 25%)

## Security Guidelines

### Never Commit Sensitive Data

- AWS credentials or profiles
- Account IDs or ARNs
- Production configuration values
- API keys or secrets

### Use Templates

- Update `.env.template` for new configuration options
- Test with dummy values in `.env.local`
- Document security implications of new features

## Pull Request Process

1. **Create a feature branch:**

   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes:**

   - Write clean, documented code
   - Add tests for new functionality
   - Update documentation as needed

3. **Test thoroughly:**

   - Run unit tests: `mvn test`
   - Test locally: `./scripts/run.sh local`
   - Test on AWS (if applicable)

4. **Commit with clear messages:**

   ```bash
   git commit -m "feat: add support for custom retry policies"
   ```

5. **Push and create PR:**

   ```bash
   git push origin feature/your-feature-name
   ```

6. **PR Requirements:**
   - Clear description of changes
   - Reference any related issues
   - Include test results
   - Update documentation if needed

## Issue Reporting

### Bug Reports

Include:

- Environment details (local/AWS, Java version, etc.)
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs or error messages
- Configuration (sanitized of sensitive data)

### Feature Requests

Include:

- Use case description
- Proposed solution
- Alternative approaches considered
- Impact on existing functionality

## Code Review Guidelines

### For Reviewers

- Check for security issues
- Verify test coverage
- Ensure documentation is updated
- Test the changes locally if possible
- Provide constructive feedback

### For Contributors

- Respond to feedback promptly
- Make requested changes
- Update tests and documentation
- Rebase if needed to keep history clean

## Release Process

1. Update version in `pom.xml`
2. Update `CHANGELOG.md`
3. Create release tag
4. Update documentation
5. Test release artifacts

## Questions?

- Open an issue for questions about the codebase
- Check existing issues and documentation first
- Be specific about your environment and use case

Thank you for contributing! 🚀
