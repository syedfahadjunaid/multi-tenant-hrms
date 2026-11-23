package com.hrms.core.employee;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API request/response shapes (DTOs). Keeping them separate from the entity means the
 * database model can change without breaking API clients, and clients can never set
 * fields like {@code id} or {@code tenantId} themselves (mass-assignment protection).
 */
public final class EmployeeDtos {

    private EmployeeDtos() {}

    /** Validated by {@code @Valid} in the controller before any code runs: bad input gets a 400. */
    public record CreateEmployeeRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9-]+$",
                    message = "must contain only A-Z, 0-9 and '-'") String employeeCode,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 100) String department,
            @Size(max = 100) String jobTitle,
            @NotNull @PastOrPresent LocalDate hireDate,
            UUID managerId) {}

    /** {@code managerId: null} removes the manager. */
    public record ChangeManagerRequest(UUID managerId) {}

    public record EmployeeResponse(
            UUID id,
            String employeeCode,
            String firstName,
            String lastName,
            String email,
            String department,
            String jobTitle,
            LocalDate hireDate,
            UUID managerId,
            String managerName,
            Instant createdAt) {

        static EmployeeResponse from(Employee e) {
            Employee manager = e.getManager();
            return new EmployeeResponse(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(),
                    e.getEmail(), e.getDepartment(), e.getJobTitle(), e.getHireDate(),
                    manager == null ? null : manager.getId(),
                    manager == null ? null : manager.fullName(),
                    e.getCreatedAt());
        }
    }
}
