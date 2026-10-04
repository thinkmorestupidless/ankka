/**
 * A switch: the browser's own checkbox, drawn as a track. It is a form control, so it submits with
 * its form when no script runs, which a switch drawn as a button and toggled by script cannot.
 */
import type { InputHTMLAttributes } from "react";

export function Switch({ label, id, ...input }: { label: string; id: string } & Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "id">) {
  return (
    <label className="ac-switch" htmlFor={id}>
      <span>{label}</span>
      <input type="checkbox" id={id} {...input} />
      <span className="ac-switch-track" aria-hidden="true" />
    </label>
  );
}
