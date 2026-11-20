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
independently in the Decision section. Examples use Kotlin and the following command:

```kotlin
data class CreateOrder(
    val requestId: UUID,
    val customerId: String,
    val lines: List<OrderLine>,
    val total: BigDecimal,
    val requestedAt: Instant,
)
```

### A. Defining the Fingerprint Content

#### Option A1: SpEL expressions

The fingerprint is selected with a SpEL expression in `@Idempotent(fingerprint = ...)`,
the same mechanism as `key`.

- Smallest API, and familiar from the key expression.
- Selection lives in strings: typos and renamed properties fail only at runtime, and
  refactoring tools don't see them. Values are still serialized by Once (see group E).

```kotlin
@Idempotent(scope = "order.create", key = "#cmd.requestId", fingerprint = "#cmd")                    // whole
@Idempotent(scope = "order.create", key = "#cmd.requestId", fingerprint = "{#cmd.customerId, #cmd.total}") // partial
fun create(cmd: CreateOrder): IdempotencyResult<Order>
```

#### Option A2: Annotations on DTO fields

Fields marked `@IdempotencyFingerprint` form the fingerprint. The parameter carrying the
DTO is marked the same way.

- Type-safe and refactoring-friendly. The DTO documents what makes two requests equal.
- Couples DTOs, often in shared contract modules, to the Once API. Allows only one
  definition per type, even if several scopes need different ones.

```kotlin
data class CreateOrder(
    val requestId: UUID,
    @IdempotencyFingerprint val customerId: String,
    @IdempotencyFingerprint val total: BigDecimal,
    val requestedAt: Instant,                         // ignored
)

@Idempotent(scope = "order.create", key = "#cmd.requestId")
fun create(@IdempotencyFingerprint cmd: CreateOrder): IdempotencyResult<Order>
```

#### Option A3: DTO implements an interface

The DTO implements `IdempotencyFingerprintable` and returns its relevant components.

- Plain, compile-time checked code that is easy to test.
- Same coupling and one-definition-per-type limitation as A2.

```kotlin
data class CreateOrder(...) : IdempotencyFingerprintable {
    override fun fingerprintComponents() = listOf(customerId, total)
}
```

#### Option A4: Mapper

A separate `IdempotencyFingerprintMapper<T>` turns a value into an
`IdempotencyFingerprint`. Mappers are classes or Spring beans.

- Type-safe, testable, keeps DTOs free of Once types, allows different definitions per
  scope, and follows the pattern of `retentionPolicy`/`retentionPolicyBean`.
- Every partial fingerprint needs a mapper class, which is more ceremony for simple
  cases.

```kotlin
fun interface IdempotencyFingerprintMapper<in T> {
    fun map(subject: T): IdempotencyFingerprint
}

class CreateOrderFingerprint : IdempotencyFingerprintMapper<CreateOrder> {
    override fun map(subject: CreateOrder) =
        IdempotencyFingerprint.builder()
            .add(subject.customerId)
            .add(subject.total.stripTrailingZeros().toPlainString())
            .build()
}
```

### B. Attaching Mappers to a Declaration

Applies to mappers (A4).

#### Option B1: Single mapper on `@Idempotent`

`@Idempotent` takes a single mapper class in `fingerprint`. The mapper is matched to the
parameter of type `T`.

- Simple, and consistent with `retentionPolicy`.
- Only one argument can contribute. Changing it to an array later breaks Kotlin source.

```kotlin
@Idempotent(scope = "order.create", key = "#cmd.requestId", fingerprint = CreateOrderFingerprint::class)
fun create(cmd: CreateOrder): IdempotencyResult<Order>
```

#### Option B2: Mapper array on `@Idempotent`

`@Idempotent` takes an array of mapper classes in `fingerprint`. Each mapper is matched
to one parameter by type, and the parts are combined in array order.

- Several arguments, an explicit combination order independent of parameter order, and
  an empty default instead of a sentinel class. Java accepts a single class without
  braces.
- Cannot express two parameters of the same type, mappers whose type matches every
  parameter, or bean names.

