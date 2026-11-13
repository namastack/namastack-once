# Contributing to Namastack Once

Thank you for your interest in contributing to Namastack Once! This document provides guidelines and information for contributors.

This project is written in **Kotlin** and targets both Kotlin and Java users of Spring Boot.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
- [Architectural Decisions](#architectural-decisions)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Documentation](#documentation)
- [Issue Reporting](#issue-reporting)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Please be considerate and constructive in your interactions with other contributors.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/namastack-once.git
   cd namastack-once
   ```
3. **Add the upstream remote**:
   ```bash
   git remote add upstream https://github.com/namastack/namastack-once.git
   ```

## Development Setup

### Prerequisites

- **JDK 21** or later
- **Kotlin** knowledge (project is 100% Kotlin)
- **Gradle** (wrapper included)
- **Docker & Docker Compose** (for integration tests with databases)
- **IDE**: IntelliJ IDEA recommended (with Kotlin plugin)

### Building the Project

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run tests with coverage
./gradlew test jacocoTestReport

# Check code style
./gradlew ktlintCheck

# Fix code style issues
./gradlew ktlintFormat

# Publish to local Maven repository (for testing)
./gradlew publishToMavenLocal
```

### Project Structure

```
namastack-once/
├── namastack-once-api/               # Java/Kotlin public contracts
├── namastack-once-core/              # Transactions and annotation adapter
├── namastack-once-jdbc/              # Marker persistence and database-specific SQL
├── namastack-once-mongodb/           # Native MongoDB marker persistence and transactional cleanup
├── namastack-once-starter-jdbc/      # Spring Boot JDBC starter
├── namastack-once-starter-mongodb/   # Spring Boot MongoDB starter
├── namastack-once-bom/               # Published module version alignment
├── namastack-once-integration-tests/ # H2/JPA and real-database contracts
└── namastack-once-examples/          # Standalone example applications
```

## How to Contribute

### Types of Contributions

- **Bug fixes**: Fix issues reported in GitHub Issues
- **Features**: Implement new features (discuss first in an issue)
- **Documentation**: Improve docs, add examples, fix typos
- **Tests**: Add missing tests, improve test coverage

### Before You Start

1. **Check existing issues** to see if your contribution is already being worked on
2. **Open an issue or GitHub Discussion** to discuss significant changes before starting work
3. **Keep changes focused** - one feature or fix per pull request

## Architectural Decisions

Changes that substantially affect public APIs, persistence models, compatibility,
ordering guarantees, extension points, or multiple modules should be discussed
before implementation. Small, local, and easily reversible changes do not require
this process.

The architectural decision process is described in [GOVERNANCE.md](GOVERNANCE.md).
Record accepted decisions in the relevant issue or discussion; a separate documentation project is deferred.

In brief:

1. Open a [GitHub Discussion](https://github.com/namastack/namastack-once/discussions)
   that describes the problem, requirements, constraints, relevant alternatives,
   and proposed direction.
2. State a review period appropriate to the scope of the decision. Three to seven
   calendar days should be sufficient for most decisions.
3. Resolve the main questions and record the outcome in the issue or discussion.
4. Link the accepted decision from the implementing pull request.

The purpose of this process is to surface fundamental concerns before implementation
and to establish a clear point at which a decision has been made.

## Pull Request Process

1. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-number-description
   ```

2. **Make your changes** following our coding standards

3. **Write/update tests** for your changes

4. **Run the full test suite**:
   ```bash
   ./gradlew build
   ```

5. **Commit your changes** with a clear message:
   ```bash
   git commit -m "GH-123 Add feature: description of your changes"
   ```

   > **Note**: Always prefix your commit message with the GitHub issue number (e.g., `GH-123`).

6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Open a Pull Request** against the `main` branch

### PR Requirements

- [ ] All tests pass (`./gradlew build`)
- [ ] Code style checks pass (`./gradlew ktlintCheck`)
- [ ] New code has appropriate test coverage
- [ ] Documentation is updated if needed
- [ ] Significant architectural decisions link to the accepted discussion outcome
- [ ] Commit messages include issue number (e.g., `GH-123 Add feature...`)
- [ ] PR description explains the changes and motivation

## Coding Standards

### Kotlin Style

We use [ktlint](https://ktlint.github.io/) for code formatting.

Keep independent production classes in separate files. Keep the cases of a sealed hierarchy with
its parent type, normally as nested types, following Namastack Outbox's conventions.

### Code Quality

- Write **self-documenting code** with clear names
- Add **KDoc comments** for public APIs and callable methods on internal infrastructure helpers
- Document parameters, return values, and relevant failure semantics; keep private helper comments focused on invariants
- Keep functions **small and focused**
- Prefer **immutability** where possible
- Use **meaningful variable names**

### Example KDoc

```kotlin
/**
 * Executes the action in a new Once-owned local transaction or returns a duplicate.
 *
 * @param T Type of the newly computed business value.
 * @param request Stable scope/key identity and retention policy.
 * @param action Synchronous work returning a fresh Executed result.
 * @return A flat Executed or Duplicate; never a stored response replay.
 * @since 0.1.0
 */
fun <T> execute(request: IdempotencyRequest, action: Supplier<IdempotencyResult<T>>): IdempotencyResult<T>
```

## Testing Guidelines

### Test Structure

- **Unit tests**: `src/test/kotlin/` in each module
- **Integration tests**: `namastack-once-integration-tests` module
- Use **descriptive test names** that explain the scenario
- Keep test fixtures, Spring configurations, test handlers, and entities with the test that owns them,
  normally as nested classes in that test.
- Extract a shared fixture only when sharing improves readability. Possible reuse alone does not
  justify a separate file; a small local fixture can be clearer even when another test needs similar setup.

### Test Naming Convention

```kotlin
@Test
fun `creates record for single handler`() { ... }

@Test
fun `retries failed record with exponential backoff`() { ... }

@Test
fun `does not process record before next retry time`() { ... }
```

For PostgreSQL/MySQL/MariaDB/Oracle/SQL Server contract tests, run:

```bash
./gradlew :namastack-once-integration-tests:test -PdatabaseTests=true
```

This command requires Docker and must not silently skip a missing daemon. Public and infrastructure
classes have accurate KDocs with `@since 0.1.0`; file organization follows the production/test rules above.
Never add automatic schema creation; application migrations own DDL.

### Test Requirements

- All new features must have tests
- Bug fixes should include a test that reproduces the bug
- Aim for **high coverage** of business logic
- Use **MockK** for mocking in Kotlin

### Running Specific Tests

```bash
# Run tests for a specific module
./gradlew :namastack-once-core:test

# Run a specific test class
./gradlew test --tests "io.namastack.once.IdempotencyKeyHasherTest"

# Run integration tests
./gradlew :namastack-once-integration-tests:test
```

## Documentation

### When to Update Docs

- Adding new public APIs
- Changing existing behavior
- Adding new configuration options
- Adding new features

### Documentation Locations

- **KDoc**: In-code documentation for APIs
- **README.md**: Project overview and quick start
- A separate documentation site is deferred from the initial release.
- **Example READMEs**: In each example project

## Issue Reporting

### Bug Reports

When reporting a bug, please include:

1. **Description**: Clear description of the issue
2. **Steps to reproduce**: Minimal steps to trigger the bug
3. **Expected behavior**: What you expected to happen
4. **Actual behavior**: What actually happened
5. **Environment**: Java version, Spring Boot version, database type
6. **Code sample**: Minimal code that reproduces the issue
7. **Stack trace**: Full error stack trace if applicable

### Feature Requests

When requesting a feature:

1. **Use case**: Describe your use case
2. **Proposed solution**: How you envision the feature working
3. **Alternatives**: Other solutions you've considered
4. **Additional context**: Any other relevant information

## Questions?

- Open a [GitHub Discussion](https://github.com/namastack/namastack-once/discussions) for questions
- Check existing issues and discussions first
- Join the community and help others!

---

Thank you for contributing to Namastack Once! Your contributions help make this library better for everyone. 🎉
