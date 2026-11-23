package com.hrms.core.employee;

import java.util.UUID;

/**
 * Domain event: an employee's manager changed (null = no manager any more).
 *
 * <p>Published inside the transaction that made the change. Other modules react to it without
 * the employee module knowing about them; the leave module, for example, hands pending
 * approvals to the new manager. Because listeners run in the same transaction, the reorg and
 * its consequences are committed together. (The same event could later go to Kafka.)
 */
public record ManagerChanged(UUID employeeId, UUID previousManagerId, UUID newManagerId) {}
