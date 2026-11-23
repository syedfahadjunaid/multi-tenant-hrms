-- Leave requests with a two-step approval workflow (manager, then HR).

-- 1. Reporting lines. A composite foreign key on (tenant_id, id) means the database itself
--    refuses a manager from another tenant, even if application code had a bug.
ALTER TABLE employees ADD CONSTRAINT uq_employees_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE employees ADD COLUMN manager_id UUID;
ALTER TABLE employees ADD CONSTRAINT fk_employees_manager
    FOREIGN KEY (tenant_id, manager_id) REFERENCES employees (tenant_id, id);
CREATE INDEX ix_employees_manager ON employees (tenant_id, manager_id);
-- Employees are matched to Keycloak accounts by verified email; uq_employees_tenant_email
-- (from V1) already indexes that lookup.

-- 2. Leave requests. "status" is the workflow state; transitions live in LeaveStatus.java.
CREATE TABLE leave_requests (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    employee_id  UUID         NOT NULL,
    approver_id  UUID,                          -- the manager at submission time; NULL = straight to HR
    type         VARCHAR(16)  NOT NULL,
    start_date   DATE         NOT NULL,
    end_date     DATE         NOT NULL,
    working_days INT          NOT NULL,
    reason       VARCHAR(500),
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking: two approvers can't both win
    CONSTRAINT fk_leave_employee FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    CONSTRAINT fk_leave_approver FOREIGN KEY (tenant_id, approver_id) REFERENCES employees (tenant_id, id),
    CONSTRAINT ck_leave_type     CHECK (type IN ('ANNUAL', 'SICK', 'UNPAID')),
    CONSTRAINT ck_leave_status   CHECK (status IN ('PENDING_MANAGER', 'PENDING_HR', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_leave_dates    CHECK (end_date >= start_date),
    CONSTRAINT ck_leave_days     CHECK (working_days > 0)
);

CREATE INDEX ix_leave_requests_employee ON leave_requests (tenant_id, employee_id, start_date);
CREATE INDEX ix_leave_requests_queue    ON leave_requests (tenant_id, status, approver_id);

-- 3. Every state change, with who did it and why. Shown as the request's timeline.
CREATE TABLE leave_request_events (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    leave_request_id UUID         NOT NULL REFERENCES leave_requests (id),
    action           VARCHAR(16)  NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20)  NOT NULL,
    actor_subject    VARCHAR(255) NOT NULL,
    actor_name       VARCHAR(255) NOT NULL,
    comment          VARCHAR(500),
    occurred_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_leave_events_request ON leave_request_events (leave_request_id, occurred_at);

-- History is append-only, like the audit log.
CREATE FUNCTION reject_history_changes() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% is not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_leave_events_no_update_delete
    BEFORE UPDATE OR DELETE ON leave_request_events
    FOR EACH ROW EXECUTE FUNCTION reject_history_changes();

CREATE TRIGGER trg_leave_events_no_truncate
    BEFORE TRUNCATE ON leave_request_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_changes();
