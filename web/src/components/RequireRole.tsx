import type { ReactNode } from "react";

import { useCurrentUser } from "../api/hooks";
import type { Role } from "../api/types";
import { Alert } from "./ui";

/**
 * Shows a page only to users with one of the given roles.
 *
 * This is for user experience only. Anyone can edit JavaScript in their own browser, so the
 * real check is the API's @PreAuthorize rule. Never rely on the UI for security.
 */
export function RequireRole({ anyOf, children }: { anyOf: Role[]; children: ReactNode }) {
  const user = useCurrentUser();
  if (!user.hasAnyRole(...anyOf)) {
    return <Alert tone="error">You don't have access to this page.</Alert>;
  }
  return <>{children}</>;
}
