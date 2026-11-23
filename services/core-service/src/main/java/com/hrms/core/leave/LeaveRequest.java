package com.hrms.core.leave;

import com.hrms.core.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    /**
     * Who approves the manager step: the employee's manager when the request was made.
     * Changes only if the employee gets a new manager while the request waits for that step.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private Employee approver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LeaveType type;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Column(name = "working_days", nullable = false, updatable = false)
    private int workingDays;

    @Column(updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** If two approvers act at the same moment, the second save fails instead of overwriting. */
    @Version
    private long version;

    protected LeaveRequest() {}

    LeaveRequest(Employee employee, LeaveType type, LocalDate startDate, LocalDate endDate, String reason) {
        this.employee = employee;
        this.approver = employee.getManager();
        this.type = type;
        this.startDate = startDate;
        this.endDate = endDate;
        this.workingDays = WorkingDays.between(startDate, endDate);
        this.reason = reason;
        this.status = approver == null ? LeaveStatus.PENDING_HR : LeaveStatus.PENDING_MANAGER;
    }

    /** Moves the request through the workflow. Throws if the move isn't allowed. */
    LeaveStatus apply(LeaveAction action) {
        status = status.after(action);
        return status;
    }

    /** Hands the manager step to the employee's new manager, or on to HR if there is none. */
    LeaveStatus reassign(Employee newApprover) {
        status = status.afterManagerChanged(newApprover != null);
        approver = newApprover;
        return status;
    }

    boolean isRequestedBy(Employee person) {
        return employee.isSameAs(person);
    }

    boolean isAssignedTo(Employee person) {
        return approver != null && approver.isSameAs(person);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Employee getEmployee() { return employee; }
    public Employee getApprover() { return approver; }
    public LeaveType getType() { return type; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getWorkingDays() { return workingDays; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
