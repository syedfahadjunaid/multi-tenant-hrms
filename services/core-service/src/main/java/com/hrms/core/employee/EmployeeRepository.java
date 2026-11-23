package com.hrms.core.employee;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data generates the implementation at startup (findById, findAll(Pageable), save, ...).
 * No tenant parameter needed: Hibernate's @TenantId filter is applied automatically.
 */
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    /** Loads each employee's manager in the same query, avoiding one extra query per row (N+1). */
    @Override
    @EntityGraph(attributePaths = "manager")
    Page<Employee> findAll(Pageable pageable);

    Optional<Employee> findByEmail(String email);

    /**
     * {@code SELECT ... FOR UPDATE}: other transactions that want this row wait until we commit.
     * Used so two leave requests submitted at the same moment can't both pass the balance check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.id = :id")
    Optional<Employee> findByIdForUpdate(UUID id);

    /**
     * A Postgres advisory lock held until the transaction ends. Reporting-line changes in one
     * tenant run one at a time, so two concurrent edits (A reports to B, B reports to A) can't
     * each pass the loop check and together create a loop. Different tenants don't block each other.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(hashtext(:key))) as locked", nativeQuery = true)
    long lockReportingLines(String key);
}