```kotlin
@Idempotent(scope = "order.create", key = "#cmd.requestId",
            fingerprint = [CreateOrderFingerprint::class, TenantFingerprint::class])
fun create(cmd: CreateOrder, tenant: TenantId): IdempotencyResult<Order>
```
```java
@Idempotent(scope = "order.create", key = "#cmd.requestId", fingerprint = CreateOrderFingerprint.class)
```

#### Option B3: Separate single and array attributes

`@Idempotent` has two attributes: `fingerprint` for one mapper and `fingerprints` for
several.

- Each reads naturally for its case.
- Two ways to say the same thing, plus an invalid state when both are set.

```kotlin
@Idempotent(scope = "...", fingerprint = CreateOrderFingerprint::class)
@Idempotent(scope = "...", fingerprints = [CreateOrderFingerprint::class, TenantFingerprint::class])
```

#### Option B4: Parameter annotations

`@IdempotencyFingerprint` marks each contributing parameter, optionally with `mapper` or
`mapperBean`. Without an explicit mapper, the mapper is resolved by type.

- Handles same-type parameters, bean names and a whole-content fallback.
- Combination order follows parameter order, so reordering parameters changes the
  fingerprint.

```kotlin
@Idempotent(scope = "invoice.listener", key = "#event.id")
fun on(
    @IdempotencyFingerprint event: OrderPlaced,                             // mapper bean by type
    @IdempotencyFingerprint(mapper = TenantFingerprint::class) tenant: String,
    @IdempotencyFingerprint(mapperBean = "regionFingerprint") region: String,
)
```

#### Option B5: Annotation on the DTO class

`@IdempotencyFingerprintMapper(X::class)` on the DTO class selects its mapper.

- Defined once per type.
- Same coupling as A2. A mapper bean resolved by type provides the same reuse without
  touching the DTO.

```kotlin
@IdempotencyFingerprintMapper(CreateOrderFingerprint::class)
data class CreateOrder(...)
```

#### Option B6: Mapper bean activates fingerprinting

A bean of type `IdempotencyFingerprintMapper<T>` enables fingerprinting for every
declaration with a parameter of type `T`.

- No annotation needed.
- Not opt-in per declaration. Adding a bean, including one from a library, changes the
  behavior of unrelated scopes.

```kotlin
@Bean
fun createOrderFingerprint() = CreateOrderFingerprint()   // applies to every @Idempotent with a CreateOrder

@Idempotent(scope = "order.create", key = "#cmd.requestId")
fun create(cmd: CreateOrder): IdempotencyResult<Order>    // fingerprinted without saying so
```

### C. Reporting a Mismatch

#### Option C1: Exception

A mismatch throws an `IdempotencyFingerprintMismatchException` carrying the duplicate
context.

- Fits the rule that only normal outcomes are results. It maps naturally to an HTTP
  `@ExceptionHandler` or a dead-letter queue.
- Callers that want to handle mismatches as a normal case need `try`/`catch` next to
  `fold`, so one invocation has two places where outcomes are handled.

```kotlin
class IdempotencyFingerprintMismatchException(val context: IdempotencyDuplicateContext) : RuntimeException()

@ExceptionHandler
fun on(e: IdempotencyFingerprintMismatchException) = ResponseEntity.unprocessableEntity().build<Unit>()
```

#### Option C2: Third result variant

`IdempotencyResult` gains `Mismatch<T>(context)` next to `Executed` and `Duplicate`, and
`fold` gains a third branch.

- Explicit and exhaustive: Kotlin `when` and Java `fold` force callers to handle it.
- Every `fold` call grows a third argument, including in scopes that never use
  fingerprints.
- Methods without a return value discard the result, so the mismatch needs a separate
  rule there.

```kotlin
sealed interface IdempotencyResult<T> {
    data class Executed<T>(val value: T) : IdempotencyResult<T>
    data class Duplicate<T>(val context: IdempotencyDuplicateContext) : IdempotencyResult<T>
    data class Mismatch<T>(val context: IdempotencyDuplicateContext) : IdempotencyResult<T>
}

when (val result = orders.create(cmd)) {
    is Executed -> created(result.value)
    is Duplicate -> ok()
    is Mismatch -> unprocessableEntity()
}
```

