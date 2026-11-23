package com.hrms.core.employee;

import com.hrms.core.audit.AuditService;
import com.hrms.core.common.BusinessRuleException;
import com.hrms.core.common.NotFoundException;
import com.hrms.core.common.PageResponse;
import com.hrms.core.employee.EmployeeDtos.CreateEmployeeRequest;
import com.hrms.core.employee.EmployeeDtos.EmployeeResponse;
import com.hrms.core.security.CurrentUser;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for employees. Controllers stay thin (HTTP in/out); rules live here.
 *
 * <p>{@code @Transactional(readOnly = true)} on the class is the default for every method;
 * methods that write override it with plain {@code @Transactional}.
 */
@Service
@Transactional(readOnly = true)
public class EmployeeService {

    /** Guard against corrupted data: real org charts are nowhere near this deep. */
    private static final int MAX_CHAIN_DEPTH = 50;

    private final EmployeeRepository employees;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    // Constructor injection: dependencies are explicit, final, and easy to fake in tests.
    public EmployeeService(EmployeeRepository employees, AuditService audit, ApplicationEventPublisher events) {
        this.employees = employees;
        this.audit = audit;
        this.events = events;
    }

    public PageResponse<EmployeeResponse> list(Pageable pageable) {
        return PageResponse.of(employees.findAll(pageable).map(EmployeeResponse::from));
    }

    public EmployeeResponse get(UUID id) {
        return employees.findById(id)
                .map(EmployeeResponse::from)
                .orElseThrow(() -> new NotFoundException("Employee", id));
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request) {
        // The tenant filter applies here too: a manager id from another company is "not found".
        Employee manager = request.managerId() == null ? null
                : employees.findById(request.managerId())
                        .orElseThrow(() -> new BusinessRuleException("managerId", "no such employee"));

        Employee employee = new Employee(
                request.employeeCode(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.department(),
                request.jobTitle(),
                request.hireDate(),
                manager);

        Employee saved = employees.saveAndFlush(employee); // flush now so duplicates fail here (409)
        audit.record("EMPLOYEE_CREATED", "Employee", saved.getId());
        return EmployeeResponse.from(saved);
    }

    /**
     * Moves an employee to a different manager, or to none ({@code newManagerId == null}).
     * Pending leave approvals follow automatically (see LeaveService#onManagerChanged).
     */
    @Transactional
    public EmployeeResponse changeManager(UUID employeeId, UUID newManagerId) {
        employees.lockReportingLines("reporting-lines:" + CurrentUser.tenantId().orElseThrow());

        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee", employeeId));
        Employee newManager = newManagerId == null ? null
                : employees.findById(newManagerId)
                        .orElseThrow(() -> new BusinessRuleException("managerId", "no such employee"));
        Employee previous = employee.getManager();

        if (previous == null ? newManager == null : previous.isSameAs(newManager)) {
            return EmployeeResponse.from(employee); // nothing changes
        }
        if (newManager != null) {
            rejectLoops(employee, newManager);
        }

        employee.changeManager(newManager);
        // Audit the cause before publishing, so the trail reads cause first, then its effects.
        audit.record("EMPLOYEE_MANAGER_CHANGED", "Employee", employee.getId(),
                "manager: %s -> %s".formatted(nameOf(previous), nameOf(newManager)));
        events.publishEvent(new ManagerChanged(employee.getId(),
                previous == null ? null : previous.getId(),
                newManager == null ? null : newManager.getId()));
        return EmployeeResponse.from(employee);
    }

    /**
     * Walks up from the proposed manager to the top of the org. Meeting the employee on the way
     * means they'd end up (indirectly) managing themselves.
     */
    private void rejectLoops(Employee employee, Employee newManager) {
        if (newManager.isSameAs(employee)) {
            throw new BusinessRuleException("managerId", "an employee can't be their own manager");
        }
        Employee step = newManager;
        for (int depth = 0; step != null; depth++) {
            if (depth > MAX_CHAIN_DEPTH) {
                throw new IllegalStateException("Reporting chain longer than " + MAX_CHAIN_DEPTH);
            }
            if (step.isSameAs(employee)) {
                throw new BusinessRuleException("managerId", "%s already reports to %s, directly or indirectly"
                        .formatted(newManager.fullName(), employee.fullName()));
            }
            step = step.getManager();
        }
    }

    private static String nameOf(Employee e) {
        return e == null ? "none" : e.fullName();
    }
}
