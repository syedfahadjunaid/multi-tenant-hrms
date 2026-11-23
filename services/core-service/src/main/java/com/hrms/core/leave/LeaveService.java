package com.hrms.core.leave;

import com.hrms.core.audit.AuditService;
import com.hrms.core.common.BusinessRuleException;
import com.hrms.core.common.NotFoundException;
import com.hrms.core.config.HrmsProperties;
import com.hrms.core.employee.Employee;
import com.hrms.core.employee.EmployeeRepository;
import com.hrms.core.employee.ManagerChanged;
import com.hrms.core.leave.LeaveDtos.Balance;
import com.hrms.core.leave.LeaveDtos.EmployeeRef;
import com.hrms.core.leave.LeaveDtos.HistoryEntry;
import com.hrms.core.leave.LeaveDtos.LeaveOverview;
import com.hrms.core.leave.LeaveDtos.LeaveRequestDetail;
import com.hrms.core.leave.LeaveDtos.LeaveRequestResponse;
import com.hrms.core.leave.LeaveDtos.NewLeaveRequest;
import com.hrms.core.security.CurrentUser;
import com.hrms.core.security.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave requests and their approval workflow.
 *
 * <p>Authorization here is about relationships, not just roles: you may cancel <em>your own</em>
 * request, approve the first step only if you're <em>that employee's</em> manager, and approve
 * the second step if you're HR, but never your own request (separation of duties).
 *
 * <p>Error responses are chosen on purpose:
 * <ul>
 *   <li>404 if you may not even see the request (don't reveal that it exists)</li>
 *   <li>403 if you can see it but this action isn't yours to take</li>
 *   <li>409 if the action doesn't fit its current status (e.g. it's already approved)</li>
 *   <li>422 if the request breaks a business rule (overlap, not enough leave left, ...)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class LeaveService {

    /** Requests that block the dates and use up allowance. */
    private static final Set<LeaveStatus> ACTIVE =
            EnumSet.of(LeaveStatus.PENDING_MANAGER, LeaveStatus.PENDING_HR, LeaveStatus.APPROVED);
    private static final int MAX_CALENDAR_DAYS = 90;

    private final LeaveRequestRepository requests;
    private final LeaveRequestEventRepository history;
    private final EmployeeRepository employees;
    private final AuditService audit;
    private final Clock clock;
    private final int annualAllowance;

    public LeaveService(LeaveRequestRepository requests, LeaveRequestEventRepository history,
                        EmployeeRepository employees, AuditService audit, Clock clock, HrmsProperties props) {
        this.requests = requests;
        this.history = history;
        this.employees = employees;
        this.audit = audit;
        this.clock = clock;
        this.annualAllowance = props.leave().annualAllowanceDays();
    }

    // ---- reads ---------------------------------------------------------------------------

    public LeaveOverview overview() {
        return currentEmployee()
                .map(me -> new LeaveOverview(EmployeeRef.of(me), EmployeeRef.of(me.getManager()),
                        balance(me, LocalDate.now(clock).getYear())))
                .orElse(new LeaveOverview(null, null, null));
    }

    public List<LeaveRequestResponse> myRequests() {
        Employee me = requireCurrentEmployee();
        return requests.findTop100ByEmployeeOrderByStartDateDesc(me).stream()
                .map(request -> toResponse(request, me))
                .toList();
    }

    /** Everything waiting for the current user: their reports' requests, plus HR's queue for HR admins. */
    public List<LeaveRequestResponse> awaitingMyDecision() {
        Employee me = currentEmployee().orElse(null);
        List<LeaveRequest> queue = new ArrayList<>();
        if (me != null) {
            queue.addAll(requests.findByApproverAndStatusOrderByStartDateAsc(me, LeaveStatus.PENDING_MANAGER));
        }
        if (CurrentUser.hasRole(Role.HR_ADMIN)) {
            requests.findByStatusOrderByStartDateAsc(LeaveStatus.PENDING_HR).stream()
                    .filter(request -> !request.isRequestedBy(me)) // never approve your own leave
                    .forEach(queue::add);
        }
        queue.sort(Comparator.comparing(LeaveRequest::getStartDate));
        return queue.stream().map(request -> toResponse(request, me)).toList();
    }

    public LeaveRequestDetail get(UUID id) {
        Employee me = currentEmployee().orElse(null);
        LeaveRequest request = findVisible(id, me);
        List<HistoryEntry> entries = history.findByLeaveRequestOrderByOccurredAtAsc(request).stream()
                .map(e -> new HistoryEntry(e.getAction(), e.getFromStatus(), e.getToStatus(),
                        e.getActorName(), e.getComment(), e.getOccurredAt()))
                .toList();
        return new LeaveRequestDetail(toResponse(request, me), entries);
    }

    // ---- writes --------------------------------------------------------------------------

    @Transactional
    public LeaveRequestResponse submit(NewLeaveRequest input) {
        // Row lock on the employee: two simultaneous submissions are handled one after the other,
        // so both can't pass the balance check and overdraw the allowance.
        Employee me = employees.findByIdForUpdate(requireCurrentEmployee().getId()).orElseThrow();

        LocalDate start = input.startDate();
        LocalDate end = input.endDate();
        if (end.isBefore(start)) {
            throw new BusinessRuleException("endDate", "must be on or after the start date");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_CALENDAR_DAYS) {
            throw new BusinessRuleException("endDate", "a single request can cover at most %d days".formatted(MAX_CALENDAR_DAYS));
        }
        if (input.type() == LeaveType.ANNUAL && start.isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleException("startDate", "annual leave can't start in the past");
        }
        int days = WorkingDays.between(start, end);
        if (days == 0) {
            throw new BusinessRuleException("endDate", "the selected dates contain no working days");
        }
        if (requests.existsOverlapping(me, ACTIVE, start, end)) {
            throw new BusinessRuleException("startDate", "overlaps another leave request of yours");
        }
        if (input.type() == LeaveType.ANNUAL) {
            // Simplification: leave that crosses New Year counts against the year it starts in.
            Balance balance = balance(me, start.getYear());
            if (days > balance.remainingDays()) {
                throw new BusinessRuleException("endDate", "you have %d days of annual leave left in %d"
                        .formatted(balance.remainingDays(), balance.year()));
            }
        }

        String reason = input.reason() == null || input.reason().isBlank() ? null : input.reason().trim();
        LeaveRequest request = requests.save(new LeaveRequest(me, input.type(), start, end, reason));
        recordStep(request, "SUBMIT", null, request.getStatus(), reason);
        audit.record("LEAVE_SUBMITTED", "LeaveRequest", request.getId());
        return toResponse(request, me);
    }

    @Transactional
    public LeaveRequestResponse decide(UUID id, LeaveAction action, String comment) {
        Employee me = currentEmployee().orElse(null);
        LeaveRequest request = findVisible(id, me);
        if (!mayAttempt(request, action, me)) {
            throw new AccessDeniedException("Not allowed to " + action + " this request");
        }

        LeaveStatus from = request.getStatus();
        LeaveStatus to = request.apply(action); // the state machine: throws 409 if not allowed now
        String note = comment == null || comment.isBlank() ? null : comment.trim();
        recordStep(request, action.name(), from, to, note);
        String auditAction = switch (action) {
            case APPROVE -> to == LeaveStatus.PENDING_HR ? "LEAVE_MANAGER_APPROVED" : "LEAVE_APPROVED";
            case REJECT -> "LEAVE_REJECTED";
            case CANCEL -> "LEAVE_CANCELLED";
        };
        audit.record(auditAction, "LeaveRequest", request.getId());
        return toResponse(request, me);
    }

    /**
     * Reacts to a reorg. Runs synchronously inside the transaction that changed the manager,
     * so either both the new reporting line and the reassigned requests are saved, or neither.
     */
    @EventListener
    @Transactional
    public void onManagerChanged(ManagerChanged event) {
        Employee employee = employees.findById(event.employeeId()).orElseThrow();
        Employee newManager = event.newManagerId() == null ? null
                : employees.findById(event.newManagerId()).orElseThrow();

        for (LeaveRequest request : requests.findByEmployeeAndStatus(employee, LeaveStatus.PENDING_MANAGER)) {
            String previous = request.getApprover() == null ? "nobody" : request.getApprover().fullName();
            LeaveStatus from = request.getStatus();
            LeaveStatus to = request.reassign(newManager);
            String note = newManager == null
                    ? "%s no longer manages %s, so this goes straight to HR".formatted(previous, employee.fullName())
                    : "Approver changed from %s to %s".formatted(previous, newManager.fullName());
            recordStep(request, "REASSIGN", from, to, note);
            audit.record("LEAVE_REASSIGNED", "LeaveRequest", request.getId(), note);
        }
    }

    // ---- rules ---------------------------------------------------------------------------

    /** Requester, their assigned manager, and HR may see a request. Everyone else gets a 404. */
    private LeaveRequest findVisible(UUID id, Employee me) {
        return requests.findById(id)
                .filter(request -> request.isRequestedBy(me)
                        || request.isAssignedTo(me)
                        || CurrentUser.hasRole(Role.HR_ADMIN))
                .orElseThrow(() -> new NotFoundException("Leave request", id));
    }

    /** Is this person the right one for this action? (Whether the status allows it is the state machine's job.) */
    private boolean mayAttempt(LeaveRequest request, LeaveAction action, Employee me) {
        boolean isRequester = request.isRequestedBy(me);
        return switch (action) {
            case CANCEL -> isRequester;
            case APPROVE, REJECT -> !isRequester && switch (request.getStatus()) {
                case PENDING_MANAGER -> request.isAssignedTo(me);
                case PENDING_HR -> CurrentUser.hasRole(Role.HR_ADMIN);
                case APPROVED, REJECTED, CANCELLED -> true; // let the state machine say why not (409)
            };
        };
    }

    private List<LeaveAction> allowedActions(LeaveRequest request, Employee me) {
        if (!request.getStatus().isPending()) {
            return List.of();
        }
        return Arrays.stream(LeaveAction.values())
                .filter(action -> mayAttempt(request, action, me))
                .toList();
    }

    private Balance balance(Employee employee, int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        long used = requests.sumWorkingDays(employee, LeaveType.ANNUAL, EnumSet.of(LeaveStatus.APPROVED), from, to);
        long pending = requests.sumWorkingDays(employee, LeaveType.ANNUAL,
                EnumSet.of(LeaveStatus.PENDING_MANAGER, LeaveStatus.PENDING_HR), from, to);
        return new Balance(year, annualAllowance, used, pending, annualAllowance - used - pending);
    }

    /** The signed-in user's employee record, matched by verified email within their tenant. */
    private Optional<Employee> currentEmployee() {
        return CurrentUser.verifiedEmail().flatMap(employees::findByEmail);
    }

    private Employee requireCurrentEmployee() {
        return currentEmployee().orElseThrow(() -> new BusinessRuleException(null,
                "Your account isn't linked to an employee record. Ask HR to add you with your work email."));
    }

    private void recordStep(LeaveRequest request, String action, LeaveStatus from, LeaveStatus to, String comment) {
        history.save(new LeaveRequestEvent(request, action, from, to,
                CurrentUser.subject(), CurrentUser.displayName(), comment));
    }

    private LeaveRequestResponse toResponse(LeaveRequest r, Employee me) {
        return new LeaveRequestResponse(r.getId(), EmployeeRef.of(r.getEmployee()), EmployeeRef.of(r.getApprover()),
                r.getType(), r.getStartDate(), r.getEndDate(), r.getWorkingDays(), r.getReason(), r.getStatus(),
                r.getCreatedAt(), allowedActions(r, me));
    }
}
