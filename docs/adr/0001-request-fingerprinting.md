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

## Considered Options

The options are grouped by the question they answer. Each group is decided
independently in the Decision section.

### A. Defining the Fingerprint Content

#### Option A1: SpEL expressions

`@Idempotent(fingerprint = "{#cmd.customerId, #cmd.total}")`, the same mechanism as
`key`.

- Smallest API, and familiar from the key expression.
- Selection lives in strings: typos and renamed properties fail only at runtime, and
  refactoring tools don't see them. Values are still serialized by Once (see group E).

#### Option A2: Annotations on DTO fields

Fields marked `@IdempotencyFingerprint` form the fingerprint.

- Type-safe and refactoring-friendly. The DTO documents what makes two requests equal.
- Couples DTOs, often in shared contract modules, to the Once API. Allows only one
  definition per type, even if several scopes need different ones.

#### Option A3: DTO implements an interface

The DTO implements `IdempotencyFingerprintable` and returns its relevant components.

- Plain, compile-time checked code that is easy to test.
- Same coupling and one-definition-per-type limitation as A2.

#### Option A4: Mapper

A separate `IdempotencyFingerprintMapper<T>` turns a value into a fingerprint. Mappers
are classes or Spring beans.

- Type-safe, testable, keeps DTOs free of Once types, allows different definitions per
  scope, and follows the pattern of `retentionPolicy`/`retentionPolicyBean`.
- Every partial fingerprint needs a mapper class, which is more ceremony for simple
  cases.

### B. Attaching Mappers to a Declaration

Applies to mappers (A4).

#### Option B1: Single mapper on `@Idempotent`

`@Idempotent(fingerprint = X::class)`, matched to the parameter of type `T`.

- Simple, and consistent with `retentionPolicy`.
- Only one argument can contribute. Changing it to an array later breaks Kotlin source.

#### Option B2: Mapper array on `@Idempotent`

`@Idempotent(fingerprint = [A::class, B::class])`, each mapper matched to one parameter
by type and combined in array order.

- Several arguments, an explicit combination order independent of parameter order, and
  an empty default instead of a sentinel class. Java accepts a single class without
  braces.
- Cannot express two parameters of the same type, mappers whose type matches every
  parameter, or bean names.

#### Option B3: Separate single and array attributes

`fingerprint` and `fingerprints` side by side.

- Each reads naturally for its case.
- Two ways to say the same thing, plus an invalid state when both are set.

#### Option B4: Parameter annotations

`@IdempotencyFingerprint` on each contributing parameter, optionally with `mapper` or
`mapperBean`. Without an explicit mapper, it is resolved by type.

- Handles same-type parameters, bean names and a whole-content fallback.
- Combination order follows parameter order, so reordering parameters changes the
  fingerprint.

#### Option B5: Annotation on the DTO class

`@IdempotencyFingerprintMapper(X::class)` on the DTO selects its mapper.

- Defined once per type.
- Same coupling as A2. A mapper bean resolved by type provides the same reuse without
  touching the DTO.

#### Option B6: Mapper bean activates fingerprinting

A bean `IdempotencyFingerprintMapper<T>` enables fingerprinting for every declaration
with a parameter of type `T`.

- No annotation needed.
- Not opt-in per declaration. Adding a bean, including one from a library, changes the
  behavior of unrelated scopes.

### C. Reporting a Mismatch

#### Option C1: Exception

A mismatch throws an `IdempotencyFingerprintMismatchException` carrying the duplicate
context.

- Fits the rule that only normal outcomes are results. It maps naturally to an HTTP
  `@ExceptionHandler` or a dead-letter queue.
- Callers that want to handle mismatches as a normal case need `try`/`catch` next to
  `fold`, so one invocation has two places where outcomes are handled.

#### Option C2: Third result variant

`IdempotencyResult` gains `Mismatch<T>(context)` next to `Executed` and `Duplicate`, and
`fold` gains a third branch.

- Explicit and exhaustive: Kotlin `when` and Java `fold` force callers to handle it.
- Every `fold` call grows a third argument, including in scopes that never use
  fingerprints.
- Methods without a return value discard the result, so the mismatch needs a separate
  rule there.

#### Option C3: Flag on Duplicate

