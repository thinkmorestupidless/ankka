/** What the app shows for one read: what answered, or why nothing did. */
export interface Shown {
  ok: boolean;
  text: string;
}

/**
 * Reads one path of the interface's own address. `/api/…` is a mount, answered by the backend
 * through the platform's proxy; `/summary` is this project's server, which calls the backend itself.
 */
export async function read(path: string, fetchImpl: typeof fetch = fetch): Promise<Shown> {
  try {
    const answer = await fetchImpl(path);
    const text = await answer.text();
    return answer.ok ? { ok: true, text } : { ok: false, text: `${path} answered ${answer.status}: ${text}` };
  } catch {
    return { ok: false, text: `${path} did not answer` };
  }
}
