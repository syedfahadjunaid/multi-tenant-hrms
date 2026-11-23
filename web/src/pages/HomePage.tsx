import { Link } from "react-router";

import { useCurrentUser } from "../api/hooks";
import { ROLE_LABELS, type Role } from "../api/types";
import { Badge, Card, PageHeader } from "../components/ui";

type Shortcut = { to: string; title: string; description: string; roles?: Role[] };

const SHORTCUTS: Shortcut[] = [
  {
    to: "/leave",
    title: "Leave",
    description: "Request time off, check your balance, and approve your team's requests.",
  },
  {
    to: "/employees",
    title: "Employees",
    description: "Browse your company's employee directory and add new hires.",
    roles: ["HR_ADMIN", "MANAGER"],
  },
  {
    to: "/assistant",
    title: "Policy assistant",
    description: "Ask questions about HR policies. Answers only use documents you're allowed to see.",
  },
];

export function HomePage() {
  const user = useCurrentUser();
  const shortcuts = SHORTCUTS.filter((s) => !s.roles || user.hasAnyRole(...s.roles));

  return (
    <>
      <PageHeader title={`Welcome, ${user.firstName}`} description="Here's what you can do today." />

      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <h2 className="text-sm font-semibold text-slate-500">Your access</h2>
          <dl className="mt-3 space-y-3 text-sm">
            <div>
              <dt className="text-slate-500">Company (tenant)</dt>
              <dd className="mt-0.5 font-medium">{user.tenantId}</dd>
            </div>
            <div>
              <dt className="text-slate-500">Roles</dt>
              <dd className="mt-1 flex flex-wrap gap-1">
                {user.roles
                  .filter((role): role is Role => role in ROLE_LABELS)
                  .map((role) => (
                    <Badge key={role}>{ROLE_LABELS[role]}</Badge>
                  ))}
              </dd>
            </div>
          </dl>
        </Card>

        {shortcuts.map((s) => (
          <Link
            key={s.to}
            to={s.to}
            className="group rounded-xl bg-white p-5 shadow-sm ring-1 ring-slate-200 transition hover:ring-indigo-300"
          >
            <h2 className="font-semibold group-hover:text-indigo-700">{s.title} →</h2>
            <p className="mt-2 text-sm text-slate-600">{s.description}</p>
          </Link>
        ))}
      </div>
    </>
  );
}
