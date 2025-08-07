CREATE DATABASE p2pc;
CREATE USER 'reader'@'%' IDENTIFIED BY 'reader';
GRANT SELECT ON p2pc.* TO 'reader'@'%';
FLUSH PRIVILEGES;

CREATE TABLE p2pc.trading_wallets
(
    id          BIGINT PRIMARY KEY
);

CREATE TABLE p2pc.trading_wallet_balances
(
    wallet_id   BIGINT NOT NULL,
    asset       VARCHAR(16) NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (wallet_id, asset),
    FOREIGN KEY (wallet_id) REFERENCES trading_wallets(id) ON DELETE CASCADE
);

CREATE TABLE p2pc.trading_wallet_holds
(
    wallet_id   BIGINT NOT NULL,
    asset       VARCHAR(16) NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (wallet_id, asset),
    FOREIGN KEY (wallet_id) REFERENCES trading_wallets(id) ON DELETE CASCADE
);

CREATE TABLE p2pc.snapshots
(
    `id`  VARCHAR(36) PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);

INSERT INTO p2pc.snapshots (`id`, `value`) VALUES ('LAST_KAFKA_OFFSET', '-1');
INSERT INTO p2pc.snapshots (`id`, `value`) VALUES ('LAST_BALANCE_ID', '0');
