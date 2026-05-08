CREATE TABLE IF NOT EXISTS products (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    price       NUMERIC(19, 2) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
