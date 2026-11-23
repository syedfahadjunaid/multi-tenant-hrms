package com.hrms.core.leave;

public enum LeaveType {
    /** Paid holiday. Counts against the yearly allowance and can't start in the past. */
    ANNUAL,
    /** Can be recorded after the fact. */
    SICK,
    UNPAID
}
