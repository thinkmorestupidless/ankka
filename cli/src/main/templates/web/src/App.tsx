import { useEffect, useState } from "react";
import { read, type Shown } from "./api.ts";

/** The interface: what the backend answered under the mount, and what the server read from it. */
export function App() {
  const [mounted, setMounted] = useState<Shown | undefined>();
  const [summary, setSummary] = useState<Shown | undefined>();

  useEffect(() => {
    void read("/api/").then(setMounted);
    void read("/summary").then(setSummary);
  }, []);

  return (
    <main>
      <h1>{"{{name}}"}</h1>
      <section>
        <h2>The backend, under the mount at /api</h2>
        <p data-testid="mounted">{mounted ? mounted.text : "asking…"}</p>
      </section>
      <section>
        <h2>The backend, as this project's server read it</h2>
        <p data-testid="summary">{summary ? summary.text : "asking…"}</p>
      </section>
    </main>
  );
}
