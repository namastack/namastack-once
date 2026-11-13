# Releasing Namastack Once

The first release is **0.1.0**. The root Gradle build is the release-version source of truth; normal
builds add `-SNAPSHOT`, while `-Prelease=true` selects immutable release coordinates.

## Prepare

For later releases, run **Prepare Release** with a version without the `v` prefix, such as `0.1.1`.
It tests release tooling, updates the root version and shared example catalog, and opens a
`release/<version>` pull request. Coordinate user documentation updates in the separate documentation
repository as part of each release.
Review and merge after Gradle and example smoke-test workflows pass. The initial 0.1.0 version is
already set, so its first publication does not require a version-only preparation pull request.

## Publish

Run **Publish Release** manually on main with the matching stable version. It verifies version/tag
state, runs the Docker-enabled database contract and build, creates an annotated `v<version>` tag,
publishes signed artifacts to Maven Central, and creates a GitHub release. Normal merges never
create release tags. Main CI may publish development snapshots, following Outbox's convention.

**Publish Existing Tag** validates a tag and requires successful Gradle and example workflows for
that exact commit. Run it in dry-run mode before retrying publication. Never overwrite a published
version or move a release tag to work around an interrupted publication; investigate the remote
state first. Release preparation labels are excluded from generated release notes.

## Repository setup

Configure the same signing and Central credentials as Outbox:

- `ORG_GRADLE_PROJECT_signingInMemoryKey`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyId`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`
- `ORG_GRADLE_PROJECT_mavenCentralUsername`
- `ORG_GRADLE_PROJECT_mavenCentralPassword`
- `CODECOV_TOKEN` for coverage uploads

Allow Actions to create release pull requests, and create the `release` label used by the workflow.
The nine published artifacts use the group `io.namastack`:

- `namastack-once-api`
- `namastack-once-core`
- `namastack-once-jdbc`
- `namastack-once-mongodb`
- `namastack-once-starter-jdbc`
- `namastack-once-starter-mongodb`
- `namastack-once-observability-api`
- `namastack-once-observability`
- `namastack-once-bom`

The integration-tests module and standalone example applications are not release artifacts.

Local checks:

```bash
./gradlew clean build -Prelease=true -PdatabaseTests=true
node --test scripts/*.test.mjs
```

Database admission requires passing real database tests, not just compiling SQL or passing H2.
There is no automatic production schema migration in any release workflow or library module.
