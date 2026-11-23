package com.hrms.core.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The whole state machine as a table. Plain unit tests: no Spring, no database. */
class LeaveStatusTests {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "PENDING_MANAGER, APPROVE, PENDING_HR",
            "PENDING_MANAGER, REJECT,  REJECTED",
            "PENDING_MANAGER, CANCEL,  CANCELLED",
            "PENDING_HR,      APPROVE, APPROVED",
            "PENDING_HR,      REJECT,  REJECTED",
            "PENDING_HR,      CANCEL,  CANCELLED",
    })
    void allowedTransitions(LeaveStatus from, LeaveAction action, LeaveStatus expected) {
        assertThat(from.after(action)).isEqualTo(expected);
    }

    @Test
    void aReorgKeepsTheManagerStepOrSkipsToHr() {
        assertThat(LeaveStatus.PENDING_MANAGER.afterManagerChanged(true)).isEqualTo(LeaveStatus.PENDING_MANAGER);
        assertThat(LeaveStatus.PENDING_MANAGER.afterManagerChanged(false)).isEqualTo(LeaveStatus.PENDING_HR);
    }

    @ParameterizedTest
    @EnumSource(value = LeaveStatus.class, names = "PENDING_MANAGER", mode = EnumSource.Mode.EXCLUDE)
    void onlyRequestsWaitingForAManagerCanBeReassigned(LeaveStatus status) {
        assertThatThrownBy(() -> status.afterManagerChanged(true)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = LeaveStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    void finalStatusesCannotChange(LeaveStatus status) {
        for (LeaveAction action : LeaveAction.values()) {
            assertThatThrownBy(() -> status.after(action))
                    .isInstanceOf(InvalidLeaveTransitionException.class)
                    .hasMessageContaining(status.name().toLowerCase());
        }
    }
}
