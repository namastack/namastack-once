CREATE TABLE demo_order (
    id VARCHAR(36) PRIMARY KEY,
    amount_cents BIGINT NOT NULL
);
CREATE TABLE demo_invoice (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    amount_cents BIGINT NOT NULL
);