`Duplicate` gains `fingerprintMatches: Boolean`.

- No new variant, and `fold` keeps two branches.
- Easy to ignore: callers that only check for `Duplicate` treat a mismatch as success.

### D. Mismatch in Methods Without a Return Value

Applies when the outcome is a result variant (C2 or C3). Methods returning
`void`/`Unit`, typically message listeners, discard the result.

#### Option D1: Disallow fingerprints

Fingerprints are only valid on methods returning `IdempotencyResult`. Startup fails
otherwise.

- One way to report a mismatch, and nothing can be lost silently.
- Excludes the main use case for void methods: message listeners that only need
  deduplication.

#### Option D2: Always throw

Void methods throw an exception on a mismatch.

- The message is retried or dead-lettered, so changed content is never acknowledged
  silently.
- Forces strict behavior on every listener, even where skipping is acceptable.

#### Option D3: Always skip

A mismatch is treated like a duplicate.

- Simple and consistent with duplicate handling.
- The fingerprint has no effect: behavior is identical to a declaration without one.

#### Option D4: Per-declaration choice

`@Idempotent(onMismatch = SKIP | FAIL)`, combined with instrumentation (metric, tracing
event, log) for every mismatch.

- Each listener chooses between deduplication and strict protection, and mismatches
  stay visible either way.
- Adds an annotation attribute that applies only to void methods.

### E. Turning Values into Bytes

#### Option E1: General-purpose serializer

Values are serialized with a configured serializer (for example canonical JSON) and the
result is hashed.

- Works for any object with no mapper code.
- Equality rules for numbers, time and collections are implicit and depend on library
  version and configuration. Adding a field with a default value changes every
  fingerprint.

#### Option E2: Once-defined canonical encoding for many types

Once defines a canonical encoding for strings, numbers including `BigDecimal` and
floating point, temporal types, maps and sets.

- Convenient: most values can be added directly.
- Once must decide, for every application, questions such as scale, precision and key
  order. A wrong choice can only be corrected by changing the encoding.

#### Option E3: Minimal canonical encoding plus mappers

Once encodes only types with a single representation: strings, integers, booleans,
byte arrays, null, lists and nested fingerprints. Every part is framed with a type tag
and a length or count. Other types are converted by application mappers.

- Every equality decision lives in application code, where it is visible and
  versionable. The encoding is small enough to specify completely and keep stable.
- Applications write mappers for common types such as `BigDecimal` and `Instant`.

### F. Reusing Conversions Across Mappers

Applies to E3.

#### Option F1: Plain injection

Mappers share conversion helpers as ordinary injected beans.

- No new concepts, fully transparent.
- Nothing ensures that all mappers use the same rule for a type.

#### Option F2: Converter registry, writing into a builder

`IdempotencyFingerprintConverter<T>.convert(value, builder)` beans are registered per
Once instance and used by a builder that adds values of any type.

- One rule per type for the whole application, similar to `IdempotencyKeyNormalizer`.
- Converters can write arbitrary parts into a foreign builder, and a second interface
  has nearly the same role as the mapper.

#### Option F3: Mapper registry, returning a fingerprint

Mappers serve as converters: a builder from a factory bean looks up
`IdempotencyFingerprintMapper<T>` beans by type and nests their result. A mapper can
also be passed explicitly.

- One interface for top-level and part mappers, pure functions, and no converter
  chains, because mappers build with a builder that knows only built-in types.
- A converted value is encoded as a nested fingerprint, so it differs from the same
  value added inline. Changing a shared mapper changes every fingerprint that uses it.

### G. Encoding Details

#### Option G1: Integers as fixed 8 bytes

All integer types are widened to 64 bits.

- Trivial, no length field.
- A future `BigInteger` would need a separate tag, so `BigInteger(42)` and `42L` would
  differ.

#### Option G2: Integers as minimal two's complement with a length

The representation of `BigInteger.toByteArray()`.

- Covers all built-in integer types and a future `BigInteger` with one tag.
- Slightly more complex than G1.

#### Option G3: Separate tags for lists and nested fingerprints

- Switching between a list and a nested fingerprint changes the digest.
- One more tag than strictly necessary, as both have the same layout.

#### Option G4: Shared tag for lists and nested fingerprints