#### Option C3: Flag on Duplicate

`Duplicate` gains a `fingerprintMatches: Boolean` property.

- No new variant, and `fold` keeps two branches.
- Easy to ignore: callers that only check for `Duplicate` treat a mismatch as success.

```kotlin
data class Duplicate<T>(val context: IdempotencyDuplicateContext, val fingerprintMatches: Boolean)
```

### D. Mismatch in Methods Without a Return Value

Applies when the outcome is a result variant (C2 or C3). Methods returning
`void`/`Unit`, typically message listeners, discard the result.

```kotlin
@Idempotent(scope = "invoice.listener", key = "#event.id", fingerprint = [OrderPlacedFingerprint::class])
fun on(event: OrderPlaced) { invoices.create(event) }    // nothing returns a Mismatch to the caller
```

#### Option D1: Disallow fingerprints

Fingerprints are only valid on methods returning `IdempotencyResult`. Startup fails for
the listener above.

- One way to report a mismatch, and nothing can be lost silently.
- Excludes the main use case for void methods: message listeners that only need
  deduplication.

#### Option D2: Always throw

Void methods throw `IdempotencyFingerprintMismatchException` on a mismatch.

- The message is retried or dead-lettered, so changed content is never acknowledged
  silently.
- Forces strict behavior on every listener, even where skipping is acceptable.

#### Option D3: Always skip

A mismatch in a void method is treated like a duplicate.

- Simple and consistent with duplicate handling.
- The fingerprint has no effect: behavior is identical to a declaration without one.

#### Option D4: Per-declaration choice

`@Idempotent` gains an `onMismatch` attribute with `SKIP` and `FAIL`, for void methods.
Every mismatch is also reported through instrumentation (metric, tracing event, log).

- Each listener chooses between deduplication and strict protection, and mismatches
  stay visible either way.
- Adds an annotation attribute that applies only to void methods.

```kotlin
@Idempotent(scope = "invoice.listener", key = "#event.id",
            fingerprint = [OrderPlacedFingerprint::class], onMismatch = OnMismatch.FAIL)   // or SKIP
fun on(event: OrderPlaced)
```

### E. Turning Values into Bytes

#### Option E1: General-purpose serializer

Values are serialized with a configured general-purpose serializer, for example
canonical JSON, and the result is hashed.

- Works for any object with no mapper code.
- Equality rules for numbers, time and collections are implicit and depend on library
  version and configuration. Adding a field with a default value changes every
  fingerprint.

```kotlin
digest = sha256(canonicalJson.writeValueAsBytes(cmd))
// {"customerId":"c-42","total":19.90,...} ≠ {"customerId":"c-42","total":19.9,...}
```

#### Option E2: Once-defined canonical encoding for many types

Once defines a canonical encoding for strings, numbers including `BigDecimal` and
floating point, temporal types, maps and sets.

- Convenient: most values can be added directly.
- Once must decide, for every application, questions such as scale, precision and key
  order. A wrong choice can only be corrected by changing the encoding.

```kotlin
IdempotencyFingerprint.of(cmd.customerId, cmd.total, cmd.requestedAt, mapOf("channel" to "web"))
// Once decides: is 19.9 equal to 19.90? Which Instant precision? Which key order?
```

#### Option E3: Minimal canonical encoding plus mappers

Once encodes only types with a single representation: strings, integers, booleans,
byte arrays, null, lists and nested fingerprints. Every part is framed with a type tag
and a length or count. Other types are converted by application mappers.

- Every equality decision lives in application code, where it is visible and
  versionable. The encoding is small enough to specify completely and keep stable.
- Applications write mappers for common types such as `BigDecimal` and `Instant`.

