# Security Policy

## Supported Versions

The initial release line is 0.1.x. This source checkout is pre-release; support for that line starts
when 0.1.0 is published. Run the latest available patch in the supported line.

## Reporting a Vulnerability

Do not open a public issue for security vulnerabilities. Use
[GitHub private vulnerability reporting](https://github.com/namastack/namastack-once/security/advisories/new)
or contact the maintainer at info@rolandbeisel.de if private reporting is not yet available.

Idempotency keys may be sensitive. Once persists their hash and the operation name, not the raw key
or payload. Application logs and business tables remain the application's responsibility. A plain
SHA-256 key digest is not encryption and does not protect predictable key spaces from guessing.