- One concept for groups of parts.
- A mapper that switches between the two produces the same digest, so the change goes
  unnoticed.

### H. Encoding Stability

#### Option H1: Frozen encoding

The encoding never changes, like the key hash.

- Simple, and no version marker in stored values.
- Any mistake in the encoding is permanent, and the encoding of broad types (E2) is
  hard to get right on the first attempt.

#### Option H2: Versioned encoding

Stored values carry an encoding version, and Once keeps older encoders to recompute
them.

- Mistakes can be corrected without false mismatches.
- Once maintains several encoders, and every stored value carries extra information.

#### Option H3: Minimal encoding with a reserved version tag

The encoding is kept minimal (E3) and extended only with new type tags. An encoding
version tag is reserved, with absence meaning version 1, but not implemented.

- Stable in practice, with a way back that costs nothing today.
- Relies on the minimal type set being specified correctly.

### I. Persisting the Fingerprint

#### Option I1: Included in the identity hash

The identity digest covers scope, key and fingerprint. No new column.

- No schema change.
- Different content produces a different identity, so the operation runs again. A
  mismatch can never be detected.

#### Option I2: Digest in a separate column

- Small, and no request content is stored.
- Shows that content differs, not where.

#### Option I3: Readable canonical content

- Mismatch diagnostics can show which part changed.
- Stores business and possibly personal data of unbounded size in the marker table.

#### Option I4: One digest per top-level part

- Shows which argument changed without storing content.
- Larger values and a more complex format for limited gain.

### J. Format of the Stored Value

Applies to I2.

#### Option J1: Bare digest with additional columns

Versions and key IDs, if added, get their own columns.

- No parsing, same shape as the identity digest.
- Every new concept requires a schema change in all dialects.

#### Option J2: Positional prefixes

For example `s1:<hex>` or `h1:<keyId>:<hex>`.

- Compact.
- Optional parts make positions ambiguous, and adding a part changes the format.

#### Option J3: Named tags

For example `v=2,1:k=2026-11:<hex>`, with defaults omitted and unknown tags treated as
not comparable.

- Self-describing, order-independent, and extensible without schema changes. Older
  Once versions handle values written by newer ones safely.
- Values must be parsed, and tag values must not contain separators.

### K. Changing a Fingerprint Definition

#### Option K1: Breaking change

Changing a mapper changes digests. This is documented, and applications accept false
mismatches until old markers expire.

- No additional concepts.
- Retries during a deployment can be rejected as mismatches.

#### Option K2: Manual versions, not comparable across versions

Mappers declare a version, which is stored with the digest. Different versions are not
compared.

- No false mismatches.
- Real mismatches across versions go undetected until old markers expire. Developers
  must remember to change the version.

#### Option K3: Manual versions with recomputation

As K2, but a mapper can compute older versions, and Once recomputes the fingerprint in
the stored version.

- Mismatches stay detectable across changes.
- Old mapping code must be kept until retention expires. Instances running only the old
  version still cannot compare against new rows without a two-phase rollout.

#### Option K4: Versions derived from the fingerprint's structure

- No manual work.
- Misses the most common changes, such as a different property of the same type, and
  depends on data: a null in one request and a value in another look like different
  versions, hiding real mismatches.

#### Option K5: Versions derived from mapper bytecode

- No manual work.
- Changes on every recompile or refactoring, which disables comparison after most
  deployments. Misses changes in code the mapper calls.

### L. Protecting Low-Entropy Content

#### Option L1: Plain SHA-256 only

- Nothing to configure.
- Low-entropy content can be brute-forced from a leaked marker table.

#### Option L2: HMAC in application mappers

Mappers hash their content with an application secret and add the result.

- No support needed in Once.
- No key ID is stored, so rotating the secret is a breaking change.

#### Option L3: Built-in HMAC with a stored key ID

Once computes HMAC-SHA-256 with configured keys and stores the key ID with the digest.
Old keys stay configured for verification until retention expires.

- Safe key rotation, and optional per instance.
- Secret management, and an optional key provider SPI.

#### Option L4: Built-in HMAC trying all keys

No key ID is stored. Once tries every configured key.

- No stored key ID.
- Several digests per duplicate, and a removed key turns a valid retry into a false
  mismatch.
