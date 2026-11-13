package io.namastack.once

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Computes the stable database identity from an application scope and normalized key.
 *
 * Each UTF-8 component is preceded by its four-byte big-endian byte length. This prevents component
 * boundaries from becoming ambiguous, even when either value contains delimiters. The encoding and
 * lowercase SHA-256 representation must remain stable across releases.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class IdempotencyKeyHasher {
    /**
     * Hashes the scope and normalized key without trimming or changing either component.
     *
     * @param scope Stable namespace in which invocation keys are unique.
     * @param key Canonical application key produced by the key normalizer.
     * @return The 64-character lowercase hexadecimal SHA-256 identity.
     */
    fun hash(
        scope: String,
        key: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendLengthPrefixedComponent(digest, scope)
        appendLengthPrefixedComponent(digest, key)

        return HexFormat.of().formatHex(digest.digest())
    }

    private fun appendLengthPrefixedComponent(
        digest: MessageDigest,
        component: String,
    ) {
        val componentBytes = component.toByteArray(UTF_8)
        val byteLengthPrefix = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(componentBytes.size).array()

        digest.update(byteLengthPrefix)
        digest.update(componentBytes)
    }
}
