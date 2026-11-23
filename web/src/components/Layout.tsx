import { NavLink, Outlet } from "react-router";

import { useCurrentUser } from "../api/hooks";
import { ROLE_LABELS, type Role } from "../api/types";
import { signOut } from "../auth/session";
import { Alert, Badge, Button, Spinner } from "./ui";

type NavItem = { to: string; label: string; roles?: Role[] };

const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Home" },
  { to: "/leave", label: "Leave" },
  { to: "/employees", label: "Employees", roles: ["HR_ADMIN", "MANAGER"] },
  { to: "/assistant", label: "Policy assistant" },
];

export function Layout() {
  const user = useCurrentUser();
  const visibleItems = NAV_ITEMS.filter((item) => !item.roles || user.hasAnyRole(...item.roles));

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4 sm:px-6">
          <div className="flex items-center gap-2 font-semibold">
            <img src="/favicon.svg" alt="" className="size-7" />
            HRMS
          </div>

          <nav aria-label="Main" className="flex gap-1">
            {visibleItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === "/"}
                className={({ isActive }) =>
                  `rounded-md px-3 py-2 text-sm font-medium ${
                    isActive ? "bg-indigo-50 text-indigo-700" : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {user.me.data && (
              <div className="hidden text-right sm:block">
                <div className="text-sm font-medium">{user.displayName}</div>
                <div className="flex justify-end gap-1">
                  <Badge tone="indigo">{user.tenantId}</Badge>
                  {user.roles
                    .filter((role): role is Role => role in ROLE_LABELS)
                    .map((role) => (
                      <Badge key={role}>{ROLE_LABELS[role]}</Badge>
                    ))}
                </div>
              </div>
            )}
            <Button variant="secondary" onClick={() => void signOut()}>
              Sign out
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        {user.me.isPending ? (
          <Spinner />
        ) : user.me.isError ? (
          <Alert tone="error">Couldn't load your profile: {user.me.error.message}</Alert>
        ) : (
          <Outlet />
        )}
      </main>
    </div>
  );
}
