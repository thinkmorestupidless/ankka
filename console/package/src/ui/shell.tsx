/**
 * The shell every console page is shown inside: the backdrop, a rail of areas, a bar saying where
 * the member is, a listing of what sits beside the page, the page, and an inspector holding what the
 * page offers to be done. A host composes the parts it has content for; the backdrop and the bar are
 * the least it mounts. The installation's console mounts them all:
 *
 * ```tsx
 * <Shell><Backdrop /><Rail /><Bar /><Listing /><Outlet /></Shell>
 * ```
 *
 * The rail, the bar and the listing read the shell's data from whichever package page is matched;
 * each page renders its own body and inspector through `Page`, so its forms, refusals and busy
 * states stay where they are and work without scripts.
 */
import type { ReactNode } from "react";
import { clsx } from "clsx";
import { Bot, Box, Building2, Folder, KeyRound, LogOut, Plus, Users, type LucideIcon } from "lucide-react";
import type { Area } from "../context.ts";
import { Breadcrumbs, ConsoleForm, ConsoleLink, useConsole } from "./console.tsx";
import { Lifecycle } from "./status.tsx";
import { button } from "./primitives/button.ts";

/**
 * The grid that lays out whichever parts it is given. It is the console's root. The console is
 * dark; a host that wants the light theme says so here.
 */
export function Shell({ children, className, theme = "dark" }: { children: ReactNode; className?: string; theme?: "dark" | "light" }) {
  return <div className={clsx("ac-root ac-shell", theme === "light" && "ac-light", className)}>{children}</div>;
}

/** The mesh behind every surface: slate, the glow at the centre of the screen, the dot grid. */
export function Backdrop() {
  return <div className="ac-backdrop" aria-hidden="true" />;
}

function Mark() {
  return (
    <svg viewBox="0 0 36 36" width="32" height="32" fill="none" aria-hidden="true">
      <path d="M8 24c0-7 5-12 10-12s10 5 10 12" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
      <path d="M14 24h8" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="18" cy="9" r="2.2" fill="var(--ac-color-amber)" />
    </svg>
  );
}

