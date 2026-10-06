/** How a deployed service's own code is hosted, in words: the facts list and the shape both say it. */
export function runsAs(s: { hosting: string; protocol?: string; processPort?: number }): string {
  if (s.hosting === "web") return `Your program beside the platform's proxy${s.processPort ? `, on port ${s.processPort}` : ""}`;
  if (s.hosting === "process") return `A process beside the platform's sidecar${s.protocol ? `, protocol ${s.protocol}` : ""}`;
  if (s.hosting === "wasm") return `A module loaded into the platform's runtime${s.protocol ? `, protocol ${s.protocol}` : ""}`;
  return "Embedded in the platform's runtime";
}

/** The same, short enough for a part of the shape. */
export function hostedAs(s: { hosting: string; processPort?: number }): string {
  if (s.hosting === "web") return s.processPort ? `Your program, port ${s.processPort}` : "Your program";
  if (s.hosting === "process") return "A process beside the sidecar";
  if (s.hosting === "wasm") return "A module in the runtime";
  return "In the platform's runtime";
}
