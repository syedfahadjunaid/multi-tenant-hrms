import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { safeReturnTo } from "./session";

describe("sign-in / sign-out", () => {
  // Fresh module per test: the "already leaving the page" flag is module state.
  beforeEach(() => {
    vi.resetModules();
    sessionStorage.clear();
    document.cookie = "HRMS-XSRF-TOKEN=csrf-123; path=/";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "HRMS-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  async function freshModule() {
    const session = await import("./session");
    const assign = vi.spyOn(session.browser, "assign").mockImplementation(() => undefined);
    return { ...session, assign };
  }

  it("goes to the BFF's sign-in once, however many requests ask for it", async () => {
    const session = await freshModule();

    session.signIn("/leave");
    session.signIn("/employees");
    session.signIn();

    expect(session.assign).toHaveBeenCalledOnce();
    expect(session.assign).toHaveBeenCalledWith("/oauth2/authorization/keycloak");
    expect(sessionStorage.getItem("hrms.returnTo")).toBe("/leave");
  });

  it("signs out with the CSRF header, then goes to Keycloak's logout page", async () => {
    const session = await freshModule();
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ logoutUrl: "http://localhost:8180/realms/hrms/logout?x=1" }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await session.signOut();

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/logout");
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("csrf-123");
    expect(session.assign).toHaveBeenCalledWith("http://localhost:8180/realms/hrms/logout?x=1");
  });

  it("doesn't let a sign-in cancel a sign-out that is under way", async () => {
    const session = await freshModule();
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response("{}", { status: 200 })));

    const signingOut = session.signOut();
    session.signIn();
    await signingOut;

    expect(session.assign).toHaveBeenCalledOnce();
    expect(session.assign).toHaveBeenCalledWith("/");
    expect(session.leavingPage()).toBe("signout");
  });

  it("allows another try when sign-out fails", async () => {
    const session = await freshModule();
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 403 })));

    await expect(session.signOut()).rejects.toThrow("Sign-out failed (403)");

    expect(session.leavingPage()).toBeNull();
  });

  it("returns to the remembered page after sign-in, once", async () => {
    const session = await freshModule();
    window.history.replaceState(null, "", "/");
    sessionStorage.setItem("hrms.returnTo", "/leave?tab=approvals");

    session.restorePageAfterSignIn();

    expect(window.location.pathname + window.location.search).toBe("/leave?tab=approvals");
    expect(sessionStorage.getItem("hrms.returnTo")).toBeNull();
    window.history.replaceState(null, "", "/");
  });

  it("never returns to another site", async () => {
    const session = await freshModule();
    window.history.replaceState(null, "", "/");
    sessionStorage.setItem("hrms.returnTo", "//evil.example/phish");

    session.restorePageAfterSignIn();

    expect(window.location.pathname).toBe("/");
  });
});

describe("safeReturnTo (open-redirect protection)", () => {
  it.each(["/", "/employees", "/assistant?q=leave"])("allows the same-site path %s", (path) => {
    expect(safeReturnTo(path)).toBe(path);
  });

  it.each([
    "https://evil.example/phish",
    "//evil.example/phish", // protocol-relative URL: leaves the site
    "/\\evil.example", // some browsers treat backslashes like slashes
    "javascript:alert(1)",
    undefined,
    42,
  ])("falls back to / for %s", (path) => {
    expect(safeReturnTo(path)).toBe("/");
  });
});