```kotlin
IdempotencyFingerprint.builder()
    .add(cmd.customerId)                                          // built-in
    .add(cmd.total.stripTrailingZeros().toPlainString())          // application decides: 19.9 == 19.90
    .add(cmd.requestedAt.truncatedTo(ChronoUnit.MILLIS).toString()) // application decides the precision
    .build()
```

### F. Reusing Conversions Across Mappers

Applies to E3.

#### Option F1: Plain injection

Mappers share conversion helpers as ordinary injected beans.

- No new concepts, fully transparent.
- Nothing ensures that all mappers use the same rule for a type.

```kotlin
class CreateOrderFingerprint(private val money: MoneyFingerprint) : IdempotencyFingerprintMapper<CreateOrder> {
    override fun map(subject: CreateOrder) =
        IdempotencyFingerprint.builder().add(subject.customerId).add(money.encode(subject.total)).build()
}
```

#### Option F2: Converter registry, writing into a builder

An `IdempotencyFingerprintConverter<T>` interface writes a value into a builder.
Converter beans are registered per Once instance and used by a builder that adds values
of any type.

- One rule per type for the whole application, similar to `IdempotencyKeyNormalizer`.
- Converters can write arbitrary parts into a foreign builder, and a second interface
  has nearly the same role as the mapper.

```kotlin
fun interface IdempotencyFingerprintConverter<in T> {
    fun convert(value: T, builder: IdempotencyFingerprint.Builder)
}

@Bean
fun money() = IdempotencyFingerprintConverter<BigDecimal> { value, builder ->
    builder.add(value.stripTrailingZeros().toPlainString())
}
```

#### Option F3: Mapper registry, returning a fingerprint

`IdempotencyFingerprintMapper` also serves as converter. A builder from the
`IdempotencyFingerprints` factory bean looks up mapper beans by type and nests their
result. A mapper can also be passed explicitly.

- One interface for top-level and part mappers, pure functions, and no converter
  chains, because mappers build with a builder that knows only built-in types.
- A converted value is encoded as a nested fingerprint, so it differs from the same
  value added inline. Changing a shared mapper changes every fingerprint that uses it.

```kotlin
@Bean
fun money() = IdempotencyFingerprintMapper<BigDecimal> {
    IdempotencyFingerprint.of(it.stripTrailingZeros().toPlainString())
}

class CreateOrderFingerprint(private val fingerprints: IdempotencyFingerprints) :
    IdempotencyFingerprintMapper<CreateOrder> {
    override fun map(subject: CreateOrder) =
        fingerprints.builder()
            .add(subject.customerId)              // built-in
            .add(subject.total)                   // BigDecimal mapper bean, found by type
            .add(subject.requestedAt, InstantMillis) // explicit mapper
            .build()
}
```

### G. Encoding Details

Encoded bytes are shown in hexadecimal: tag, then length or count, then content.

#### Option G1: Integers as fixed 8 bytes

All integer types are widened to 64 bits.

- Trivial, no length field.
- A future `BigInteger` would need a separate tag, so `BigInteger(42)` and `42L` would
  differ.

```
42 / 42L        02 00000000 0000002A
-1              02 FFFFFFFF FFFFFFFF
```

#### Option G2: Integers as minimal two's complement with a length

The representation of `BigInteger.toByteArray()`.

- Covers all built-in integer types and a future `BigInteger` with one tag.
- Slightly more complex than G1.

```
42 / 42L        02 00000001 2A
-1              02 00000001 FF
5_000_000_000L  02 00000005 012A05F200
```

#### Option G3: Separate tags for lists and nested fingerprints

Lists (tag `05`) and nested fingerprints (tag `06`) have their own tags with the same
layout.

- Switching between a list and a nested fingerprint changes the digest.
- One more tag than strictly necessary, as both have the same layout.

```
listOf("ab")             05 00000001 | 01 00000002 6162
nested fingerprint "ab"  06 00000001 | 01 00000002 6162
```

#### Option G4: Shared tag for lists and nested fingerprints

Lists and nested fingerprints are both encoded with tag `05`.

- One concept for groups of parts.
- A mapper that switches between the two produces the same digest, so the change goes
  unnoticed.

