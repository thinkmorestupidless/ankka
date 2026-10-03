/**
 * A control plane in one process: every route the console uses, over in-memory state, with the
 * control plane's rules as the console can observe them — who sees what, `404` rather than `403`
 * for an outsider, owner-only operations, tombstoned ids, `409` for a non-empty delete, a disabled
 * organization refusing changes, descriptor validation, a deploy token shown once.
 *
 * It answers from state rather than from a script, so it cannot run out quietly. What a test needs
 * to steer is explicit: `script()` makes the next answer on one route a given status, `tick()` moves
 * services through their lifecycle, `appendLog()` writes a line, and `hideNewFromListings()` makes
 * listings lag writes the way the real control plane's projections do. `visited` records every route
 * template exercised, which the parity test reads.
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { randomBytes } from "node:crypto";

export interface FakeClaims {
  sub: string;
  name?: string;
  email?: string;
  email_verified?: boolean;
  realm_access?: { roles?: string[] };
  exp?: number;
}

export interface FakeControlPlaneOptions {
  /** Turns a bearer into claims, or `null` when it is not a valid token. Defaults to decoding a JWT and checking `exp`. */
  verify?: (token: string) => Promise<FakeClaims | null> | FakeClaims | null;
  issuer?: string;
  clientId?: string;
  audience?: string;
  /** Who may create organizations: anyone signed in, or platform administrators only. */
  organizationCreation?: "open" | "platform-admin";
  signupUrl?: string;
  baseDomain?: string;
  port?: number;
}

type Role = "owner" | "member";

interface Member {
  role: Role;
  email?: string;
  display?: string;
  since: string;
  addedBy?: string;
}

interface Invitation {
  role: Role;
  invitedAt: string;
  invitedBy?: string;
}

interface Org {
  id: string;
  name: string;
  disabled: boolean;
  quota?: { projects?: number; services?: number; instances?: number };
  members: Map<string, Member>;
  invitations: Map<string, Invitation>;
  hidden: boolean;
}

interface Registry {
  server: string;
  username: string;
  password: string;
  setAt: string;
  setBy?: string;
}

interface Project {
  id: string;
  name: string;
  organizationId: string;
  registry?: Registry;
  hidden: boolean;
}

interface Service {
  name: string;
  projectId: string;
  lifecycle: string;
  generation: number;
  image: string;
  readyInstances: number;
  desiredInstances: number;
  detail?: string;
  exposed: boolean;
  paused: boolean;
  history: { kind: string; generation: number; actor?: { subject: string; display?: string; administrative: boolean }; at: string }[];
  logs: Map<string, string[]>;
  previousLogs: Map<string, string[]>;
  /** The platform's container's output: the sidecar beside a process, the proxy beside a web-hosted one. */
  platformLogs: Map<string, string[]>;
  hosting?: string;
  mounts?: { path: string; service: string; state: string }[];
  callers?: string[];
  processPort?: number;
}

/** The hostings whose pods hold the platform's container beside the developer's. */
const twoContainers = (hosting: string | undefined) => hosting === "process" || hosting === "web";

interface Token {
  id: string;
  organizationId: string;
  label: string;
  subject: string;
  secret: string;
  createdBy?: string;
  createdAt: string;
  expiresAt?: string;
  revoked: boolean;
}

interface Caller {
  subject: string;
  name?: string;
  email?: string;
  emailVerified: boolean;
  admin: boolean;
  tokenOrg?: string;
}

class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const now = () => new Date().toISOString();
const NameRule = /^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$/;

function decodeJwt(token: string): FakeClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8")) as FakeClaims;
    if (typeof claims.exp === "number" && claims.exp * 1000 < Date.now()) return null;
    return claims;
  } catch {
    return null;
  }
}

export interface FakeControlPlane {
  url: string;
  server: Server;
  /** `METHOD /template` for every route answered, e.g. `GET /organizations/{organizationId}`. */
  visited: Set<string>;
  /** The number of requests answered per `METHOD /template`. */
  counts: Map<string, number>;
  /** Make the next request matching `METHOD /template` answer `status` with `error` as the reason. */
  script(route: string, status: number, error?: string): void;
  /** Advance every service in a transitional state by one step. */
  tick(): void;
  appendLog(projectId: string, name: string, line: string, instance?: string): void;
  /** Who may create organizations from now on, and where the refusal sends people. */
  policy(creation: "open" | "platform-admin", signupUrl?: string): void;
  /** From now on, created organizations and projects are missing from listings until `settle()`. */
  hideNewFromListings(): void;
  settle(): void;
  /** Direct state access for assertions and seeding. */
  state: {
    organizations: Map<string, Org>;
    projects: Map<string, Project>;
    services: Map<string, Service>;
    tokens: Map<string, Token>;
    tombstones: Set<string>;
  };
  seed(seed: FakeSeed): void;
  close(): Promise<void>;
}

