-- Flyway migration: runs once, in order, and is recorded in core.flyway_schema_history.
-- Never edit a migration that has already run. Add V2__..., V3__... instead.

CREATE TABLE employees (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    employee_code VARCHAR(32)  NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    department    VARCHAR(100),
    job_title     VARCHAR(100),
    hire_date     DATE         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking (@Version)
    CONSTRAINT uq_employees_tenant_code  UNIQUE (tenant_id, employee_code),
    CONSTRAINT uq_employees_tenant_email UNIQUE (tenant_id, email)
);

CREATE INDEX ix_employees_tenant_last_name ON employees (tenant_id, last_name);

-- Audit trail: who did what, when. Written in the same transaction as the change itself.
CREATE TABLE audit_events (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    actor       VARCHAR(255) NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(64)  NOT NULL,
    entity_id   VARCHAR(64),
    occurred_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_events_tenant_time ON audit_events (tenant_id, occurred_at DESC);

-- Enforce "append-only" in the database itself, so even a buggy or compromised
-- application cannot quietly rewrite history.
CREATE FUNCTION audit_events_are_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% is not allowed)', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_are_append_only();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_are_append_only();
