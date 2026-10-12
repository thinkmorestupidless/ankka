import type { GrantTarget } from "../client/schemas.ts";

/** A grant's target in one line, as the CLI shows it: `route wallet POST /v1/…`, `topic casino.players consume`. */
export function targetText(t: GrantTarget): string {
  switch (t.kind) {
    case "route":
      return `route ${t.service ?? ""} ${t.method ?? ""} ${t.path ?? ""}`;
    case "method":
      return `method ${t.service ?? ""} ${t.method ?? ""}`;
    case "topic":
      return `topic ${t.topic ?? ""} ${t.right ?? ""}${t.decrypt ? " decrypt" : ""}`;
    default:
      return t.kind;
  }
}
