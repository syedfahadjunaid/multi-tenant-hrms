package com.hrms.core.leave;

import com.hrms.core.common.ConflictException;
import java.util.Locale;

/** E.g. approving a request that was already cancelled. HTTP 409. */
public class InvalidLeaveTransitionException extends ConflictException {

    public InvalidLeaveTransitionException(LeaveStatus status, LeaveAction action) {
        super("Can't %s a request that is %s.".formatted(
                action.name().toLowerCase(Locale.ROOT),
                status.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
    }
}
