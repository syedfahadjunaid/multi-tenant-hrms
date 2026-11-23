package com.hrms.core.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * JPA entity: one row of {@code core.employees}.
 *
 * <p>Fields are private with getters only: other code changes an employee through
 * methods that keep it valid, not by setting fields one by one.
 */
@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Filled in and filtered by Hibernate automatically, see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "employee_code", nullable = false)
    private String employeeCode;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    private String department;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    /** Who approves this employee's leave first. Loaded only when used (LAZY). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: concurrent edits fail instead of silently overwriting each other. */
    @Version
    private long version;

    /** Required by JPA; not for application code. */
    protected Employee() {}

    public Employee(String employeeCode, String firstName, String lastName, String email,
                    String department, String jobTitle, LocalDate hireDate, Employee manager) {
        this.employeeCode = employeeCode;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.department = department;
        this.jobTitle = jobTitle;
        this.hireDate = hireDate;
        this.manager = manager;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    /** Validation (no loops, same company) is EmployeeService's job; this just records it. */
    void changeManager(Employee newManager) {
        this.manager = newManager;
    }

    /** Compares by id, so it also works with Hibernate's lazy-loading proxies. */
    public boolean isSameAs(Employee other) {
        return other != null && id != null && id.equals(other.getId());
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
    public String getTenantId() { return tenantId; }
    public String getEmployeeCode() { return employeeCode; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getDepartment() { return department; }
    public String getJobTitle() { return jobTitle; }
    public LocalDate getHireDate() { return hireDate; }
    public Employee getManager() { return manager; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
