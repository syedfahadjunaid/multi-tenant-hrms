import { isRouteErrorResponse, Link, useRouteError } from "react-router";

import { Alert, FullPageMessage } from "../components/ui";

export function NotFoundPage() {
  return (
    <Alert tone="info">
      There's nothing here. <Link to="/" className="font-medium underline">Go to the home page</Link>
    </Alert>
  );
}

/** Shown when a page crashes while rendering, instead of a blank screen. */
export function RouteErrorPage() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : "An unexpected error occurred. Reloading the page usually helps.";
  return (
    <FullPageMessage title="Something went wrong" message={message}>
      <a href="/" className="text-sm font-medium text-indigo-600 underline">
        Back to the home page
      </a>
    </FullPageMessage>
  );
}
