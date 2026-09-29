/**
 * A service's lifecycle as a word and a shape. The shape carries the state as well as the colour
 * does, so nothing depends on seeing colour: filled for running, half for partly, hollow for not
 * yet, a bar for stopped on purpose, a triangle for trouble.
 */
const known: Record<string, { mark: string; tone: string; words: string }> = {
  Ready: { mark: "●", tone: "ready", words: "Ready" },
  PartiallyReady: { mark: "◐", tone: "partial", words: "Partially ready" },
  UpdateInProgress: { mark: "◌", tone: "moving", words: "Updating" },
  NotDeployed: { mark: "○", tone: "idle", words: "Not deployed" },
  Paused: { mark: "▮▮", tone: "stopped", words: "Paused" },
  Suspended: { mark: "▮▮", tone: "stopped", words: "Suspended" },
  Unavailable: { mark: "▲", tone: "trouble", words: "Unavailable" },
  Failed: { mark: "▲", tone: "trouble", words: "Failed" },
};

export function lifecycleWords(lifecycle: string): string {
  return known[lifecycle]?.words ?? lifecycle;
}

export function Lifecycle({ lifecycle, confirmed = true }: { lifecycle: string; confirmed?: boolean }) {
  const k = known[lifecycle] ?? { mark: "○", tone: "idle", words: lifecycle };
  return (
    <span className={`ac-status ac-status-${k.tone}`} data-lifecycle={lifecycle}>
      <span className="ac-status-mark" aria-hidden="true">
        {k.mark}
      </span>
      {k.words}
      {confirmed ? null : <span className="ac-status-note"> (last known)</span>}
    </span>
  );
}
