import { errorBodySchema } from "./schemas.ts";

/**
 * A refusal or failure from the control plane: its HTTP status and the reason its body gave, which
 * the console shows verbatim beside the thing refused. A refusal is a value the person should read,
 * not a crash.
 */
export class ControlPlaneError extends Error {
  readonly status: number;
  readonly reason: string;

  constructor(status: number, reason: string) {
    super(`control plane answered ${status}: ${reason}`);
    this.name = "ControlPlaneError";
    this.status = status;
    this.reason = reason;
  }

  /** Whether sending the same request again, unchanged, could succeed. */
  get retryable(): boolean {
    return this.status === 503 || this.status === 504;
  }

  /**
   * The individual problems of a refused descriptor. The control plane joins them into one message,
   * `invalid descriptor: a; b`; anything else is one problem, the whole reason.
   */
  get problems(): string[] {
    const prefix = "invalid descriptor: ";
    return this.reason.startsWith(prefix)
      ? this.reason.slice(prefix.length).split("; ").filter((p) => p.length > 0)
      : [this.reason];
  }

  static async from(response: Response): Promise<ControlPlaneError> {
    const text = await response.text().catch(() => "");
    let reason = text.trim() || response.statusText || `HTTP ${response.status}`;
    try {
      const body = errorBodySchema.safeParse(JSON.parse(text));
      if (body.success) reason = body.data.error;
    } catch {
      // Not JSON: a proxy's page, say. The status is still the truth.
    }
    return new ControlPlaneError(response.status, reason);
  }
}

/** The control plane could not be reached at all: no status, only the transport's failure. */
export class ControlPlaneUnreachable extends Error {
  constructor(url: string, cause: unknown) {
    super(`could not reach the control plane at ${url}: ${cause instanceof Error ? cause.message : String(cause)}`, {
      cause,
    });
    this.name = "ControlPlaneUnreachable";
  }
}

/** The request's access token was refused, and a fresh one was refused too: sign in again. */
export class SignInRequired extends Error {
  constructor() {
    super("sign-in required");
    this.name = "SignInRequired";
  }
}
