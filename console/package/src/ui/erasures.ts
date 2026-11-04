/** What the erasure pages share (feature 042). */

/**
 * A not-before date as a date field gives it (`YYYY-MM-DD`), as the instant the control plane takes:
 * the start of that day, UTC. Empty is no hold. A value that is not a date is passed on as it is, for
 * the control plane to refuse in its own words.
 */
export function notBeforeOf(date: string): string | undefined {
  if (!date) return undefined;
  const at = new Date(`${date}T00:00:00Z`);
  return Number.isNaN(at.getTime()) ? date : at.toISOString();
}

/** The not-before date as a date field shows it: the instant's day, UTC. */
export function dateOf(instant: string | undefined): string | undefined {
  return instant ? instant.slice(0, 10) : undefined;
}