```
listOf("ab")             05 00000001 | 01 00000002 6162
nested fingerprint "ab"  05 00000001 | 01 00000002 6162
```

### H. Encoding Stability

#### Option H1: Frozen encoding

The encoding never changes, like the key hash. Stored values carry no encoding version.

- Simple, and no version marker in stored values.
- Any mistake in the encoding is permanent, and the encoding of broad types (E2) is
  hard to get right on the first attempt.

```
3f2a…        always encoding 1
```

#### Option H2: Versioned encoding

Stored values carry an encoding version, and Once keeps older encoders to recompute
them.

- Mistakes can be corrected without false mismatches.
- Once maintains several encoders, and every stored value carries extra information.

```
e=1:3f2a…    recomputed with encoder 1
e=2:9b41…    recomputed with encoder 2
```

#### Option H3: Minimal encoding with a reserved version tag

The encoding is kept minimal (E3) and extended only with new type tags. An encoding
version tag is reserved, with absence meaning version 1, but not implemented.

- Stable in practice, with a way back that costs nothing today.
- Relies on the minimal type set being specified correctly.

```
3f2a…        encoding 1 (tag absent)
e=2:9b41…    reserved, only if an existing tag's encoding must ever change
```

### I. Persisting the Fingerprint

#### Option I1: Included in the identity hash

The identity digest covers scope, key and fingerprint. No new column is added.

- No schema change.
- Different content produces a different identity, so the operation runs again. A
  mismatch can never be detected.

```
idempotency_hash = SHA-256(scope, key, fingerprint)
```

#### Option I2: Digest in a separate column

A new nullable column `request_fingerprint` holds the fingerprint digest.

- Small, and no request content is stored.
- Shows that content differs, not where.

```sql
request_fingerprint VARCHAR(255) NULL   -- 3f2a…
```

#### Option I3: Readable canonical content

A new column holds the canonical fingerprint parts in readable form.

- Mismatch diagnostics can show which part changed.
- Stores business and possibly personal data of unbounded size in the marker table.

```sql
request_fingerprint_data TEXT NULL      -- ["c-42", "19.9"]
```

#### Option I4: One digest per top-level part

The fingerprint column holds one digest per top-level part instead of one combined
digest.

- Shows which argument changed without storing content.
- Larger values and a more complex format for limited gain.

```sql
request_fingerprint VARCHAR(...) NULL   -- 3f2a…,9b41…
```

### J. Format of the Stored Value

Applies to I2.

#### Option J1: Bare digest with additional columns

The column holds only the digest. Versions and key IDs, if added, get their own columns.

- No parsing, same shape as the identity digest.
- Every new concept requires a schema change in all dialects.

```sql
request_fingerprint        CHAR(64)    NULL,   -- 3f2a…
request_fingerprint_key_id VARCHAR(50) NULL,   -- 2026-11
request_fingerprint_versions VARCHAR(50) NULL  -- 2,1
```

#### Option J2: Positional prefixes

The digest is preceded by positional prefixes for the scheme, key ID and versions.

- Compact.
- Optional parts make positions ambiguous, and adding a part changes the format.

```
s1:3f2a…
h1:2026-11:3f2a…
h1:2026-11:2,1:3f2a…    is "2,1" a version list or part of something else?
```

#### Option J3: Named tags

The value is a list of `name=value` tags followed by the digest. Defaults are omitted,
and unknown tags make a value not comparable.

- Self-describing, order-independent, and extensible without schema changes. Older
  Once versions handle values written by newer ones safely.
- Values must be parsed, and tag values must not contain separators.

```
3f2a…
v=2,1:3f2a…
k=2026-11:v=2,1:3f2a…
```

### K. Changing a Fingerprint Definition

The examples change this mapper by adding the delivery address:

```kotlin
IdempotencyFingerprint.builder().add(subject.customerId).add(subject.total, Money).build()
```

#### Option K1: Breaking change

Changing a mapper changes digests. This is documented, and applications accept false
mismatches until old markers expire.

- No additional concepts.
- Retries during a deployment can be rejected as mismatches.

