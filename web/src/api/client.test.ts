import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// vi.mock is hoisted above the imports, so shared mocks must be created with vi.hoisted.
const mocks = vi.hoisted(() => ({ signIn: vi.fn() }));
vi.mock("../auth/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../auth/session")>()),
  signIn: mocks.signIn,
}));

import { ApiError, apiFetch } from "./client";

const fetchMock = vi.fn<typeof fetch>();

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

describe("apiFetch", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    document.cookie = "HRMS-XSRF-TOKEN=csrf-123; path=/";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetAllMocks();
    document.cookie = "HRMS-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("parses JSON and never sends a token itself (the BFF adds it)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { tenantId: "acme" }));

    const result = await apiFetch<{ tenantId: string }>("/api/me");

    expect(result.tenantId).toBe("acme");
    const [, init] = fetchMock.mock.calls[0]!;
    expect(init?.credentials).toBe("same-origin");
    expect(new Headers(init?.headers).has("Authorization")).toBe(false);
    expect(new Headers(init?.headers).has("X-XSRF-TOKEN")).toBe(false); // reads don't need it
  });

  it("adds the CSRF header to requests that change something", async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "1" }));

    await apiFetch("/api/leave/requests", { method: "POST", body: { type: "ANNUAL" } });

    const [, init] = fetchMock.mock.calls[0]!;
    expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("csrf-123");
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
  });

  it("turns problem details with field errors into an ApiError", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        title: "Bad Request",
        detail: "Some fields are invalid.",
        errors: [{ field: "email", message: "must be a well-formed email address" }],
      }),
    );

    const error = await apiFetch("/api/employees", { method: "POST", body: {} }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).messageFor("email")).toBe("must be a well-formed email address");
  });

  it("reads Retry-After when rate limited", async () => {
    fetchMock.mockResolvedValue(jsonResponse(429, { detail: "Rate limit exceeded." }, { "Retry-After": "42" }));

    const error = (await apiFetch("/api/assistant/ask").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(429);
    expect(error.retryAfterSeconds).toBe(42);
  });

  it("signs in again when there is no session", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    await expect(apiFetch("/api/me")).rejects.toMatchObject({ status: 401 });
    expect(mocks.signIn).toHaveBeenCalledOnce();
  });
});
