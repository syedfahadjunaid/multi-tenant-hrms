import type { ReactNode } from "react";

import { ApiError } from "../api/client";
import { useMe } from "../api/hooks";
import { Button, FullPageMessage } from "../components/ui";
import { leavingPage, signIn } from "./session";

/**
 * Renders the app only for signed-in users. "Signed in" means the BFF has a session for this
 * browser, which we find out by asking for /api/me. Without a session that call gets 401, and
 * apiFetch sends the browser to sign in.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  // The BFF sends the browser here when Keycloak's answer was unusable. Asking again
  // automatically could loop forever, so the user decides.
  if (new URLSearchParams(window.location.search).get("signin") === "failed") {
    return (
      <FullPageMessage title="Sign-in failed" message="We couldn't complete your sign-in. Please try again.">
        <Button onClick={() => signIn("/")}>Try again</Button>
      </FullPageMessage>
    );
  }
  return <SessionGate>{children}</SessionGate>;
}

function SessionGate({ children }: { children: ReactNode }) {
  const me = useMe();

  if (me.isSuccess) {
    return <>{children}</>;
  }
  if (me.error && !(me.error instanceof ApiError && me.error.status === 401)) {
    return (
      <FullPageMessage title="Can't reach the HRMS" message={me.error.message}>
        <Button onClick={() => void me.refetch()}>Try again</Button>
      </FullPageMessage>
    );
  }
  return <FullPageMessage title={leavingPage() === "signout" ? "Signing you out…" : "Signing you in…"} busy />;
}
