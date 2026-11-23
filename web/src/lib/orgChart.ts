import type { Employee } from "../api/types";

/**
 * Who could become this employee's manager without creating a loop: everyone except the
 * employee and the people below them (direct and indirect reports). The server checks the same
 * rule; filtering here just means the UI never offers an option that's going to be rejected.
 */
export function eligibleManagers(employeeId: string, everyone: Employee[]): Employee[] {
  const reportsOf = new Map<string, string[]>();
  for (const e of everyone) {
    if (e.managerId) reportsOf.set(e.managerId, [...(reportsOf.get(e.managerId) ?? []), e.id]);
  }

  // Breadth-first walk down the org chart from the employee.
  const excluded = new Set([employeeId]);
  const toVisit = [employeeId];
  for (let id = toVisit.pop(); id !== undefined; id = toVisit.pop()) {
    for (const report of reportsOf.get(id) ?? []) {
      if (!excluded.has(report)) {
        excluded.add(report);
        toVisit.push(report);
      }
    }
  }
  return everyone.filter((e) => !excluded.has(e.id));
}
