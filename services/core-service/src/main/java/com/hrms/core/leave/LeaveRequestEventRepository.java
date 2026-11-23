package com.hrms.core.leave;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRequestEventRepository extends JpaRepository<LeaveRequestEvent, UUID> {

    List<LeaveRequestEvent> findByLeaveRequestOrderByOccurredAtAsc(LeaveRequest leaveRequest);
}
