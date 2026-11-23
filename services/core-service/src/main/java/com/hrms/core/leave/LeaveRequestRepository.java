package com.hrms.core.leave;

import com.hrms.core.employee.Employee;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every query is tenant-filtered automatically (@TenantId). */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    @EntityGraph(attributePaths = {"employee", "approver"})
    List<LeaveRequest> findTop100ByEmployeeOrderByStartDateDesc(Employee employee);

    /** A manager's queue: requests from their reports waiting for them. */
    @EntityGraph(attributePaths = {"employee", "approver"})
    List<LeaveRequest> findByApproverAndStatusOrderByStartDateAsc(Employee approver, LeaveStatus status);

    /** An employee's requests in one status, e.g. those still waiting for their manager. */
    @EntityGraph(attributePaths = "approver")
    List<LeaveRequest> findByEmployeeAndStatus(Employee employee, LeaveStatus status);

    /** HR's queue. */
    @EntityGraph(attributePaths = {"employee", "approver"})
    List<LeaveRequest> findByStatusOrderByStartDateAsc(LeaveStatus status);

    /** Does any of the employee's active requests overlap the given dates? */
    @Query("""
            select count(r) > 0 from LeaveRequest r
            where r.employee = :employee
              and r.status in :statuses
              and r.startDate <= :end and r.endDate >= :start""")
    boolean existsOverlapping(Employee employee, Collection<LeaveStatus> statuses, LocalDate start, LocalDate end);

    /** Working days of matching requests that start within [from, to]. */
    @Query("""
            select coalesce(sum(r.workingDays), 0) from LeaveRequest r
            where r.employee = :employee
              and r.type = :type
              and r.status in :statuses
              and r.startDate between :from and :to""")
    long sumWorkingDays(Employee employee, LeaveType type, Collection<LeaveStatus> statuses,
                        LocalDate from, LocalDate to);
}
