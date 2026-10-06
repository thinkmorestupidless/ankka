/**
 * A choice: the browser's own select, labelled. A choice drawn by script cannot be made without it;
 * this one can, and it submits with its form.
 */
import type { ReactNode, SelectHTMLAttributes } from "react";

export function Select({ label, id, children, ...select }: { label: string; id: string; children: ReactNode } & SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className="ac-field">
      <label htmlFor={id}>{label}</label>
      <select id={id} {...select}>
        {children}
      </select>
    </div>
  );
}