export interface FakeSeed {
  organizations?: { id: string; name: string; owners?: string[]; members?: string[]; disabled?: boolean }[];
  projects?: { id: string; name: string; organizationId: string }[];
  services?: {
    projectId: string;
    name: string;
    image?: string;
    lifecycle?: string;
    instances?: number;
    hosting?: string;
    mounts?: { path: string; service: string; state: string }[];
    callers?: string[];
    processPort?: number;
  }[];
}

export async function fakeControlPlane(options: FakeControlPlaneOptions = {}): Promise<FakeControlPlane> {
  const verify = options.verify ?? decodeJwt;
  const organizations = new Map<string, Org>();
  const projects = new Map<string, Project>();
  const services = new Map<string, Service>();
  const tokens = new Map<string, Token>();
  const tombstones = new Set<string>();
  const visited = new Set<string>();
  const counts = new Map<string, number>();
  const scripted = new Map<string, { status: number; error: string }[]>();
  let hideNew = false;
  let base = "";

  const serviceKey = (p: string, n: string) => `${p}/${n}`;

  // ── Views of state ─────────────────────────────────────────────────────────

  const orgSummary = (org: Org, caller: Caller) => {
    const member = org.members.get(caller.subject);
    const projectCount = [...projects.values()].filter((p) => p.organizationId === org.id).length;
    const svc = [...services.values()].filter((s) => projects.get(s.projectId)?.organizationId === org.id);
    return {
      id: org.id,
      name: org.name,
      projects: projectCount,
      disabled: org.disabled,
      role: member?.role ?? null,
      quota: org.quota ?? null,
      usage: {
        projects: projectCount,
        services: svc.length,
        instances: svc.reduce((n, s) => n + s.desiredInstances, 0),
      },
    };
  };

  const registrySummary = (r?: Registry) =>
    r ? { server: r.server, username: r.username, setAt: r.setAt, setBy: r.setBy ?? null } : null;

  const serviceStatus = (s: Service) => {
    const org = organizations.get(projects.get(s.projectId)?.organizationId ?? "");
    const suspended = org?.disabled ?? false;
    return {
      name: s.name,
      projectId: s.projectId,
      lifecycle: suspended ? "Suspended" : s.lifecycle,
      generation: s.generation,
      image: s.image,
      readyInstances: suspended ? 0 : s.readyInstances,
      desiredInstances: s.desiredInstances,
      detail: s.detail ?? null,
      confirmed: true,
      database: s.hosting === "web" ? "none" : "provisioned",
      hostname: s.exposed ? `https://${s.name}-${s.projectId}.${options.baseDomain ?? "example.test"}` : null,
      exposed: s.exposed,
      suspended,
      paused: s.paused,
      hosting: s.hosting ?? "embedded",
      protocol: null,
      mounts: s.mounts ?? [],
      callers: s.callers ?? [],
      processPort: s.processPort ?? null,
    };
  };

  // ── Authorization, the control plane's rules ──────────────────────────────

  const isAdmin = (c: Caller) => c.admin;

  function claimInvitations(c: Caller) {
    if (!c.email || !c.emailVerified) return;
    for (const org of organizations.values()) {
      const inv = org.invitations.get(c.email);
      if (inv && !org.members.has(c.subject)) {
        org.members.set(c.subject, { role: inv.role, email: c.email, display: c.name, since: now(), addedBy: inv.invitedBy });
        org.invitations.delete(c.email);
      }
    }
  }

  function requireOrg(c: Caller, id: string): { org: Org; role?: Role } {
    const org = organizations.get(id);
    if (!org) throw new HttpError(404, `organization '${id}' not found`);
    const member = org.members.get(c.subject);
    if (!member && !isAdmin(c)) throw new HttpError(404, `organization '${id}' not found`);
    return { org, role: member?.role };
  }

  function requireWrite(org: Org) {
    if (org.disabled) throw new HttpError(409, `organization '${org.id}' is disabled`);
  }

  function requireOwner(c: Caller, id: string): Org {
    const { org, role } = requireOrg(c, id);
    if (role !== "owner" && !isAdmin(c)) throw new HttpError(403, `owner role required in organization '${id}'`);
    return org;
  }

  function requireProject(c: Caller, id: string): { project: Project; org: Org } {
    const project = projects.get(id);
    if (!project) throw new HttpError(404, `project '${id}' not found`);
    const org = organizations.get(project.organizationId);
    if (!org || (!org.members.has(c.subject) && !isAdmin(c))) throw new HttpError(404, `project '${id}' not found`);
    return { project, org };
  }

  function requireService(c: Caller, p: string, n: string): { service: Service; org: Org } {
    const { org } = requireProject(c, p);
    const service = services.get(serviceKey(p, n));
    if (!service) throw new HttpError(404, `service '${n}' not found in project '${p}'`);
    return { service, org };
  }

  const actor = (c: Caller) => ({ subject: c.subject, display: c.name ?? c.email, administrative: c.admin });

  // ── Routes ─────────────────────────────────────────────────────────────────

  type Handler = (c: Caller, params: Record<string, string>, body: unknown, url: URL) => unknown;
  const routes: { method: string; template: string; pattern: RegExp; keys: string[]; anonymous?: boolean; handler: Handler }[] = [];

  function route(method: string, template: string, handler: Handler, anonymous = false) {
    const keys: string[] = [];
    const pattern = new RegExp(
      "^" + template.replace(/\{(\w+)\}/g, (_m, k: string) => (keys.push(k), "([^/]+)")) + "$",
    );
    routes.push({ method, template, pattern, keys, handler, anonymous });
  }

  route(
    "GET",
    "/auth",
    () => ({
      issuer: options.issuer ?? "http://issuer.invalid/realms/ankka",
      clientId: options.clientId ?? "ankka-cli",
      audience: options.audience ?? "ankka-controlplane",
    }),
    true,
  );

  route("GET", "/auth/whoami", (c) => {
    claimInvitations(c);
    return {
      subject: c.subject,
      name: c.name ?? null,
      email: c.email ?? null,
      emailVerified: c.emailVerified,
      platformAdmin: c.admin,
      organizations: isAdmin(c)
        ? []
        : [...organizations.values()]
            .filter((o) => o.members.has(c.subject))
            .map((o) => ({ id: o.id, name: o.name, role: o.members.get(c.subject)!.role })),
    };
  });

  route("GET", "/organizations", (c) => {
    claimInvitations(c);
    return [...organizations.values()]
      .filter((o) => !o.hidden && (isAdmin(c) || o.members.has(c.subject)))
      .map((o) => orgSummary(o, c));
  });

  route("GET", "/organizations/{organizationId}", (c, p) => {
    const { org } = requireOrg(c, p.organizationId);
    const s = orgSummary(org, c);
    return isAdmin(c) && !org.members.has(c.subject) ? { ...s, role: null } : s;
  });

  route("POST", "/organizations/{organizationId}", (c, p, body) => {
    const b = body as { name?: string; owner?: { subject: string; email?: string; display?: string } };
    if (b.owner && !isAdmin(c)) throw new HttpError(403, "platform administrator role required to name an owner");
    if ((options.organizationCreation ?? "open") === "platform-admin" && !isAdmin(c)) {
      const where = options.signupUrl ? `; sign up at ${options.signupUrl}` : "";
      throw new HttpError(403, `organizations in this installation are created by the platform administrator${where}`);
    }
    const id = p.organizationId;
    if (!NameRule.test(id)) throw new HttpError(400, `organization id '${id}' is invalid: lowercase letters, digits and '-', starting with a letter`);
    if (!b.name) throw new HttpError(400, "organization name must not be empty");
    if (tombstones.has(`org:${id}`)) throw new HttpError(409, `organization '${id}' was deleted; its id is not reused`);
    if (organizations.has(id)) throw new HttpError(409, `organization '${id}' already exists`);
    const owner = b.owner ?? { subject: c.subject, email: c.email, display: c.name };
    const org: Org = {
      id,
      name: b.name,
      disabled: false,
      members: new Map([[owner.subject, { role: "owner", email: owner.email, display: owner.display, since: now(), addedBy: c.name }]]),
      invitations: new Map(),
      hidden: hideNew,
    };
    organizations.set(id, org);
    return "done";
  });

  route("PUT", "/organizations/{organizationId}/name", (c, p, body) => {
    const org = requireOwner(c, p.organizationId);
    requireWrite(org);
    const name = (body as { name?: string }).name;
    if (!name) throw new HttpError(400, "organization name must not be empty");
    org.name = name;
    return "done";
  });

  route("DELETE", "/organizations/{organizationId}", (c, p) => {
    const org = requireOwner(c, p.organizationId);
    const remaining = [...projects.values()].filter((x) => x.organizationId === org.id).length;
    if (remaining > 0) throw new HttpError(409, `organization '${org.id}' still has ${remaining} project(s)`);
    organizations.delete(org.id);
    tombstones.add(`org:${org.id}`);
    return "done";
  });

  route("GET", "/organizations/{organizationId}/members", (c, p) => {
    const { org } = requireOrg(c, p.organizationId);
    return {
      members: [...org.members.entries()].map(([subject, m]) => ({ subject, role: m.role, email: m.email ?? null, display: m.display ?? null, since: m.since, addedBy: m.addedBy ?? null })),
      invitations: [...org.invitations.entries()].map(([email, i]) => ({ email, role: i.role, invitedAt: i.invitedAt, invitedBy: i.invitedBy ?? null })),
    };
  });

  route("POST", "/organizations/{organizationId}/members", (c, p, body) => {
    const org = requireOwner(c, p.organizationId);
    requireWrite(org);
    const b = body as { email?: string; role?: Role };
    if (!b.email || !b.email.includes("@")) throw new HttpError(400, `'${b.email ?? ""}' is not an email address`);
    org.invitations.set(b.email, { role: b.role ?? "member", invitedAt: now(), invitedBy: c.name ?? c.subject });
    return "done";
  });

  const owners = (org: Org) => [...org.members.values()].filter((m) => m.role === "owner").length;

  route("DELETE", "/organizations/{organizationId}/members/{subject}", (c, p) => {
    const org = requireOwner(c, p.organizationId);
    requireWrite(org);
    const m = org.members.get(p.subject);
    if (!m) throw new HttpError(404, `'${p.subject}' is not a member of organization '${org.id}'`);
    if (m.role === "owner" && owners(org) === 1) throw new HttpError(409, `'${p.subject}' is the last owner of organization '${org.id}'`);
    org.members.delete(p.subject);
    return "done";
  });

  route("PUT", "/organizations/{organizationId}/members/{subject}/role", (c, p, body) => {
    const org = requireOwner(c, p.organizationId);
    requireWrite(org);
    const m = org.members.get(p.subject);
    if (!m) throw new HttpError(404, `'${p.subject}' is not a member of organization '${org.id}'`);
    const role = (body as { role: Role }).role;
    if (m.role === "owner" && role !== "owner" && owners(org) === 1) throw new HttpError(409, `'${p.subject}' is the last owner of organization '${org.id}'`);
    m.role = role;
    return "done";
  });

  route("DELETE", "/organizations/{organizationId}/invitations/{email}", (c, p) => {
    const org = requireOwner(c, p.organizationId);
    requireWrite(org);
    if (!org.invitations.delete(p.email)) throw new HttpError(404, `no invitation for '${p.email}'`);
    return "done";
  });

  route("POST", "/organizations/{organizationId}/members/{subject}/repair", (c, p, body) => {
    if (!isAdmin(c)) throw new HttpError(403, "platform administrator role required");
    const org = organizations.get(p.organizationId);
    if (!org) throw new HttpError(404, `organization '${p.organizationId}' not found`);
    org.members.set(p.subject, { role: (body as { role?: Role }).role ?? "owner", since: now(), addedBy: c.name });
    return "done";
  });

  route("GET", "/organizations/{organizationId}/tokens", (c, p) => {
    requireOwner(c, p.organizationId);
    return [...tokens.values()]
      .filter((t) => t.organizationId === p.organizationId && !t.revoked)
      .map((t) => ({ id: t.id, label: t.label, subject: t.subject, createdBy: t.createdBy ?? null, createdAt: t.createdAt, expiresAt: t.expiresAt ?? null, lastUsed: null }));
  });

  route("POST", "/organizations/{organizationId}/tokens", (c, p, body) => {
    const org = requireOwner(c, p.organizationId);
    if (c.tokenOrg) throw new HttpError(403, "a deploy token cannot manage deploy tokens");
    requireWrite(org);
    const b = body as { label?: string; expiresIn?: number };
    if (!b.label) throw new HttpError(400, "token label must not be empty");
    const id = randomBytes(6).toString("hex");
    const secret = `ankka_${id}_${randomBytes(18).toString("base64url")}`;
    const t: Token = {
      id,
      organizationId: org.id,
      label: b.label,
      subject: `token:${id}`,
      secret,
      createdBy: c.name ?? c.subject,
      createdAt: now(),
      // Absent is the platform's 90-day default; 0 is never.
      expiresAt: b.expiresIn === 0 ? undefined : new Date(Date.now() + (b.expiresIn ?? 90 * 86_400) * 1000).toISOString(),
      revoked: false,
    };
    tokens.set(id, t);
    org.members.set(t.subject, { role: "member", display: b.label, since: now(), addedBy: c.name });
    return { id, label: t.label, secret, subject: t.subject, expiresAt: t.expiresAt ?? null };
  });

  route("DELETE", "/organizations/{organizationId}/tokens/{tokenId}", (c, p) => {
    const org = requireOwner(c, p.organizationId);
    const t = tokens.get(p.tokenId);
    if (!t || t.organizationId !== org.id || t.revoked) throw new HttpError(404, `deploy token '${p.tokenId}' not found`);
    t.revoked = true;
    org.members.delete(t.subject);
    return "done";
  });

  route("POST", "/organizations/{organizationId}/disable", (c, p) => {
    if (!isAdmin(c)) throw new HttpError(403, "platform administrator role required");
    const org = organizations.get(p.organizationId);
    if (!org) throw new HttpError(404, `organization '${p.organizationId}' not found`);
    org.disabled = true;
    return "done";
  });

  route("POST", "/organizations/{organizationId}/enable", (c, p) => {
    if (!isAdmin(c)) throw new HttpError(403, "platform administrator role required");
    const org = organizations.get(p.organizationId);
    if (!org) throw new HttpError(404, `organization '${p.organizationId}' not found`);
    org.disabled = false;
    return "done";
  });

  route("PUT", "/organizations/{organizationId}/quota", (c, p, body) => {
    if (!isAdmin(c)) throw new HttpError(403, "platform administrator role required");
    const org = organizations.get(p.organizationId);
    if (!org) throw new HttpError(404, `organization '${p.organizationId}' not found`);
    const q = body as { projects?: number | null; services?: number | null; instances?: number | null };
    org.quota = {
      projects: q.projects ?? undefined,
      services: q.services ?? undefined,
      instances: q.instances ?? undefined,
    };
    return "done";
  });

  route("DELETE", "/organizations/{organizationId}/quota", (c, p) => {
    if (!isAdmin(c)) throw new HttpError(403, "platform administrator role required");
    const org = organizations.get(p.organizationId);
    if (!org) throw new HttpError(404, `organization '${p.organizationId}' not found`);
    org.quota = undefined;
    return "done";
  });

  route("GET", "/projects", (c) =>
    [...projects.values()]
      .filter((p) => !p.hidden)
      .filter((p) => {
        const org = organizations.get(p.organizationId);
        return org && (isAdmin(c) || org.members.has(c.subject));
      })
      .map((p) => ({
        id: p.id,
        name: p.name,
        organizationId: p.organizationId,
        services: [...services.values()].filter((s) => s.projectId === p.id).length,
        registry: registrySummary(p.registry),
      })),
  );

  route("GET", "/projects/{projectId}", (c, p) => {
    const { project } = requireProject(c, p.projectId);
    return { id: project.id, name: project.name, organizationId: project.organizationId, registry: registrySummary(project.registry) };
  });

  route("POST", "/projects/{projectId}", (c, p, body) => {
    const b = body as { name?: string; organizationId?: string };
    const { org } = requireOrg(c, b.organizationId ?? "");
    requireWrite(org);
    const id = p.projectId;
    if (!NameRule.test(id)) throw new HttpError(400, `project id '${id}' is invalid: lowercase letters, digits and '-', starting with a letter`);
    if (id === "platform") throw new HttpError(400, `project id '${id}' is reserved for the platform's own workloads`);
    if (!b.name) throw new HttpError(400, "project name must not be empty");
    if (tombstones.has(`project:${id}`)) throw new HttpError(409, `project '${id}' was deleted; its id is not reused`);
    if (projects.has(id)) throw new HttpError(409, `project '${id}' already exists`);
    const count = [...projects.values()].filter((x) => x.organizationId === org.id).length;
    if (org.quota?.projects !== undefined && count >= org.quota.projects)
      throw new HttpError(409, `organization '${org.id}' is at its quota of ${org.quota.projects} project(s)`);
    projects.set(id, { id, name: b.name, organizationId: org.id, hidden: hideNew });
    return "done";
  });

  route("PUT", "/projects/{projectId}/name", (c, p, body) => {
    const { project, org } = requireProject(c, p.projectId);
    requireWrite(org);
    const name = (body as { name?: string }).name;
    if (!name) throw new HttpError(400, "project name must not be empty");
    project.name = name;
    return "done";
  });

  route("DELETE", "/projects/{projectId}", (c, p) => {
    const { project } = requireProject(c, p.projectId);
    const remaining = [...services.values()].filter((s) => s.projectId === project.id).length;
    if (remaining > 0) throw new HttpError(409, `project '${project.id}' still has ${remaining} service(s)`);
    projects.delete(project.id);
    tombstones.add(`project:${project.id}`);
    return "done";
  });

  route("PUT", "/projects/{projectId}/registry", (c, p, body) => {
    const { project, org } = requireProject(c, p.projectId);
    requireWrite(org);
    const b = body as { server?: string; username?: string; password?: string };
    if (!b.server || !b.username || !b.password) throw new HttpError(400, "server, username and password are all required");
    project.registry = { server: b.server, username: b.username, password: b.password, setAt: now(), setBy: c.name ?? c.subject };
    return "done";
  });

  route("DELETE", "/projects/{projectId}/registry", (c, p) => {
    const { project, org } = requireProject(c, p.projectId);
    requireWrite(org);
    project.registry = undefined;
    return "done";
  });

  route("GET", "/services/{projectId}", (c, p) => {
    requireProject(c, p.projectId);
    return [...services.values()].filter((s) => s.projectId === p.projectId).map(serviceStatus);
  });

  route("GET", "/services/{projectId}/{name}", (c, p) => serviceStatus(requireService(c, p.projectId, p.name).service));

  route("PUT", "/services/{projectId}/{name}", (c, p, body) => {
    const { org } = requireProject(c, p.projectId);
    requireWrite(org);
    const d = body as { name?: string; service?: { image?: string; resources?: { autoscaling?: { minInstances?: number } } } };
    if (d?.name !== p.name) throw new HttpError(400, `descriptor names service '${d?.name ?? ""}' but was applied to '${p.name}'`);
    const problems: string[] = [];
    if (!NameRule.test(p.name)) problems.push(`service name '${p.name}' is invalid: lowercase letters, digits and '-', starting with a letter`);
    if (!d.service?.image) problems.push("image must not be empty");
    if (problems.length > 0) throw new HttpError(400, `invalid descriptor: ${problems.join("; ")}`);
    const key = serviceKey(p.projectId, p.name);
    const instances = d.service?.resources?.autoscaling?.minInstances ?? 1;
    const existing = services.get(key);
    const s: Service = existing ?? {
      name: p.name,
      projectId: p.projectId,
      lifecycle: "NotDeployed",
      generation: 0,
      image: "",
      readyInstances: 0,
      desiredInstances: instances,
      exposed: false,
      paused: false,
      history: [],
      logs: new Map(),
      previousLogs: new Map(),
      platformLogs: new Map(),
    };
    s.image = d.service!.image!;
    s.generation += 1;
    s.desiredInstances = instances;
    s.lifecycle = s.paused ? "Paused" : "UpdateInProgress";
    s.history.push({ kind: "applied", generation: s.generation, actor: actor(c), at: now() });
    services.set(key, s);
    return serviceStatus(s);
  });

  const operation = (op: "pause" | "resume" | "restart" | "expose" | "unexpose") => (c: Caller, p: Record<string, string>) => {
    const { service: s, org } = requireService(c, p.projectId, p.name);
    requireWrite(org);
    if (op === "pause") {
      s.paused = true;
      s.lifecycle = "Paused";
      s.readyInstances = 0;
    } else if (op === "resume") {
      s.paused = false;
      s.lifecycle = "UpdateInProgress";
    } else if (op === "restart") {
      if (s.paused) throw new HttpError(409, `service '${s.name}' is paused`);
      s.generation += 1;
      s.lifecycle = "UpdateInProgress";
      for (const [k, v] of s.logs) s.previousLogs.set(k, v);
      s.logs = new Map();
    } else if (op === "expose") {
      s.exposed = true;
    } else {
      s.exposed = false;
    }
    s.history.push({ kind: op === "pause" ? "paused" : op === "resume" ? "resumed" : op === "restart" ? "restarted" : op === "expose" ? "exposed" : "unexposed", generation: s.generation, actor: actor(c), at: now() });
    return "done";
  };
  for (const op of ["pause", "resume", "restart", "expose", "unexpose"] as const) {
    route("POST", `/services/{projectId}/{name}/${op}`, operation(op));
  }

  route("GET", "/services/{projectId}/{name}/logs", (c, p, _body, url) => {
    const { service: s } = requireService(c, p.projectId, p.name);
    if (s.paused || s.readyInstances === 0 && s.logs.size === 0) throw new HttpError(404, `service '${s.name}' has no running instance`);
    const previous = url.searchParams.get("previous") === "true";
    const platform = url.searchParams.get("platform") === "true";
    if (platform && !twoContainers(s.hosting)) throw new HttpError(400, "--platform applies to a service with process or web hosting");
    const tail = url.searchParams.get("tail");
    const only = url.searchParams.get("instance");
    const source = platform ? s.platformLogs : previous ? s.previousLogs : s.logs;
    const names = s.readyInstances > 0 ? Array.from({ length: s.readyInstances }, (_x, i) => `${s.name}-${i}`) : [...source.keys()];
    return {
      instances: names
        .filter((n) => !only || n === only)
        .map((n) => {
          const lines = source.get(n) ?? [];
          const shown = tail ? lines.slice(-Number(tail)) : lines;
          return { instance: n, output: shown.map((l) => l + "\n").join(""), error: null };
        }),
    };
  });

  route("GET", "/services/{projectId}/{name}/history", (c, p) => requireService(c, p.projectId, p.name).service.history);

  route("DELETE", "/services/{projectId}/{name}", (c, p) => {
    const { service: s, org } = requireService(c, p.projectId, p.name);
    requireWrite(org);
    services.delete(serviceKey(s.projectId, s.name));
    return "done";
  });

  // ── The server ─────────────────────────────────────────────────────────────

  async function callerOf(req: IncomingMessage): Promise<Caller | null> {
    const header = req.headers.authorization ?? "";
    if (!header.startsWith("Bearer ")) return null;
    const token = header.slice(7);
    if (token.startsWith("ankka_")) {
      const id = token.split("_")[1];
      const t = tokens.get(id);
      if (!t || t.revoked || t.secret !== token) return null;
      return { subject: t.subject, name: t.label, emailVerified: false, admin: false, tokenOrg: t.organizationId };
    }
    const claims = await verify(token);
    if (!claims) return null;
    return {
      subject: claims.sub,
      name: claims.name,
      email: claims.email,
      emailVerified: claims.email_verified ?? false,
      admin: claims.realm_access?.roles?.includes("platform-admin") ?? false,
    };
  }

  function send(res: ServerResponse, status: number, body: unknown) {
    // The control plane answers `Done` as 204 with no body.
    if (body === "done" && status === 200) {
      res.writeHead(204).end();
    } else if (typeof body === "string") {
      res.writeHead(status, { "content-type": "text/plain" }).end(body);
    } else {
      res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
    }
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://fake");
    const path = url.pathname;
    const match = routes
      .filter((r) => r.method === req.method)
      .map((r) => ({ r, m: r.pattern.exec(path) }))
      .filter((x) => x.m !== null)
      // Literal segments outrank parameters, as in the real router.
      .sort((a, b) => b.r.template.replace(/\{\w+\}/g, "").length - a.r.template.replace(/\{\w+\}/g, "").length)[0];
    if (!match) return send(res, 404, { status: 404, error: `no route for ${req.method} ${path}` });
    const id = `${match.r.method} ${match.r.template}`;
    visited.add(id);
    counts.set(id, (counts.get(id) ?? 0) + 1);

    const queued = scripted.get(id);
    if (queued && queued.length > 0) {
      const next = queued.shift()!;
      return send(res, next.status, { status: next.status, error: next.error });
    }

    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const raw = Buffer.concat(chunks).toString("utf8");
    let body: unknown = undefined;
    if (raw.length > 0) {
      try {
        body = JSON.parse(raw);
      } catch {
        return send(res, 400, { status: 400, error: "request body is not valid JSON" });
      }
    }

    let caller: Caller | null = null;
    if (!match.r.anonymous) {
      caller = await callerOf(req);
      if (!caller) {
        res.setHeader("www-authenticate", 'Bearer realm="ankka"');
        return send(res, 401, { status: 401, error: "missing, invalid or expired token" });
      }
    }
    const params: Record<string, string> = {};
    match.r.keys.forEach((k, i) => (params[k] = decodeURIComponent(match.m![i + 1])));
    try {
      const result = match.r.handler(caller as Caller, params, body, url);
      send(res, 200, result);
    } catch (e) {
      if (e instanceof HttpError) return send(res, e.status, { status: e.status, error: e.message });
      send(res, 500, { status: 500, error: String(e) });
    }
  });

  await new Promise<void>((resolve) => server.listen(options.port ?? 0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

  const fake: FakeControlPlane = {
    url: base,
    server,
    visited,
    counts,
    script(routeId, status, error = "scripted failure") {
      const list = scripted.get(routeId) ?? [];
      list.push({ status, error });
      scripted.set(routeId, list);
    },
    tick() {
      for (const s of services.values()) {
        if (s.lifecycle === "UpdateInProgress") {
          s.lifecycle = "Ready";
          s.readyInstances = s.desiredInstances;
          for (let i = 0; i < s.readyInstances; i++) {
            const n = `${s.name}-${i}`;
            if (!s.logs.has(n)) s.logs.set(n, [`${s.name} started`]);
          }
        } else if (s.lifecycle === "NotDeployed") {
          s.lifecycle = "UpdateInProgress";
        }
      }
    },
    appendLog(projectId, name, line, instance) {
      const s = services.get(serviceKey(projectId, name));
      if (!s) throw new Error(`no service ${projectId}/${name}`);
      const n = instance ?? `${name}-0`;
      s.logs.set(n, [...(s.logs.get(n) ?? []), line]);
    },
    policy(creation, signupUrl) {
      options.organizationCreation = creation;
      options.signupUrl = signupUrl;
    },
    hideNewFromListings() {
      hideNew = true;
    },
    settle() {
      hideNew = false;
      for (const o of organizations.values()) o.hidden = false;
      for (const p of projects.values()) p.hidden = false;
    },
    state: { organizations, projects, services, tokens, tombstones },
    seed(seed) {
      for (const o of seed.organizations ?? []) {
        const members = new Map<string, Member>();
        for (const s of o.owners ?? []) members.set(s, { role: "owner", since: now() });
        for (const s of o.members ?? []) members.set(s, { role: "member", since: now() });
        organizations.set(o.id, { id: o.id, name: o.name, disabled: o.disabled ?? false, members, invitations: new Map(), hidden: false });
      }
      for (const p of seed.projects ?? []) projects.set(p.id, { ...p, hidden: false });
      for (const s of seed.services ?? []) {
        const lifecycle = s.lifecycle ?? "Ready";
        const n = s.instances ?? 1;
        const ready = lifecycle === "Ready" ? n : 0;
        const logs = new Map<string, string[]>();
        const platformLogs = new Map<string, string[]>();
        for (let i = 0; i < ready; i++) {
          logs.set(`${s.name}-${i}`, [`${s.name} started`]);
          if (twoContainers(s.hosting)) platformLogs.set(`${s.name}-${i}`, [`${s.hosting === "web" ? "proxy" : "sidecar"} of ${s.name} started`]);
        }
        services.set(serviceKey(s.projectId, s.name), {
          name: s.name,
          projectId: s.projectId,
          lifecycle,
          generation: 1,
          image: s.image ?? `${s.name}:latest`,
          readyInstances: ready,
          desiredInstances: n,
          exposed: false,
          paused: lifecycle === "Paused",
          history: [{ kind: "applied", generation: 1, at: now() }],
          logs,
          previousLogs: new Map(),
          platformLogs,
          hosting: s.hosting,
          mounts: s.mounts,
          callers: s.callers,
          processPort: s.processPort,
        });
      }
    },
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
  return fake;
}
