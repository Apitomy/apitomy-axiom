# GitHub Workflows

This directory contains GitHub Actions workflows for the Apitomy Axiom project.

## Workflows

### Release (`release.yaml`)

Creates a new release of Apitomy Axiom, including building and publishing Docker images.

**Trigger**: Manual workflow dispatch

**Inputs**:
- `release-version`: The version to release (e.g., `1.0.0`)

**Process**:
1. Updates `package.json` with the release version
2. Builds and tests the project
3. Commits and pushes version changes to the main branch
4. Builds multi-architecture Docker images (amd64, arm64)
5. Pushes images to Docker Hub and Quay.io with tags:
   - `<version>` (e.g., `1.0.0`)
   - `latest`
6. Creates a GitHub release with auto-generated release notes
7. Sends a Slack notification: success to the general channel, failure to the important one

**Required Secrets**:
- `ACCESS_TOKEN`: GitHub personal access token with repo and packages permissions
- `DOCKERHUB_USERNAME`: Docker Hub username
- `DOCKERHUB_PASSWORD`: Docker Hub password/token
- `QUAY_USERNAME`: Quay.io username
- `QUAY_PASSWORD`: Quay.io password/token
- `SLACK_GENERAL_NOTIFICATION_WEBHOOK`, `SLACK_IMPORTANT_NOTIFICATION_WEBHOOK`, `SLACK_USER_MAP`: organization secrets for the Slack notification, sent with [notify-slack](https://github.com/Apicurio/apicurio-github-actions/tree/main/notify-slack) (optional)

**Usage**:

1. Go to Actions → Release
2. Click "Run workflow"
3. Enter the release version (e.g., `1.0.0`)
4. Click "Run workflow"

**Pre-release Versions**:

The workflow automatically detects pre-release versions by checking for:
- `RC` (release candidate)
- `alpha`
- `beta`

Example: `1.0.0-RC1` will be marked as a pre-release.

**Registry Locations**:

After release, images are available at:
- Docker Hub: `docker.io/apitomy/apitomy-axiom:<version>`
- Quay.io: `quay.io/apitomy/apitomy-axiom:<version>`

**Multi-Architecture Support**:

Images are built for:
- `linux/amd64`
- `linux/arm64`

## Local Testing

To test the Docker build locally before releasing:

```bash
# Build locally
cd docker/build
./build.sh 1.0.0-test

# Test the image
docker run --rm apitomy/apitomy-axiom:1.0.0-test node --version
```

## Troubleshooting

### Version mismatch error
If the workflow fails with a version mismatch, ensure:
- The version number in `package.json` matches the release tag
- You're running the workflow from the correct branch

### Docker push fails
Check that:
- Docker Hub and Quay.io credentials are correctly configured in repository secrets
- The `ACCESS_TOKEN` has `packages:write` permissions
- Repository name and organization match the configured registries

### Build fails
- Review the build logs for specific errors
- Test the build locally first using the build script
- Ensure all dependencies are correctly specified in `package.json`
