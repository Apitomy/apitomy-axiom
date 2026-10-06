# Contributing to Apitomy Axiom

Thank you for your interest in contributing to Apitomy Axiom! We welcome contributions from the community.

## Ways to Contribute

- **Bug Reports**: Found a bug? Please open an issue with details about the problem and how to reproduce it.
- **Feature Requests**: Have an idea for a new feature? Open an issue to discuss it with the maintainers.
- **Code Contributions**: Submit pull requests for bug fixes or new features.
- **Documentation**: Help improve documentation, examples, or tutorials.
- **Testing**: Help test new features or bug fixes.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally
3. **Create a branch** for your changes: `git checkout -b feature/my-feature`
4. **Make your changes** following our coding standards
5. **Test your changes** thoroughly
6. **Commit your changes** with clear, descriptive commit messages
7. **Push to your fork** and submit a pull request

## Development Setup

See the [README](README.md) for installation and setup instructions.

### Building and Testing

```bash
# Build the backend and run all Java tests
./build.sh

# Build the backend and the UI, running the UI unit tests (Vitest) as part of the build
./build.sh -Pui

# Run the UI unit tests (Vitest) on their own
cd ui && npm install && npm test

# Build the UI on its own
cd ui && npm run build
```

## Coding Standards

- Follow the existing code style in the project
- Code is formatted and linted with [Biome](https://biomejs.dev/)
- Run `npm test` before committing to ensure the UI tests pass
- Write clear, descriptive commit messages
- Add tests for new features when applicable
- Update documentation when adding or changing features

## Pull Request Process

1. **Update documentation** if your changes affect user-facing behavior
2. **Ensure all tests pass** and linting succeeds
3. **Update the README** if necessary with details of changes to the interface
4. **Reference related issues** in your PR description (e.g., "Fixes #123")
5. **Be responsive** to review feedback and questions

### Pull Request Guidelines

- Keep PRs focused on a single feature or bug fix
- Include a clear description of what the PR does and why
- Link to any related issues
- Ensure the PR passes all CI checks (linting, building)
- Update CHANGELOG.md if your changes are user-facing

## Code Review

All submissions require review before being merged. We use GitHub pull requests for this purpose.

## Commit Message Format

We use conventional commit messages for clarity and automated changelog generation:

- `feat: add new feature`
- `fix: resolve bug in X`
- `docs: update documentation`
- `refactor: restructure code without changing behavior`
- `test: add or update tests`
- `chore: update dependencies or tooling`

## Reporting Bugs

When reporting bugs, please include:

- A clear, descriptive title
- Detailed steps to reproduce the issue
- Expected behavior vs. actual behavior
- Your environment (Node.js version, OS, etc.)
- Relevant logs or error messages
- Configuration files (with sensitive data removed)

## Suggesting Features

Feature requests are welcome! Please:

- Check existing issues to avoid duplicates
- Clearly describe the feature and its use case
- Explain how it would benefit Apitomy Axiom users
- Be open to discussion and feedback

## Questions?

If you have questions about contributing, feel free to:

- Open a discussion on GitHub
- Ask in an existing issue or PR
- Reach out to the Apitomy community

## License

By contributing to Apitomy Axiom, you agree that your contributions will be licensed under the Apache License 2.0.

---

Thank you for contributing to Apitomy Axiom!
