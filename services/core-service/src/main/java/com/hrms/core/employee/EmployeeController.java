package com.hrms.core.employee;

import com.hrms.core.common.PageResponse;
import com.hrms.core.employee.EmployeeDtos.ChangeManagerRequest;
import com.hrms.core.employee.EmployeeDtos.CreateEmployeeRequest;
import com.hrms.core.employee.EmployeeDtos.EmployeeResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for employees. Authorization is declared per method with
 * {@code @PreAuthorize}, so the rule sits right next to the code it protects.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'MANAGER')")
    public PageResponse<EmployeeResponse> list(
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'MANAGER')")
    public EmployeeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/employees/" + created.id())).body(created);
    }

    /** PUT: setting the manager is idempotent, sending the same request twice changes nothing. */
    @PutMapping("/{id}/manager")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public EmployeeResponse changeManager(@PathVariable UUID id, @RequestBody ChangeManagerRequest request) {
        return service.changeManager(id, request.managerId());
    }
}
