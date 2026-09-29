import { useActionData } from "react-router";
import type { ActionRefusal } from "../context.ts";
import { Refusal } from "./errors.tsx";

/** The refusal for this form's intent, if the last action was refused; the page shows it beside the form. */
export function useRefusal(intent: string): ActionRefusal | undefined {
  const result = useActionData() as ActionRefusal | undefined;
  return result && typeof result === "object" && "intent" in result && result.intent === intent && "reason" in result ? result : undefined;
}

export function Refused({ intent }: { intent: string }) {
  const refusal = useRefusal(intent);
  return refusal ? <Refusal reason={refusal.reason} problems={refusal.problems} /> : null;
}
