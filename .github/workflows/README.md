# GitHub Workflows

GitHub Actions workflows for Apitomy Axiom.

## Verify Build (`verify.yaml`)

**Trigger:** pull requests to `main` and pushes to `main`.

- **Verify Build** — `./build.sh` with Java 25 and Node.js 24: builds every module and runs the backend tests,
  including the bundled MCP servers' Node tests.
- **UI Build Check** — `npm ci`, `npm run build` (TypeScript type check and Vite build) and `npm test` (Vitest)
  in `ui/`.

## Release (`release.yaml`)

**Trigger:** manual (Actions → Release → Run workflow). There are no inputs: the version comes from the
`-SNAPSHOT` version in `pom.xml`.

**Secrets:**

- `ACCESS_TOKEN` (required): a token that can push to `main` and create releases.
- `SLACK_GENERAL_NOTIFICATION_WEBHOOK`, `SLACK_IMPORTANT_NOTIFICATION_WEBHOOK`, `SLACK_USER_MAP` (optional):
  organization secrets for the Slack notification, sent with
  [notify-slack](https://github.com/Apicurio/apicurio-github-actions/tree/main/notify-slack).

**Process:**

1. Release version = the current version without `-SNAPSHOT` (e.g. `3.0.0-SNAPSHOT` → `3.0.0`).
2. `scripts/set-version.sh <version>` sets it everywhere: Maven modules, the OpenAPI contract, the UI package and
   the bundled MCP servers.
3. Release notes: `docs/release-notes/v<version>.md` when it exists, otherwise GitHub's generated notes.
4. `./build-release.sh` builds the application with the UI and runs all tests.
5. The uber-jar is renamed to `apitomy-axiom-<version>.jar`.
6. The release commit and the `v<version>` tag are pushed only after every step above has succeeded.
7. A **draft** GitHub Release is created with the notes and the jar. A maintainer reviews and publishes it;
   publishing it triggers **Update Website**.
8. `scripts/set-version.sh` sets the next development version (`<major>.<minor>.<patch+1>-SNAPSHOT`), which is
   committed and pushed.
9. A Slack notification is sent: success to the general channel, failure to the important one, mentioning
   whoever ran the release.

**Before releasing:**

- Write `docs/release-notes/v<version>.md` and merge it to `main`.
- Optionally run `./build-release.sh` locally as a dry run.
- For a minor or major release, set the target version first, e.g. `scripts/set-version.sh 3.1.0-SNAPSHOT`,
  and merge that.

## Publish Documentation (`publish-docs.yml`)

**Trigger:** pushes to `main`. Builds the MkDocs site and pushes it to the website repository.

## Update Website (`update-website.yaml`)

**Trigger:** a published GitHub Release. Updates the project website for the release; posts to Slack only
when the update fails.

## Local Testing

```bash
./build.sh                 # backend build and tests
cd ui && npm run build && npm test
./build-release.sh         # full release build with the UI
```
