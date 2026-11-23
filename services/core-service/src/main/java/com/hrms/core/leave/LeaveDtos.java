package com.hrms.core.leave;

import com.hrms.core.employee.Employee;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** JSON shapes for the leave API. */
public final class LeaveDtos {

    private LeaveDtos() {}

    public record NewLeaveRequest(
            @NotNull LeaveType type,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Size(max = 500) String reason) {}

    public record ApproveRequest(@Size(max = 500) String comment) {}

    /** A rejection must say why: the employee deserves an explanation. */
    public record RejectRequest(@NotBlank @Size(max = 500) String comment) {}

    public record EmployeeRef(UUID id, String name) {
        static EmployeeRef of(Employee e) {
            return e == null ? null : new EmployeeRef(e.getId(), e.fullName());
        }
    }

    public record Balance(int year, int allowanceDays, long usedDays, long pendingDays, long remainingDays) {}

    /** {@code employee} is null when the signed-in account isn't linked to an employee record. */
    public record LeaveOverview(EmployeeRef employee, EmployeeRef manager, Balance balance) {}

    /**
     * {@code allowedActions}: what the <em>current user</em> may do with this request. The UI
     * shows exactly these buttons instead of re-implementing the permission rules.
     */
    public record LeaveRequestResponse(
            UUID id,
            EmployeeRef employee,
            EmployeeRef approver,
            LeaveType type,
            LocalDate startDate,
            LocalDate endDate,
            int workingDays,
            String reason,
            LeaveStatus status,
            Instant createdAt,
            List<LeaveAction> allowedActions) {}

    public record HistoryEntry(
            String action,
            LeaveStatus fromStatus,
            LeaveStatus toStatus,
            String actorName,
            String comment,
            Instant occurredAt) {}

    public record LeaveRequestDetail(LeaveRequestResponse request, List<HistoryEntry> history) {}
}
