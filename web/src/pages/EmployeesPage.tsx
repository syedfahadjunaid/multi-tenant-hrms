import { useState } from "react";

import { useAllEmployees, useCurrentUser, useEmployees } from "../api/hooks";
import type { Employee } from "../api/types";
import { Alert, Button, Card, PageHeader, Spinner } from "../components/ui";
import { formatDate } from "../lib/dates";
import { EmployeeForm } from "./EmployeeForm";
import { ManagerCell } from "./ManagerCell";

export function EmployeesPage() {
  const user = useCurrentUser();
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const employees = useEmployees(page);

  const isHr = user.hasAnyRole("HR_ADMIN");
  // HR needs the whole company to pick managers from, not just the current page.
  const everyone = useAllEmployees(isHr);
  const data = employees.data;

  function managerChanged(updated: Employee) {
    const name = `${updated.firstName} ${updated.lastName}`;
    setNotice(
      updated.managerName
        ? `${name} now reports to ${updated.managerName}. Leave waiting for their old manager moved too.`
        : `${name} no longer has a manager. Their leave requests now go straight to HR.`,
    );
  }

  return (
    <>
      <PageHeader
        title="Employees"
        description={data ? `${data.totalItems} people at ${user.tenantId}` : undefined}
        actions={
          isHr &&
          !adding && (
            <Button
              onClick={() => {
                setAdding(true);
                setNotice(null);
              }}
            >
              Add employee
            </Button>
          )
        }
      />

      <div className="space-y-4">
        {adding && (
          <Card>
            <h2 className="mb-4 font-semibold">New employee</h2>
            <EmployeeForm
              onCancel={() => setAdding(false)}
              onCreated={(employee) => {
                setAdding(false);
                setNotice(`Added ${employee.firstName} ${employee.lastName} (${employee.employeeCode}).`);
              }}
            />
          </Card>
        )}

        {notice && <Alert tone="success">{notice}</Alert>}

        {employees.isError && (
          <Alert tone="error">Couldn't load employees: {employees.error.message}</Alert>
        )}

        <Card className="overflow-hidden p-0">
          {employees.isPending ? (
            <div className="p-6">
              <Spinner label="Loading employees" />
            </div>
          ) : data && data.items.length === 0 ? (
            <p className="p-6 text-sm text-slate-500">
              No employees yet.{isHr && " Use “Add employee” to create the first one."}
            </p>
          ) : (
            data && (
              <EmployeeTable
                employees={data.items}
                everyone={everyone.data?.items ?? []}
                canEditManagers={isHr && everyone.isSuccess}
                onManagerChanged={managerChanged}
              />
            )
          )}

          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3 text-sm">
              <span className="text-slate-500">
                Page {data.page + 1} of {data.totalPages}
              </span>
              <div className="flex gap-2">
                <Button variant="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <Button
                  variant="secondary"
                  disabled={page + 1 >= data.totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}

function EmployeeTable({
  employees,
  everyone,
  canEditManagers,
  onManagerChanged,
}: {
  employees: Employee[];
  everyone: Employee[];
  canEditManagers: boolean;
  onManagerChanged: (updated: Employee) => void;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50 text-left text-xs font-semibold tracking-wide text-slate-500 uppercase">
          <tr>
            <th scope="col" className="px-4 py-3">Name</th>
            <th scope="col" className="px-4 py-3">Code</th>
            <th scope="col" className="px-4 py-3">Email</th>
            <th scope="col" className="px-4 py-3">Department</th>
            <th scope="col" className="px-4 py-3">Job title</th>
            <th scope="col" className="px-4 py-3">Manager</th>
            <th scope="col" className="px-4 py-3">Hire date</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {employees.map((e) => (
            <tr key={e.id} className="hover:bg-slate-50">
              <td className="px-4 py-3 font-medium whitespace-nowrap">
                {e.firstName} {e.lastName}
              </td>
              <td className="px-4 py-3 font-mono text-xs text-slate-600">{e.employeeCode}</td>
              <td className="px-4 py-3 text-slate-600">{e.email}</td>
              <td className="px-4 py-3 text-slate-600">{e.department ?? "—"}</td>
              <td className="px-4 py-3 text-slate-600">{e.jobTitle ?? "—"}</td>
              <td className="px-4 py-3 text-slate-600">
                <ManagerCell
                  employee={e}
                  everyone={everyone}
                  canEdit={canEditManagers}
                  onChanged={onManagerChanged}
                />
              </td>
              <td className="px-4 py-3 whitespace-nowrap text-slate-600">{formatDate(e.hireDate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
