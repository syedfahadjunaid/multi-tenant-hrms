import { csrfHeaders, signIn } from "../auth/session";

export type InvalidField = { field: string; message: string };

/** An API call that failed. Carries what the UI needs to explain it to the user. */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: InvalidField[];
  readonly retryAfterSeconds?: number;

  constructor(status: number, message: string, fieldErrors: InvalidField[] = [], retryAfterSeconds?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  messageFor(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message;
  }
}

type ProblemDetail = { title?: string; detail?: string; errors?: InvalidField[] };

type RequestOptions = {
  method?: "GET" | "POST" | "PUT";
  body?: unknown;
  signal?: AbortSignal;
};

/**
 * fetch() wrapper for the core API: sends/parses JSON, adds the CSRF header to requests that
 * change something, and turns RFC 9457 problem responses into {@link ApiError}s.
 *
 * There is no token handling here on purpose. Calls go to the BFF on this origin, the browser
 * sends its HttpOnly session cookie, and the BFF adds the access token (see auth/session.ts).
 */
export async function apiFetch<T>(path: string, { method = "GET", body, signal }: RequestOptions = {}): Promise<T> {
  const response = await fetch(path, {
    method,
    signal,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(method === "GET" ? {} : csrfHeaders()),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 401) {
    // No BFF session (never signed in, timed out, or Keycloak ended it).
    signIn();
    throw new ApiError(401, "Your session has expired. Signing you in again…");
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail = {};
  try {
    problem = (await response.json()) as ProblemDetail;
  } catch {
    // Some errors (e.g. 403 from Spring Security) have no body.
  }
  const retryAfter = Number(response.headers.get("Retry-After")) || undefined;
  return new ApiError(
    response.status,
    problem.detail ?? defaultMessage(response.status),
    Array.isArray(problem.errors) ? problem.errors : [],
    retryAfter,
  );
}

function defaultMessage(status: number): string {
  if (status === 403) return "You don't have permission to do that.";
  if (status === 404) return "That item doesn't exist.";
  if (status === 429) return "Too many requests. Please wait a moment.";
  if (status >= 500) return "The server had a problem. Please try again.";
  return `Request failed (${status}).`;
}
