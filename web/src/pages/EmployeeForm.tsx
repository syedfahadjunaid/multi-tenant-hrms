import type { FormEvent } from "react";

import { ApiError } from "../api/client";
import { useAllEmployees, useCreateEmployee } from "../api/hooks";
import { todayIso } from "../lib/dates";
import type { Employee, NewEmployee } from "../api/types";
import { Alert, Button, Field, inputClass } from "../components/ui";

/**
 * The browser checks the simple rules (required, max length, email format) before submitting.
 * The server validates everything again and returns per-field errors, which are shown under
 * the matching input. Client-side checks are convenience, the server's are the real ones.
 */
export function EmployeeForm({
  onCreated,
  onCancel,
}: {
  onCreated: (employee: Employee) => void;
  onCancel: () => void;
}) {
  const create = useCreateEmployee();
  const everyone = useAllEmployees();
  const apiError = create.error instanceof ApiError ? create.error : null;
  const today = todayIso();
  const managerError = apiError?.messageFor("managerId");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const optional = (name: string) => text(name) || null;

    const employee: NewEmployee = {
      employeeCode: text("employeeCode").toUpperCase(),
      firstName: text("firstName"),
      lastName: text("lastName"),
      email: text("email"),
      department: optional("department"),
      jobTitle: optional("jobTitle"),
      hireDate: text("hireDate"),
      managerId: optional("managerId"),
    };
    create.mutate(employee, { onSuccess: onCreated });
  }

  function input(name: keyof NewEmployee, label: string, props: Record<string, unknown> = {}, hint?: string) {
    const error = apiError?.messageFor(name);
    return (
      <Field label={label} htmlFor={name} hint={hint} error={error}>
        <input
          id={name}
          name={name}
          className={inputClass}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${name}-error` : undefined}
          {...props}
        />
      </Field>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      <div className="grid gap-4 sm:grid-cols-2">
        {input("firstName", "First name", { required: true, maxLength: 100, autoComplete: "off" })}
        {input("lastName", "Last name", { required: true, maxLength: 100, autoComplete: "off" })}
        {input("email", "Work email", { type: "email", required: true, maxLength: 255, autoComplete: "off" })}
        {input(
          "employeeCode",
          "Employee code",
          { required: true, maxLength: 32, pattern: "[A-Za-z0-9\\-]+" },
          "Letters, digits and dashes, e.g. ACME-002",
        )}
        {input("department", "Department", { maxLength: 100 })}
        {input("jobTitle", "Job title", { maxLength: 100 })}
        {input("hireDate", "Hire date", { type: "date", required: true, max: today })}
        <Field
          label="Manager"
          htmlFor="managerId"
          error={managerError}
          hint="Approves their leave first. Without a manager, leave goes straight to HR."
        >
          <select id="managerId" name="managerId" className={inputClass} defaultValue="">
            <option value="">No manager</option>
            {everyone.data?.items.map((e) => (
              <option key={e.id} value={e.id}>
                {e.firstName} {e.lastName}
                {e.jobTitle ? ` (${e.jobTitle})` : ""}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {apiError && apiError.fieldErrors.length === 0 && <Alert tone="error">{apiError.message}</Alert>}

      <div className="flex gap-2">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? "Saving…" : "Save employee"}
        </Button>
        <Button variant="ghost" onClick={onCancel} disabled={create.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
