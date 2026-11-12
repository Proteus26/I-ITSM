export const SERVER_ERROR_MESSAGE = 'Something went wrong on the server. Try again.';

/**
 * Errors from the backend. The backend answers with RFC 9457 `application/problem+json`:
 * `{ title, status, detail }`, plus `errors: { field: message }` when `title` is "ValidationError".
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail: string,
    readonly fieldErrors: Readonly<Record<string, string>> = {},
    /** True when the backend could not be reached at all (no HTTP response). */
    readonly unreachable: boolean = false,
  ) {
    super(detail || title);
    this.name = 'ApiError';
  }

  /** The backend failed (5xx) or could not be reached. */
  get isServerError(): boolean {
    return this.unreachable || this.status >= 500;
  }

  /** Safe to show to users: server-side details are never exposed. */
  get userMessage(): string {
    return this.isServerError ? SERVER_ERROR_MESSAGE : this.detail;
  }

  /** Message for the `field`, if the backend reported one. */
  fieldError(field: string): string | undefined {
    return this.fieldErrors[field];
  }

  /** Builds an ApiError from an already-parsed body; tolerates anything (non-object, missing fields). */
  static fromBody(status: number, body: unknown, statusText = ''): ApiError {
    const fallbackTitle = statusText || 'HttpError';
    if (typeof body !== 'object' || body === null || Array.isArray(body)) {
      return new ApiError(status, fallbackTitle, `Request failed with status ${status}`);
    }
    const b = body as Record<string, unknown>;
    const title = typeof b.title === 'string' && b.title ? b.title : fallbackTitle;
    const detail = typeof b.detail === 'string' && b.detail ? b.detail : `Request failed with status ${status}`;
    const fieldErrors: Record<string, string> = {};
    if (typeof b.errors === 'object' && b.errors !== null && !Array.isArray(b.errors)) {
      for (const [field, message] of Object.entries(b.errors)) {
        fieldErrors[field] = String(message);
      }
    }
    return new ApiError(status, title, detail, fieldErrors);
  }

  /** Parses an error response; falls back to a generic error when the body is not JSON. */
  static async fromResponse(res: Response): Promise<ApiError> {
    let body: unknown = undefined;
    try {
      body = JSON.parse(await res.text());
    } catch {
      // empty or non-JSON body (e.g. an HTML error page from a proxy)
    }
    return ApiError.fromBody(res.status, body, res.statusText);
  }

  /** The error thrown when the backend cannot be reached at all. */
  static unreachable(): ApiError {
    return new ApiError(502, 'BadGateway', 'The server is unreachable. Please try again later.', {}, true);
  }
}

/** A problem+json Response, used by the proxy for errors it generates itself. */
export function problemResponse(status: number, title: string, detail: string): Response {
  return new Response(JSON.stringify({ title, status, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}
