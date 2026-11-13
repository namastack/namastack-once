package io.namastack.demo;

/**
 * Fresh business value returned by order creation; Once does not store or replay it.
 *
 * @param id identifier persisted by the order service
 */
public record Order(String id) {}
