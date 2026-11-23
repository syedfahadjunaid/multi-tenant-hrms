import "./index.css";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { ApiError } from "./api/client";
import { App } from "./App";
import { AuthGate } from "./auth/AuthGate";
import { restorePageAfterSignIn } from "./auth/session";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // Retry network blips and 5xx, never 4xx: a 403 won't fix itself by asking again.
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof ApiError && error.status < 500),
    },
  },
});

const root = document.getElementById("root");
if (!root) throw new Error("#root element missing from index.html");

// Before the router reads the URL: after a sign-in, go back to the page the user was on.
restorePageAfterSignIn();

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthGate>
        <App />
      </AuthGate>
    </QueryClientProvider>
  </StrictMode>,
);
