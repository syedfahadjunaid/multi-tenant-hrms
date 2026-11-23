import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn(), signIn: vi.fn() }));

// Replace only apiFetch and signIn; keep the real ApiError class and the rest.
vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/client")>()),
  apiFetch: mocks.apiFetch,
}));
vi.mock("./session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./session")>()),
  signIn: mocks.signIn,
}));

import { ApiError } from "../api/client";
import { AuthGate } from "./AuthGate";

function renderGate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthGate>
        <p>the app</p>
      </AuthGate>
    </QueryClientProvider>,
  );
}

describe("AuthGate", () => {
  beforeEach(() => window.history.replaceState(null, "", "/"));
  afterEach(() => vi.resetAllMocks());

  it("shows the app once the BFF confirms the session", async () => {
    mocks.apiFetch.mockResolvedValue({ username: "bob", roles: ["EMPLOYEE"] });

    renderGate();

    expect(await screen.findByText("the app")).toBeInTheDocument();
  });

  it("keeps the app hidden while a sign-in is under way", async () => {
    mocks.apiFetch.mockRejectedValue(new ApiError(401, "Signing you in again…"));

    renderGate();

    expect(await screen.findByText("Signing you in…")).toBeInTheDocument();
    expect(screen.queryByText("the app")).not.toBeInTheDocument();
  });

  it("asks before retrying a failed sign-in, instead of looping", async () => {
    window.history.replaceState(null, "", "/?signin=failed");

    renderGate();

    expect(screen.getByText("Sign-in failed")).toBeInTheDocument();
    expect(mocks.apiFetch).not.toHaveBeenCalled();
    screen.getByRole("button", { name: "Try again" }).click();
    expect(mocks.signIn).toHaveBeenCalledWith("/");
  });
});
