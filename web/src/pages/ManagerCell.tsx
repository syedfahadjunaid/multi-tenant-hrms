import { useState } from "react";

import { useChangeManager } from "../api/hooks";
import type { Employee } from "../api/types";
import { Button, inputClass } from "../components/ui";
import { eligibleManagers } from "../lib/orgChart";

/** The Manager column: shows the manager, and lets HR admins change it in place. */
export function ManagerCell({
  employee,
  everyone,
  canEdit,
  onChanged,
}: {
  employee: Employee;
  everyone: Employee[];
  canEdit: boolean;
  onChanged: (updated: Employee) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [choice, setChoice] = useState(employee.managerId ?? "");
  const change = useChangeManager();
  const name = `${employee.firstName} ${employee.lastName}`;

  if (!editing) {
    return (
      <div className="flex items-center gap-2 whitespace-nowrap">
        <span>{employee.managerName ?? "—"}</span>
        {canEdit && (
          <button
            type="button"
            className="text-xs font-medium text-indigo-600 hover:underline"
            aria-label={`Change manager for ${name}`}
            onClick={() => {
              setChoice(employee.managerId ?? "");
              setEditing(true);
            }}
          >
            Change
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="flex min-w-64 flex-wrap items-center gap-2">
      <select
        aria-label={`New manager for ${name}`}
        className={`${inputClass} w-auto`}
        value={choice}
        onChange={(e) => setChoice(e.target.value)}
      >
        <option value="">No manager (leave goes to HR)</option>
        {eligibleManagers(employee.id, everyone).map((m) => (
          <option key={m.id} value={m.id}>
            {m.firstName} {m.lastName}
          </option>
        ))}
      </select>
      <Button
        disabled={change.isPending || choice === (employee.managerId ?? "")}
        onClick={() =>
          change.mutate(
            { employeeId: employee.id, managerId: choice || null },
            {
              onSuccess: (updated) => {
                setEditing(false);
                onChanged(updated);
              },
            },
          )
        }
      >
        {change.isPending ? "Saving…" : "Save"}
      </Button>
      <Button
        variant="ghost"
        onClick={() => {
          setEditing(false);
          change.reset();
        }}
      >
        Cancel
      </Button>
      {change.error && <p className="w-full text-xs text-red-600">{change.error.message}</p>}
    </div>
  );
}
