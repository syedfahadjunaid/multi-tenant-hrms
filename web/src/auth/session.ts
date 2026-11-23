/**
 * Sign-in and sign-out, handled by the backend-for-frontend (BFF, services/bff).
 *
 * The SPA never sees an OAuth2 token. Signing in is a full-page visit to the BFF, which runs
 * the authorization-code flow with Keycloak (as a confidential client, with PKCE) and gives
 * the browser an HttpOnly session cookie. JavaScript can't read that cookie, so even an XSS
 * bug couldn't steal the tokens behind it. API calls simply go to /api/* on this origin: the
 * browser sends the cookie, and the BFF adds the access token before forwarding the call.
 *
 * Because the browser sends that cookie automatically, the BFF also requires a CSRF token on
 * every state-changing request: it sets a readable cookie, and we copy it into a header,
 * which a page on another site cannot do.
 */

/** Where the BFF starts a sign-in (Spring Security's authorization endpoint). */
export const SIGN_IN_URL = "/oauth2/authorization/keycloak";
const CSRF_COOKIE = "HRMS-XSRF-TOKEN";
const RETURN_TO_KEY = "hrms.returnTo";

/** Page navigation, as an object so tests can replace it (jsdom can't really navigate). */
export const browser = {
  assign: (url: string) => window.location.assign(url),
};

/**
 * Set once we've started sending the browser away. The page is about to unload, so nothing
 * else may start another redirect: signing out must not be cancelled by a sign-in that a
 * failing API call triggers, and several simultaneous 401s should cause one redirect.
 */
let leavingFor: "signin" | "signout" | null = null;

export function leavingPage() {
  return leavingFor;
}

/** Sends the browser to sign in, remembering which page the user was on. */
export function signIn(returnTo = window.location.pathname + window.location.search) {
  if (leavingFor) return;
  leavingFor = "signin";
  try {
    sessionStorage.setItem(RETURN_TO_KEY, safeReturnTo(returnTo));
  } catch {
    // Storage blocked: the user lands on the home page instead. Not worth failing for.
  }
  browser.assign(SIGN_IN_URL);
}

/**
 * Ends the BFF session, then the Keycloak session too (otherwise the next sign-in would
 * succeed without a password). The BFF answers with Keycloak's logout URL; going there
 * brings the user back to the app, signed out.
 */
export async function signOut() {
  if (leavingFor) return;
  leavingFor = "signout";
  try {
    const response = await fetch("/logout", { method: "POST", headers: csrfHeaders() });
    if (!response.ok) throw new Error(`Sign-out failed (${response.status})`);
    const { logoutUrl } = (await response.json()) as { logoutUrl?: string };
    browser.assign(logoutUrl ?? "/");
  } catch (error) {
    leavingFor = null;
    throw error;
  }
}

/** The header the BFF checks on POST/PUT/DELETE (the "double-submit cookie" pattern). */
export function csrfHeaders(): Record<string, string> {
  const prefix = `${CSRF_COOKIE}=`;
  const cookie = document.cookie.split("; ").find((c) => c.startsWith(prefix));
  return cookie ? { "X-XSRF-TOKEN": decodeURIComponent(cookie.slice(prefix.length)) } : {};
}

/**
 * Call once at start-up. After signing in, the BFF always lands on "/", so this goes back to
 * the page the user was on before (once; the stored path is removed).
 */
export function restorePageAfterSignIn() {
  let stored: string | null;
  try {
    stored = sessionStorage.getItem(RETURN_TO_KEY);
    sessionStorage.removeItem(RETURN_TO_KEY);
  } catch {
    return;
  }
  const returnTo = safeReturnTo(stored);
  if (returnTo !== "/" && window.location.pathname === "/" && !window.location.search) {
    window.history.replaceState(null, "", returnTo);
  }
}

/**
 * Only same-site paths are allowed as a post-login destination. Without this check a crafted
 * value could bounce the user to another site after sign-in (an "open redirect").
 */
export function safeReturnTo(path: unknown): string {
  if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//") || path.includes("\\")) {
    return "/";
  }
  return path;
}