#### Option K2: Manual versions, not comparable across versions

`IdempotencyFingerprintMapper` gains a `version` property, which is stored with the
digest. Fingerprints with different versions are not compared.

- No false mismatches.
- Real mismatches across versions go undetected until old markers expire. Developers
  must remember to change the version.

```kotlin
class CreateOrderFingerprint : IdempotencyFingerprintMapper<CreateOrder> {
    override val version = "2"
    override fun map(subject: CreateOrder) =
        IdempotencyFingerprint.builder().add(subject.customerId).add(subject.total, Money)
            .add(subject.deliveryAddress).build()
}
// stored v=1 vs computed v=2 → not comparable → Duplicate
```

#### Option K3: Manual versions with recomputation

As K2, but a `VersionedIdempotencyFingerprintMapper` can compute older versions, and
Once recomputes the fingerprint in the stored version.

- Mismatches stay detectable across changes.
- Old mapping code must be kept until retention expires. Instances running only the old
  version still cannot compare against new rows without a two-phase rollout.

```kotlin
class CreateOrderFingerprint : VersionedIdempotencyFingerprintMapper<CreateOrder> {
    override val currentVersion = "2"
    override fun map(subject: CreateOrder, version: String) = when (version) {
        "1" -> IdempotencyFingerprint.builder().add(subject.customerId).add(subject.total, Money).build()
        "2" -> IdempotencyFingerprint.builder().add(subject.customerId).add(subject.total, Money)
                   .add(subject.deliveryAddress).build()
        else -> null                                  // no longer supported
    }
}
// stored v=1 → recomputed with version "1" → Duplicate or Mismatch
```

#### Option K4: Versions derived from the fingerprint's structure

Once computes the version from the sequence of part types, without their values.

- No manual work.
- Misses the most common changes, such as a different property of the same type, and
  depends on data: a null in one request and a value in another look like different
  versions, hiding real mismatches.

```
.add(customerId).add(total, Money)        shape STR, NESTED       → version hash(shape)
.add(customerEmail).add(total, Money)     shape STR, NESTED       same version, different meaning
address = null  vs  address = "Main 1"    shape …, NULL vs …, STR different version for the same mapper
```

#### Option K5: Versions derived from mapper bytecode

Once computes the version from the mapper class's bytecode.

- No manual work.
- Changes on every recompile or refactoring, which disables comparison after most
  deployments. Misses changes in code the mapper calls.

```
version = sha256(CreateOrderFingerprint.class bytes)
```

### L. Protecting Low-Entropy Content

#### Option L1: Plain SHA-256 only

Fingerprints are always plain SHA-256 digests.

- Nothing to configure.
- Low-entropy content can be brute-forced from a leaked marker table.

```
SHA-256("c-42" | "19.9")    can be found by trying all customer IDs and amounts
```

#### Option L2: HMAC in application mappers

Mappers hash their content with an application secret and add the result as bytes.

- No support needed in Once.
- No key ID is stored, so rotating the secret is a breaking change.

```kotlin
override fun map(subject: CreateOrder) =
    IdempotencyFingerprint.ofBytes(hmacSha256(secret, subject.customerId, subject.total))
```

#### Option L3: Built-in HMAC with a stored key ID

Once computes HMAC-SHA-256 with configured keys and stores the key ID with the digest.
Old keys stay configured for verification until retention expires.

- Safe key rotation, and optional per instance.
- Secret management, and an optional key provider SPI.

```yaml
namastack:
  once:
    fingerprint:
      hmac:
        current-key-id: "2026-11"
        keys:
          "2026-11": ${ONCE_FP_KEY_2026_11}
          "2026-05": ${ONCE_FP_KEY_2026_05}   # verify only
```

#### Option L4: Built-in HMAC trying all keys

No key ID is stored. Once tries every configured key.

- No stored key ID.
- Several digests per duplicate, and a removed key turns a valid retry into a false
  mismatch.

```
stored 3f2a… → try HMAC(2026-11), HMAC(2026-05), SHA-256 → any match = Duplicate
```
