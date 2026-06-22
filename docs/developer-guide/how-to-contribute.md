# How to Contribute

This guide covers the development workflow, coding conventions, and pull request
process for contributing to Axiom.

For a quick overview of ways to get involved, see the
[Getting Started > How to Contribute](../getting-started/contributing.md) page.

---

## Development Setup

1. **Fork** the repository on GitHub
2. **Clone** your fork:
   ```bash
   git clone https://github.com/your-username/apitomy-axiom.git
   cd apitomy-axiom
   ```
3. **Start development mode**:
   ```bash
   ./dev.sh
   ```
4. Open `http://localhost:9191` and verify the UI loads

See the [Building Axiom](building-axiom.md) guide for prerequisites and build details.

---

## Code Organization

When making changes, use this guide to find the right module:

| Change | Module |
|--------|--------|
| REST API endpoint | `common/api` (OpenAPI spec) + `app/rest/` (implementation) |
| Domain entity or lifecycle | `core/` |
| Manager behavior | `manager/` |
| Claude Code engine/actor | `actors/claude-code/` |
| OpenCode engine/actor | `engine/opencode/` |
| GitHub polling | `events/github/` |
| Jira polling | `events/jira/` |
| Pipeline orchestration | `app/` (PipelineOrchestrator, TaskExecutionService, etc.) |
| UI page or component | `ui/src/pages/` or `ui/src/components/` |
| API client functions | `ui/src/config/api.ts` |
| Database migration | `app/src/main/resources/db/migration/` |

---

## Coding Conventions

### Java (Backend)

- Follow existing Quarkus patterns: `@ApplicationScoped`, `@Inject`,
  `@ConfigProperty`, `@Scheduled`
- Use Panache active record for entities (no separate DAO/repository layer)
- Use builders for configuration objects (`AiEngineConfig.Builder`, etc.)
- Async work returns `CompletableFuture`
- Keep transactional boundaries small — one `@Transactional` method per database
  operation

### TypeScript (Frontend)

- Code is formatted and linted with [Biome](https://biomejs.dev/)
- Run `npm run lint` before committing
- Use PatternFly 6 components — don't introduce other UI libraries
- API calls go in `ui/src/config/api.ts`
- Pages go in `ui/src/pages/`, reusable components in `ui/src/components/`

---

## Commit Message Format

Use conventional commits:

| Prefix | Usage |
|--------|-------|
| `feat:` | New feature |
| `fix:` | Bug fix |
| `docs:` | Documentation only |
| `refactor:` | Code restructuring without behavior change |
| `test:` | Adding or updating tests |
| `chore:` | Dependencies, tooling, CI |

Examples:

```
feat: add Jira event source polling
fix: resolve timeout in Claude Code subprocess
docs: add extending-axiom developer guide
```

---

## Pull Request Workflow

1. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/my-feature
   ```
2. **Make your changes** following the conventions above
3. **Test locally**:
   - `mvn test` — run backend unit tests
   - `npm run lint` — check frontend code quality
   - `./dev.sh` — verify the change works end-to-end
4. **Commit** with a conventional commit message
5. **Push** and open a pull request against `main`
6. **Describe** what the PR does and why, and reference related issues (e.g.
   "Fixes #42")

### PR Guidelines

- Keep PRs focused on a single feature or fix
- Update documentation if the change affects user-facing behavior
- Ensure CI checks pass (build, lint)
- Be responsive to review feedback

All submissions require code review before merging.

---

## Testing

### Unit Tests

Standard Maven tests — run with `mvn test` from the project root or a specific module
directory.

### Integration Tests

Some tests require a running AI engine CLI. These are gated behind an environment
variable:

```bash
AXIOM_CLAUDE_TESTS=true mvn test
```

This requires the `claude` CLI on your PATH and a valid `ANTHROPIC_API_KEY`.

### Frontend Linting

```bash
cd ui
npm run lint          # Check for issues
npm run lint:fix      # Auto-fix what Biome can
```

---

## Documentation

The documentation site uses [MkDocs](https://www.mkdocs.org/) with the
[Material for MkDocs](https://squidfunk.github.io/mkdocs-material/) theme.

### Local Preview

```bash
./serve-docs.sh
```

This starts a local server at `http://localhost:8000` with live reload.

### Adding a Page

1. Create a Markdown file in the appropriate `docs/` subdirectory
2. Add a nav entry in `mkdocs.yml`
3. Verify the build: `python3 -m mkdocs build --strict`