const initials = (name: string) =>
  name
    .split(/[\s@.]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("");

/**
 * The bar: the wordmark and anything a host adds beside it (its own navigation), where the member
 * is, the page's primary operation, and who is signed in. `end` replaces the last of these, for a
 * host whose visitors may not be signed in.
 */
export function Bar({ wordmark, children, end }: { wordmark?: ReactNode; children?: ReactNode; end?: ReactNode }) {
  const { shell, principal, shows } = useConsole();
  const name = principal ? (principal.name ?? principal.email ?? principal.subject) : null;
  const primary = shell.primary && (!shell.primary.operation || shows(shell.primary.operation)) ? shell.primary : null;
  return (
    <header className="ac-bar">
      <div className="ac-bar-start">
        {wordmark ?? (
          <ConsoleLink to="" className="ac-wordmark">
            ankka <span>console</span>
          </ConsoleLink>
        )}
        {children}
      </div>
      {shell.crumbs.length > 0 ? <Breadcrumbs trail={shell.crumbs} /> : <span />}
      <div className="ac-bar-end">
        {primary ? (
          <ConsoleLink to={primary.to} className={button({ variant: "primary" })} data-primary>
            <Plus aria-hidden="true" />
            {primary.label}
          </ConsoleLink>
        ) : null}
        {end ??
          (name ? (
            <span className="ac-person" title={name}>
              <span aria-hidden="true">{initials(name)}</span>
              <span className="ac-visually-hidden">You are {name}</span>
            </span>
          ) : null)}
      </div>
    </header>
  );
}

interface RailEntry {
  area: Area;
  label: string;
  icon: LucideIcon;
  /** Where it opens; absent when the page names nothing for it to open. */
  to?: string;
  unavailable: string;
}

/**
 * The rail: the console's areas, the one the page belongs to marked. Members, deploy tokens and machines open
 * those of the organization the page belongs to; with no organization in view they are shown and
 * unavailable. Signing out is at its foot.
 */
export function Rail() {
  const { shell } = useConsole();
  const org = shell.organization;
  const project = shell.project;
  const orgPath = org ? `organizations/${encodeURIComponent(org.id)}` : undefined;
  const entries: RailEntry[] = [
    { area: "organizations", label: "Organizations", icon: Building2, to: "", unavailable: "" },
    { area: "projects", label: org ? `Projects of ${org.name}` : "Projects", icon: Folder, to: orgPath, unavailable: "Projects: open an organization first" },
    {
      area: "services",
      label: project ? `Services of ${project.name}` : "Services",
      icon: Box,
      to: project ? `projects/${encodeURIComponent(project.id)}` : undefined,
      unavailable: "Services: open a project first",
    },
    { area: "members", label: org ? `Members of ${org.name}` : "Members", icon: Users, to: orgPath && `${orgPath}/members`, unavailable: "Members: open an organization first" },
    {
      area: "tokens",
      label: org ? `Deploy tokens of ${org.name}` : "Deploy tokens",
      icon: KeyRound,
      to: orgPath && org?.manages ? `${orgPath}/tokens` : undefined,
      unavailable: org ? "Deploy tokens: only an owner manages them" : "Deploy tokens: open an organization first",
    },
    {
      area: "machines",
      label: org ? `Machines of ${org.name}` : "Machines",
      icon: Bot,
      to: orgPath && `${orgPath}/machines`,
      unavailable: "Machines: open an organization first",
    },
  ];
  return (
    <nav className="ac-rail" aria-label="Console">
      <ConsoleLink to="" className="ac-mark" aria-label="ankka console" title="ankka console">
        <Mark />
      </ConsoleLink>
      <ul className="ac-rail-areas">
        {entries.map((e) => {
          const Icon = e.icon;
          return (
            <li key={e.area} data-area={e.area}>
              {e.to !== undefined ? (
                <ConsoleLink to={e.to} className="ac-rail-item" aria-label={e.label} title={e.label} aria-current={shell.area === e.area ? "true" : undefined}>
                  <Icon aria-hidden="true" />
                </ConsoleLink>
              ) : (
                <span className="ac-rail-item" role="link" aria-disabled="true" aria-label={e.unavailable} title={e.unavailable}>
                  <Icon aria-hidden="true" />
                </span>
              )}
            </li>
          );
        })}
      </ul>
      <div className="ac-rail-foot">
        <ConsoleForm to="auth/sign-out">
          <button type="submit" className="ac-rail-item" aria-label="Sign out" title="Sign out">
            <LogOut aria-hidden="true" />
          </button>
        </ConsoleForm>
      </div>
    </nav>
  );
}

/** The listing beside the page: a project's services or an organization's projects. */
export function Listing() {
  const { shell } = useConsole();
  const l = shell.listing;
  if (!l) return null;
  return (
    <nav className="ac-listing" aria-label={l.label}>
      <p className="ac-section-title">
        <span>{l.title}</span>
        <span>{l.items.length}</span>
      </p>
      {l.items.length === 0 ? <p className="ac-hint">Nothing here yet.</p> : null}
      <ul className="ac-listing-items">
        {l.items.map((i) => (
          <li key={i.key} data-item={i.key}>
            <ConsoleLink to={i.to} className="ac-listing-item" aria-current={i.key === l.current ? "page" : undefined}>
              <span className="ac-listing-name">{i.name}</span>
              {i.kind === "service" ? (
                <>
                  <span className="ac-listing-count">
                    {i.ready} of {i.desired}
                  </span>
                  <Lifecycle lifecycle={i.lifecycle} confirmed={i.confirmed} />
                </>
              ) : (
                <span className="ac-listing-count">
                  {i.services} service{i.services === 1 ? "" : "s"}
                </span>
              )}
            </ConsoleLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** The column of what a page offers to be done. */
export function Inspector({ children, label = "Operations" }: { children: ReactNode; label?: string }) {
  return (
    <aside className="ac-inspector" aria-label={label}>
      {children}
    </aside>
  );
}

/** A page: its body, and the inspector beside it when it offers anything. */
export function Page({ children, inspector, className }: { children: ReactNode; inspector?: ReactNode; className?: string }) {
  return (
    <div className="ac-page">
      <main className={clsx("ac-body", className)} id="main">
        {children}
      </main>
      {inspector ? <Inspector>{inspector}</Inspector> : null}
    </div>
  );
}

/** A heading for a group in a side column. */
export function SectionTitle({ children }: { children: ReactNode }) {
  return <h2 className="ac-section-title">{children}</h2>;
}

export interface SegmentLink {
  label: string;
  to: string;
  current?: boolean;
}

/** A page's sections, as links: each is a page of its own. */
export function SegmentedLinks({ label, links }: { label: string; links: SegmentLink[] }) {
  return (
    <nav className="ac-seg" aria-label={label}>
      {links.map((l) => (
        <ConsoleLink key={l.to} to={l.to} aria-current={l.current ? "page" : undefined}>
          {l.label}
        </ConsoleLink>
      ))}
    </nav>
  );
}

export type ServiceSection = "overview" | "topology" | "logs" | "history";

/** A service's four sections. */
export function ServiceSections({ projectId, name, current }: { projectId: string; name: string; current: ServiceSection }) {
  const path = `projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}`;
  const links: [ServiceSection, string, string][] = [
    ["overview", "Overview", path],
    ["topology", "Topology", `${path}/topology`],
    ["logs", "Logs", `${path}/logs`],
    ["history", "History", `${path}/history`],
  ];
  return <SegmentedLinks label="Sections of the service" links={links.map(([s, label, to]) => ({ label, to, current: s === current }))} />;
}
