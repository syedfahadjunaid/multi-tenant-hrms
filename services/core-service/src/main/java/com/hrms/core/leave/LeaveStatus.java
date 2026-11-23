package com.hrms.core.leave;

/**
 * The approval workflow as a state machine.
 *
 * <pre>
 *                approve               approve
 *  PENDING_MANAGER ─────► PENDING_HR ─────► APPROVED
 *        │                    │
 *        │ reject / cancel    │ reject / cancel
 *        ▼                    ▼
 *    REJECTED / CANCELLED (final)
 * </pre>
 *
 * Employees without a manager start at PENDING_HR. If an employee's manager is removed while a
 * request waits at PENDING_MANAGER, it moves on to PENDING_HR ({@link #afterManagerChanged}).
 * All the rules about which move is legal live in this enum; who may make the move is decided
 * in LeaveService.
 */
public enum LeaveStatus {
    PENDING_MANAGER,
    PENDING_HR,
    APPROVED,
    REJECTED,
    CANCELLED;

    /**
     * The status after {@code action}, or an exception if that move isn't allowed.
     *
     * <p>These are switch <em>expressions</em>: the compiler checks every status and every action
     * is handled, so adding a new enum value without deciding its transitions won't compile.
     */
    public LeaveStatus after(LeaveAction action) {
        return switch (this) {
            case PENDING_MANAGER -> switch (action) {
                case APPROVE -> PENDING_HR;
                case REJECT -> REJECTED;
                case CANCEL -> CANCELLED;
            };
            case PENDING_HR -> switch (action) {
                case APPROVE -> APPROVED;
                case REJECT -> REJECTED;
                case CANCEL -> CANCELLED;
            };
            case APPROVED, REJECTED, CANCELLED -> throw new InvalidLeaveTransitionException(this, action);
        };
    }

    /**
     * The employee's manager changed while this request waited for the old one (a reorg).
     * It stays with the new manager, or goes on to HR if there's no manager any more.
     */
    public LeaveStatus afterManagerChanged(boolean hasNewManager) {
        if (this != PENDING_MANAGER) {
            throw new IllegalStateException("Only requests waiting for a manager can be reassigned, not " + this);
        }
        return hasNewManager ? PENDING_MANAGER : PENDING_HR;
    }

    public boolean isPending() {
        return this == PENDING_MANAGER || this == PENDING_HR;
    }
}
