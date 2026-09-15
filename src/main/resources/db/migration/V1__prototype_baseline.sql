-- Existing prototype entities only; not the eight-table phase-one target design.
-- Flyway refuses an unmanaged non-empty schema. Inspect/back up old databases first.
-- Do not edit this migration after it has been applied; add a new version instead.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(40) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(255),
    phone VARCHAR(255),
    role VARCHAR(255) NOT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    publisher_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(3000) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category VARCHAR(255),
    location VARCHAR(255),
    occurred_at DATE,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_items_publisher FOREIGN KEY (publisher_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
