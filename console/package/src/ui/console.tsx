/**
 * The pieces every page shares: where the package is mounted, who is signed in, the host's
 * extensions, links and forms that stay under the mount, and a submit button that cannot be pressed
 * twice while its form is in flight.
 */
import { createContext, useContext, type ReactNode } from "react";
import { Form, Link, useMatches, useNavigation, type FormProps, type LinkProps } from "react-router";
import type { ConsoleExtensions, Operation } from "../extensions/types.ts";
import type { ConsolePageData } from "../context.ts";

const ExtensionsContext = createContext<ConsoleExtensions>({});

/**
 * Hands the host's extensions to the pages. Rendered in the host's layout, around its `<Outlet />`,
 * with the same `extensions` object given to `consoleMiddleware`, so panels render in the browser as
 * well as on the server.
 */
export function ConsoleProvider({ extensions, children }: { extensions?: ConsoleExtensions; children: ReactNode }) {
  return <ExtensionsContext.Provider value={extensions ?? {}}>{children}</ExtensionsContext.Provider>;
}

export interface UseConsole extends ConsolePageData {
  extensions: ConsoleExtensions;
  href(path?: string): string;
  shows(operation: Operation): boolean;
}

/** The console's page data, from whichever package page is matched; usable in the host's layout too. */
export function useConsole(): UseConsole {
  const matches = useMatches();
  const extensions = useContext(ExtensionsContext);
  let page: ConsolePageData | undefined;
  for (const m of matches) {
    const d = m.loaderData as { console?: ConsolePageData } | undefined;
    if (d && typeof d === "object" && d.console) page = d.console;
  }
  const mount = page?.mount ?? "/";
  const hidden = new Set<Operation>([...(page?.hidden ?? []), ...(extensions.hidden ?? [])]);
  return {
    mount,
    principal: page?.principal ?? null,
    hidden: [...hidden],
    extensions,
    href: (path = "") => mount + path.replace(/^\/+/, ""),
    shows: (operation) => !hidden.has(operation),
  };
}

/** A link under the mount, fetched ahead when the pointer or focus shows intent. */
export function ConsoleLink({ to, ...rest }: Omit<LinkProps, "to"> & { to: string }) {
  const { href } = useConsole();
  return <Link to={href(to)} prefetch="intent" {...rest} />;
}

/**
 * A form posting to a path under the mount (the current page when `to` is absent), carrying the
 * operation's name as `intent`. It works as plain HTML with scripts off; with scripts on, its submit
 * control is disabled while it is in flight so a second press sends nothing.
 */
export function ConsoleForm({
  to,
  intent,
  children,
  ...rest
}: Omit<FormProps, "action"> & { to?: string; intent?: string; children: ReactNode }) {
  const { href } = useConsole();
  return (
    <Form method="post" action={to === undefined ? undefined : href(to)} {...rest}>
      {intent ? <input type="hidden" name="intent" value={intent} /> : null}
      {children}
    </Form>
  );
}

export function Submit({ intent, children, danger }: { intent?: string; children: ReactNode; danger?: boolean }) {
  const navigation = useNavigation();
  const busy = navigation.state === "submitting" && (intent === undefined || navigation.formData?.get("intent") === intent);
  return (
    <button type="submit" className={danger ? "ac-button ac-button-danger" : "ac-button"} disabled={busy} aria-busy={busy || undefined}>
      {children}
    </button>
  );
}

/** A labelled field; a refusal about it is announced with it. */
export function Field({
  label,
  name,
  hint,
  error,
  ...input
}: { label: string; name: string; hint?: string; error?: string } & React.InputHTMLAttributes<HTMLInputElement>) {
  const hintId = hint ? `${name}-hint` : undefined;
  const errorId = error ? `${name}-error` : undefined;
  return (
    <div className="ac-field">
      <label htmlFor={name}>{label}</label>
      <input id={name} name={name} aria-describedby={[hintId, errorId].filter(Boolean).join(" ") || undefined} aria-invalid={error ? true : undefined} {...input} />
      {hint ? <p className="ac-hint" id={hintId}>{hint}</p> : null}
      {error ? <p className="ac-field-error" id={errorId}>{error}</p> : null}
    </div>
  );
}

export interface Crumb {
  label: string;
  to?: string;
}

/** Where the page sits in the installation: organization, project, service. */
export function Breadcrumbs({ trail }: { trail: Crumb[] }) {
  return (
    <nav aria-label="Breadcrumb" className="ac-crumbs">
      <ol>
        {trail.map((c, i) => (
          <li key={i}>{c.to && i < trail.length - 1 ? <ConsoleLink to={c.to}>{c.label}</ConsoleLink> : <span aria-current={i === trail.length - 1 ? "page" : undefined}>{c.label}</span>}</li>
        ))}
      </ol>
    </nav>
  );
}

export function when(date: string | undefined): string {
  if (!date) return "—";
  const d = new Date(date);
  return Number.isNaN(d.getTime()) ? date : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" }) + " UTC";
}
