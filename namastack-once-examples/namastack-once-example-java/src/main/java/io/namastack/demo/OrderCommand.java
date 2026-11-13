package io.namastack.demo;

import java.util.UUID;

/**
 * Application command retaining its request identity across delivery retries.
 *
 * @param requestId stable identity reused when this command is delivered again
 * @param orderId identifier to persist for a newly created order
 */
public record OrderCommand(UUID requestId, String orderId) {}
