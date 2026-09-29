/**
 * Following a log that can only be re-read, never resumed: the control plane answers an instance's
 * recent output as text, with no cursor and no line identity. Each read covers a window that overlaps
 * the last; the lines already sent are found as the longest run at the end of what was sent that
 * matches the start of the new window, and only what follows it is new.
 *
 * The one thing this cannot see is a line repeated identically inside one overlap: it may be dropped
 * or sent twice. A service that timestamps its own log lines makes following exact.
 */
export class LogFollower {
  readonly #keep: number;
  readonly #sent = new Map<string, string[]>();

  constructor(keep = 200) {
    this.#keep = keep;
  }

  /** The lines of `window` not yet sent for `instance`, remembered as sent. */
  next(instance: string, window: string[]): string[] {
    const sent = this.#sent.get(instance) ?? [];
    const overlap = LogFollower.overlap(sent, window);
    const fresh = window.slice(overlap);
    this.#sent.set(instance, [...sent, ...fresh].slice(-this.#keep));
    return fresh;
  }

  /** How many lines at the start of `window` are the last lines of `sent`. */
  static overlap(sent: string[], window: string[]): number {
    for (let n = Math.min(sent.length, window.length); n > 0; n--) {
      let match = true;
      for (let i = 0; i < n; i++) {
        if (sent[sent.length - n + i] !== window[i]) {
          match = false;
          break;
        }
      }
      if (match) return n;
    }
    return 0;
  }
}

export const lines = (output: string): string[] => output.split("\n").filter((l, i, all) => l.length > 0 || i < all.length - 1);
