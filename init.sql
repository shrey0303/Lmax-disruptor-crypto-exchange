-- Runs against the p2pc database (created by POSTGRES_DB=p2pc).
-- Tables live in the default public schema.
CREATE USER reader WITH PASSWORD 'reader';
GRANT CONNECT ON DATABASE p2pc TO reader;
GRANT USAGE ON SCHEMA public TO reader;

CREATE TABLE trading_wallets
(
    id BIGINT PRIMARY KEY
);

CREATE TABLE trading_wallet_balances
(
    wallet_id BIGINT      NOT NULL,
    asset     VARCHAR(16) NOT NULL,
    amount    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (wallet_id, asset),
    FOREIGN KEY (wallet_id) REFERENCES trading_wallets (id) ON DELETE CASCADE
);

CREATE TABLE trading_wallet_holds
(
    wallet_id BIGINT      NOT NULL,
    asset     VARCHAR(16) NOT NULL,
    amount    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (wallet_id, asset),
    FOREIGN KEY (wallet_id) REFERENCES trading_wallets (id) ON DELETE CASCADE
);

CREATE TABLE snapshots
(
    id    VARCHAR(36) PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);

-- Grant read access on the tables just created.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO reader;

INSERT INTO snapshots (id, value) VALUES ('LAST_KAFKA_OFFSET', '-1');
INSERT INTO snapshots (id, value) VALUES ('LAST_BALANCE_ID', '0');
