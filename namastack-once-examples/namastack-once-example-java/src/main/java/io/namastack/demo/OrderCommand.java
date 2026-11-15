package io.namastack.demo;

import java.util.UUID;

public record OrderCommand(UUID requestId, String orderId) {}
