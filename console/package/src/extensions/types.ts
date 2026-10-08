import type { ComponentType } from "react";
import type { OrganizationSummary, ProjectDetail, ServiceStatus } from "../client/schemas.ts";

/** Every operation the console offers, by the name a host uses to add beside it or hide it. */
export const operations = [
  "organization.create",
  "organization.rename",
  "organization.delete",
  "organization.disable",
  "organization.enable",
  "organization.quota.set",
  "organization.quota.clear",
  "member.invite",
  "member.role",
  "member.remove",
  "invitation.withdraw",
  "member.repair",
  "token.create",
  "token.revoke",
  "project.create",
  "project.rename",
  "project.delete",
  "registry.set",
  "registry.clear",
  "project-secret.set",
  "project-secret.unset",
  "project-topic.set",
  "project-topic.unset",
  "project-broker.set",
  "project-broker.unset",
  "service.apply",
  "service.pause",
  "service.resume",
  "service.restart",
  "service.rollback",
  "service.expose",
  "service.unexpose",
  "service.delete",
  "service.logs",
] as const;
export type Operation = (typeof operations)[number];

export interface PanelEntities {
  organization: OrganizationSummary;
  project: ProjectDetail;
  service: ServiceStatus;
}
export type PanelKind = keyof PanelEntities;

/** What a panel's `load` receives: the request and a bearer for calls of the host's own. */
export interface PanelLoadContext {
  request: Request;
  accessToken(): Promise<string | null>;
}

/**
 * A section a host adds to an organization's, project's or service's page. `load` runs on the
 * server with the page's own data; a failure in it, or in `Component`, is shown in the panel's place
 * and leaves the page intact.
 */
export interface Panel<E = unknown> {
  id: string;
  title: string;
  load?: (context: PanelLoadContext, entity: E) => Promise<unknown>;
  Component: ComponentType<{ entity: E; data: unknown }>;
}

/** A control a host adds beside one of the console's operations: a link of the host's own. */
export interface Action<E = unknown> {
  id: string;
  label: string;
  href: (entity: E | undefined) => string;
}

export interface ConsoleExtensions {
  panels?: { [K in PanelKind]?: Panel<PanelEntities[K]>[] };
  actions?: Partial<Record<Operation, Action<never>[]>>;
  /** Operations whose control is not shown. The control plane's own rule still stands if one is posted anyway. */
  hidden?: Operation[];
}
