# ADR-0001: Request Fingerprinting

- **Status:** Proposed
- **Date:** 2026-11-20
- **Decision owner:** @Alek96
- **Discussion:** TBD
- **Supersedes:** —

## Context

Once identifies a protected operation by its scope and idempotency key. Before the
operation runs, Once reserves a marker holding the SHA-256 digest of scope and key in the
same local transaction as the business writes. If a marker for that identity already
exists, the invocation is reported as a `Duplicate` and the operation is skipped.

Duplicate detection looks only at the identity. Once cannot tell a true retry from a
different request that reuses the same key. Both are skipped, and the second request's
content is silently dropped. Typical causes include:

- an HTTP client that reuses an idempotency key for a modified request body,
- a message producer that assigns the same message ID to different payloads,
- a corrected event that is published again under its original ID,
- several producers whose key spaces collide within one scope.

In an HTTP API, the caller gets a success response for work that was never done. In a
message listener, the message is acknowledged and its content is lost without a trace.
The IETF `Idempotency-Key` HTTP header draft therefore recommends rejecting a reused
key with a different payload instead of treating it as a duplicate.

To detect key reuse, Once must store a compact representation of the request content,
a fingerprint, with the marker. It must also compare that fingerprint with the one
computed for a later invocation with the same identity.

Computing a fingerprint means turning request content into bytes, and most values have
more than one byte representation. Two serializations of the same business content can
differ, and so can their digests:

- `BigDecimal` values `19.9` and `19.90` are numerically equal but differ in scale,
  which depends on where the value came from (JSON, a database column, arithmetic).
- `double` arithmetic is order-dependent (`0.1 + 0.2 != 0.3`), and the output of
  `Double.toString` differs between Java 17 and later versions for some values.
- Temporal values can be serialized at different precisions.
- Maps and sets have no defined iteration order unless one is imposed.
- General-purpose serializers (JSON, Java serialization) depend on library versions,
  configuration and class structure, for example field order or newly added fields
  with default values.

Whether two such representations mean the same request is a business decision, not a
technical one. A fingerprint that gets it wrong reports false mismatches for valid
retries, or misses real differences.

Fingerprinting affects several parts of the project at once:

- **Public API:** the result type, the `@Idempotent` annotation and the programmatic
  request gain new concepts.
- **Persistence model:** the marker gains a column in every supported SQL dialect and
  a field in MongoDB.
- **Compatibility:** stored fingerprints must stay comparable across instances, JVM
  versions, Once releases and changes to application code. A comparison that changes
  between two deployments reports false mismatches for valid retries.

This ADR records how fingerprints are declared, computed, encoded, stored and compared.

## Requirements and Constraints

### Behavior

- Fingerprinting is opt-in per declaration. Declarations without a fingerprint behave
  exactly as before, and don't pay for any extra database work.
- A fingerprint can cover the whole content of an argument or selected parts of it,
  and it can combine several arguments.
- Annotated methods and the programmatic `IdempotencyOperations` API both support
  fingerprints, from Java and from Kotlin.
- A reused key with different content is reported as a distinct outcome, separate from
  a `Duplicate`. Methods without a return value also need a defined behavior.
- The same content always produces the same fingerprint, on every instance, JVM version
  and platform.
- Different content must not produce the same fingerprint because of an ambiguous
  encoding.
- Values with several possible representations are compared the way the application
  defines, not according to an implicit rule chosen by Once.

### To Be Considered

These capabilities are desirable, but not required for the first version. The design
must not rule them out.

- **Fingerprint versions:** applications can change a fingerprint definition without
  causing false mismatches for markers that are still within their retention period.
- **Keyed digests (HMAC):** applications can keep low-entropy content (amounts,
  customer IDs) from being recoverable by brute force from the marker table.

### Constraints

- The project is at version 0.1.0. Public API and persistence schema can still change
  without a migration path.
- Java 17 is the baseline, and tests run on Java 17, 21 and 25. The fingerprint must
  not depend on behavior that differs between these versions.
- The schema change must be applied to all supported SQL dialects (H2, PostgreSQL,
  MySQL, MariaDB, SQL Server, Oracle) and to MongoDB.
- Markers are reserved before the operation runs, so the fingerprint must be computable
  from the invocation's input alone.
- Raw request content is never persisted, the same as for raw idempotency keys.

### Non-goals

- Storing or replaying the result of the first invocation. That is a separate decision.
- Reporting which part of the content differs on a mismatch.
- Detecting changes to a fingerprint definition automatically.
