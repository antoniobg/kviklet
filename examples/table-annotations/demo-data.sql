-- A stand-in "production" schema for the table annotations demo.
--
-- The point of the demo is that some of these tables are mirrored into a cache and some are not,
-- and that nothing in the SQL tells you which is which. The annotation server decides.

CREATE TABLE subscriber (
    id            SERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    plan          VARCHAR(50)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE entitlement (
    id            SERIAL PRIMARY KEY,
    subscriber_id INTEGER      NOT NULL REFERENCES subscriber (id),
    name          VARCHAR(100) NOT NULL,
    expires_at    DATE
);

CREATE TABLE billing_event (
    id            SERIAL PRIMARY KEY,
    subscriber_id INTEGER      NOT NULL REFERENCES subscriber (id),
    kind          VARCHAR(50)  NOT NULL,
    amount_cents  INTEGER      NOT NULL
);

CREATE TABLE billing_invoice (
    id            SERIAL PRIMARY KEY,
    subscriber_id INTEGER      NOT NULL REFERENCES subscriber (id),
    total_cents   INTEGER      NOT NULL,
    paid          BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Deliberately not annotated, so you can see a write that raises no warning.
CREATE TABLE audit_log (
    id            SERIAL PRIMARY KEY,
    actor         VARCHAR(255) NOT NULL,
    action        VARCHAR(255) NOT NULL
);

INSERT INTO subscriber (email, plan, active) VALUES
    ('ada@example.com', 'pro', TRUE),
    ('grace@example.com', 'free', TRUE),
    ('alan@example.com', 'pro', FALSE),
    ('edsger@example.com', 'enterprise', TRUE);

INSERT INTO entitlement (subscriber_id, name, expires_at) VALUES
    (1, 'premium_support', '2027-01-01'),
    (1, 'api_access', '2027-01-01'),
    (2, 'api_access', NULL),
    (4, 'premium_support', '2026-12-31');

INSERT INTO billing_event (subscriber_id, kind, amount_cents) VALUES
    (1, 'charge', 4900),
    (2, 'charge', 0),
    (4, 'charge', 99000),
    (3, 'refund', -4900);

INSERT INTO billing_invoice (subscriber_id, total_cents, paid) VALUES
    (1, 4900, TRUE),
    (4, 99000, FALSE);

INSERT INTO audit_log (actor, action) VALUES
    ('admin@admin.com', 'seeded the demo data');

ALTER TABLE subscriber OWNER TO postgres;
ALTER TABLE entitlement OWNER TO postgres;
ALTER TABLE billing_event OWNER TO postgres;
ALTER TABLE billing_invoice OWNER TO postgres;
ALTER TABLE audit_log OWNER TO postgres;
