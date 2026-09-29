/**
 * Every open update stream in this process, so shutdown can tell each one to reconnect elsewhere.
 *
 * Kept on `globalThis` under a registered symbol rather than in module state: the host's server and
 * its app bundle each hold their own copy of this package, and both must see the same set.
 */
const key = Symbol.for("ankka-console.open-streams");

type Closer = () => void;

function registry(): Set<Closer> {
  const g = globalThis as unknown as Record<symbol, Set<Closer> | undefined>;
  return (g[key] ??= new Set());
}

export function registerStream(close: Closer): () => void {
  const set = registry();
  set.add(close);
  return () => set.delete(close);
}

/** Tells every open stream the server is closing; returns how many there were. */
export function closeAllStreams(): number {
  const set = registry();
  const n = set.size;
  for (const close of [...set]) {
    try {
      close();
    } catch {
      // A stream whose client already left has nothing to be told.
    }
  }
  set.clear();
  return n;
}

export function openStreamCount(): number {
  return registry().size;
}
