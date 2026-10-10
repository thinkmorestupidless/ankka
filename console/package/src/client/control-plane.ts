import type { z } from "zod";
import { ControlPlaneError, ControlPlaneUnreachable, SignInRequired } from "./errors.ts";
import {
  authDiscoverySchema,
  deployTokenCreatedSchema,
  deployTokenSummarySchema,
  historyEntrySchema,
  logsResponseSchema,
  membersResponseSchema,
  organizationSummarySchema,
  projectBrokerSchema,
  projectDetailSchema,
  projectSecretSummarySchema,
  projectSummarySchema,
  projectTopicSchema,
  rolledBackSchema,
  serviceStatusSchema,
  serviceTopologySchema,
  type AuthDiscovery,
  type BrokerDeclarationRequest,
  type CreateDeployToken,
  type CreateOrganization,
  type DeployTokenCreated,
  type DeployTokenSummary,
  type HistoryEntry,
  type LogsResponse,
  type MembersResponse,
  type OrganizationSummary,
  type ProjectBroker,
  type ProjectDetail,
  type ProjectSecretSummary,
  type ProjectSummary,
  type ProjectTopic,
  type Quota,
  type Role,
  type RolledBack,
  type ServiceStatus,
  type ServiceTopology,
  type SetRegistry,
  type TopicDeclarationRequest,
  type Whoami,
  whoamiSchema,
  type Installation,
  installationSchema,
} from "./schemas.ts";

/** `fetch`'s shape; the server supplies one that presents the console's certificate. */
export type Transport = (url: string, init: RequestInit) => Promise<Response>;

/**
 * Where the bearer comes from. Called once per request; `refresh: true` means the last one was
 * refused and a new one must be obtained. `null` means nobody is signed in.
 */
export type BearerSource = (options: { refresh: boolean }) => Promise<string | null>;

export interface LogsQuery {
  instance?: string;
  previous?: boolean;
  /** The platform's container (the sidecar, or a web-hosted service's proxy) instead of the developer's. */
  platform?: boolean;
  tail?: number;
  since?: number;
}

export interface ControlPlaneClientOptions {
  baseUrl: string;
  bearer: BearerSource;
  transport?: Transport;
}

/** A descriptor is passed through as the control plane wrote it, as `applyService` takes one. */
const anyJson = { parse: (value: unknown): unknown => value };

const arrayOf = <T extends z.ZodType>(schema: T) => ({
  parse: (value: unknown): z.infer<T>[] => {
    if (!Array.isArray(value)) throw new TypeError("expected a JSON array");
    return value.map((item) => schema.parse(item));
  },
});

const segment = encodeURIComponent;

/**
 * The control plane's HTTP API, one method per route the console uses, as the signed-in person.
 *
 * A `401` is retried once with a refreshed bearer and then surfaces as `SignInRequired`; any other
 * refusal is a `ControlPlaneError` carrying the body's reason. Responses are decoded against the
 * wire schemas, with unknown fields dropped.
 */
export class ControlPlaneClient {
  readonly #baseUrl: string;
  readonly #bearer: BearerSource;
  readonly #transport: Transport;

  constructor(options: ControlPlaneClientOptions) {
    this.#baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.#bearer = options.bearer;
    this.#transport = options.transport ?? ((url, init) => fetch(url, init));
  }

  // ── Identity ──────────────────────────────────────────────────────────────

  authDiscovery(): Promise<AuthDiscovery> {
    return this.#call("GET", "/auth", { schema: authDiscoverySchema, anonymous: true });
  }

  whoami(): Promise<Whoami> {
    return this.#call("GET", "/auth/whoami", { schema: whoamiSchema });
  }

  // ── The installation ──────────────────────────────────────────────────────

  /** Its version, and its cloud when it names a provider; the key's name for an owner alone (feature 044). */
  installation(): Promise<Installation> {
    return this.#call("GET", "/installation", { schema: installationSchema });
  }

  // ── Organizations ─────────────────────────────────────────────────────────

  listOrganizations(): Promise<OrganizationSummary[]> {
    return this.#call("GET", "/organizations", { schema: arrayOf(organizationSummarySchema) });
  }

  getOrganization(id: string): Promise<OrganizationSummary> {
    return this.#call("GET", `/organizations/${segment(id)}`, { schema: organizationSummarySchema });
  }

  createOrganization(id: string, body: CreateOrganization): Promise<void> {
    return this.#call("POST", `/organizations/${segment(id)}`, { body });
  }

  renameOrganization(id: string, name: string): Promise<void> {
    return this.#call("PUT", `/organizations/${segment(id)}/name`, { body: { name } });
  }

  deleteOrganization(id: string): Promise<void> {
    return this.#call("DELETE", `/organizations/${segment(id)}`);
  }

  disableOrganization(id: string): Promise<void> {
    return this.#call("POST", `/organizations/${segment(id)}/disable`);
  }

  enableOrganization(id: string): Promise<void> {
    return this.#call("POST", `/organizations/${segment(id)}/enable`);
  }

  setQuota(id: string, quota: Quota): Promise<void> {
    return this.#call("PUT", `/organizations/${segment(id)}/quota`, { body: quota });
  }

  clearQuota(id: string): Promise<void> {
    return this.#call("DELETE", `/organizations/${segment(id)}/quota`);
  }

  // ── Members and invitations ───────────────────────────────────────────────

  members(id: string): Promise<MembersResponse> {
    return this.#call("GET", `/organizations/${segment(id)}/members`, { schema: membersResponseSchema });
  }

  invite(id: string, email: string, role: Role): Promise<void> {
    return this.#call("POST", `/organizations/${segment(id)}/members`, { body: { email, role } });
  }

  removeMember(id: string, subject: string): Promise<void> {
    return this.#call("DELETE", `/organizations/${segment(id)}/members/${segment(subject)}`);
  }

  changeRole(id: string, subject: string, role: Role): Promise<void> {
    return this.#call("PUT", `/organizations/${segment(id)}/members/${segment(subject)}/role`, {
      body: { role },
    });
  }

  withdrawInvitation(id: string, email: string): Promise<void> {
    return this.#call("DELETE", `/organizations/${segment(id)}/invitations/${segment(email)}`);
  }

  repair(id: string, subject: string, role: Role): Promise<void> {
    return this.#call("POST", `/organizations/${segment(id)}/members/${segment(subject)}/repair`, {
      body: { role },
    });
  }

  // ── Deploy tokens ─────────────────────────────────────────────────────────

  tokens(id: string): Promise<DeployTokenSummary[]> {
    return this.#call("GET", `/organizations/${segment(id)}/tokens`, {
      schema: arrayOf(deployTokenSummarySchema),
    });
  }

  createToken(id: string, body: CreateDeployToken): Promise<DeployTokenCreated> {
    return this.#call("POST", `/organizations/${segment(id)}/tokens`, {
      body,
      schema: deployTokenCreatedSchema,
    });
  }

  revokeToken(id: string, tokenId: string): Promise<void> {
    return this.#call("DELETE", `/organizations/${segment(id)}/tokens/${segment(tokenId)}`);
  }

  // ── Projects ──────────────────────────────────────────────────────────────

  listProjects(): Promise<ProjectSummary[]> {
    return this.#call("GET", "/projects", { schema: arrayOf(projectSummarySchema) });
  }

  getProject(id: string): Promise<ProjectDetail> {
    return this.#call("GET", `/projects/${segment(id)}`, { schema: projectDetailSchema });
  }

  createProject(id: string, name: string, organizationId: string): Promise<void> {
    return this.#call("POST", `/projects/${segment(id)}`, { body: { name, organizationId } });
  }

  renameProject(id: string, name: string): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/name`, { body: { name } });
  }

  deleteProject(id: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}`);
  }

  setRegistry(id: string, body: SetRegistry): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/registry`, { body });
  }

  clearRegistry(id: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}/registry`);
  }

  /** Where the project's new buckets in Google Cloud Storage are made; moves no bucket. */
  setProjectLocation(id: string, location: string): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/location`, { body: { location } });
  }

  /** The installation's default location for the project's new buckets again. */
  clearProjectLocation(id: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}/location`);
  }

  /** Entries of a project secret, merged into what it holds. The values are never read back. */
  setProjectSecret(id: string, name: string, entries: Record<string, string>): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/secrets/${segment(name)}`, { body: { entries } });
  }

  unsetProjectSecretEntry(id: string, name: string, entry: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}/secrets/${segment(name)}?entry=${segment(entry)}`);
  }

  listProjectSecrets(id: string): Promise<ProjectSecretSummary[]> {
    return this.#call("GET", `/projects/${segment(id)}/secrets`, { schema: arrayOf(projectSecretSummarySchema) });
  }

  /** Declares a topic on the project, or raises its partitions; its compaction and contract with it. */
  declareTopic(id: string, name: string, request: TopicDeclarationRequest): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/topics/${segment(name)}`, { body: request });
  }

  /** The schema document a topic's contract was declared with. */
  topicSchema(id: string, name: string): Promise<unknown> {
    return this.#call("GET", `/projects/${segment(id)}/topics/${segment(name)}/schema`, { schema: { parse: (v: unknown) => v } });
  }

  /** Stops declaring a topic; it stays on the broker. */
  removeTopic(id: string, name: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}/topics/${segment(name)}`);
  }

  listTopics(id: string): Promise<ProjectTopic[]> {
    return this.#call("GET", `/projects/${segment(id)}/topics`, { schema: arrayOf(projectTopicSchema) });
  }

  /** Declares a broker beside the installation's, or changes where it is; the project secret holds its credential. */
  declareBroker(id: string, name: string, request: BrokerDeclarationRequest): Promise<void> {
    return this.#call("PUT", `/projects/${segment(id)}/brokers/${segment(name)}`, { body: request });
  }

  /** Stops declaring a broker; a service naming it is refused at its next start. */
  removeBroker(id: string, name: string): Promise<void> {
    return this.#call("DELETE", `/projects/${segment(id)}/brokers/${segment(name)}`);
  }

  listBrokers(id: string): Promise<ProjectBroker[]> {
    return this.#call("GET", `/projects/${segment(id)}/brokers`, { schema: arrayOf(projectBrokerSchema) });
  }

  // ── Services ──────────────────────────────────────────────────────────────

  listServices(projectId: string): Promise<ServiceStatus[]> {
    return this.#call("GET", `/services/${segment(projectId)}`, { schema: arrayOf(serviceStatusSchema) });
  }

  getService(projectId: string, name: string): Promise<ServiceStatus> {
    return this.#call("GET", `/services/${segment(projectId)}/${segment(name)}`, { schema: serviceStatusSchema });
  }

  /** The descriptor is sent as the text the person gave; the control plane is the one that validates it. */
  applyService(projectId: string, name: string, descriptor: string): Promise<ServiceStatus> {
    return this.#call("PUT", `/services/${segment(projectId)}/${segment(name)}`, {
      rawBody: descriptor,
      schema: serviceStatusSchema,
    });
  }

  serviceOperation(
    projectId: string,
    name: string,
    operation: "pause" | "resume" | "restart" | "expose" | "unexpose",
  ): Promise<void> {
    return this.#call("POST", `/services/${segment(projectId)}/${segment(name)}/${operation}`);
  }

  /** Add a custom hostname; a refusal is the control plane's, shown as it says it. */
  addHostname(projectId: string, name: string, hostname: string): Promise<ServiceStatus> {
    return this.#call("PUT", `/services/${segment(projectId)}/${segment(name)}/hostnames/${segment(hostname)}`, {
      schema: serviceStatusSchema,
    });
  }

  removeHostname(projectId: string, name: string, hostname: string): Promise<ServiceStatus> {
    return this.#call("DELETE", `/services/${segment(projectId)}/${segment(name)}/hostnames/${segment(hostname)}`, {
      schema: serviceStatusSchema,
    });
  }

  /** Issues the service's storage credential again; the old one works for the rotation grace. */
  reissueStorageCredential(projectId: string, name: string): Promise<ServiceStatus> {
    return this.#call("POST", `/services/${segment(projectId)}/${segment(name)}/storage-credential`, { schema: serviceStatusSchema });
  }

  /** Applies the installation's current bucket settings to the service's bucket in Google Cloud Storage. */
  reapplyStorageSettings(projectId: string, name: string): Promise<ServiceStatus> {
    return this.#call("POST", `/services/${segment(projectId)}/${segment(name)}/storage/settings`, { schema: serviceStatusSchema });
  }

  /** Moves the service's bucket from Garage to Google Cloud Storage; an omitted bound is the control plane's default. */
  moveStorage(projectId: string, name: string, writePauseBound?: string): Promise<ServiceStatus> {
    return this.#call("POST", `/services/${segment(projectId)}/${segment(name)}/storage/move`, {
      body: writePauseBound ? { writePauseBound } : {},
      schema: serviceStatusSchema,
    });
  }

  deleteService(projectId: string, name: string): Promise<void> {
    return this.#call("DELETE", `/services/${segment(projectId)}/${segment(name)}`);
  }

  /** Roll a service back to a generation: its descriptor applied again, as a new generation. */
  rollback(projectId: string, name: string, generation: number): Promise<RolledBack> {
    return this.#call("POST", `/services/${segment(projectId)}/${segment(name)}/rollback`, {
      body: { generation },
      schema: rolledBackSchema,
    });
  }

  /** The descriptor applied at a generation, as the control plane accepts one. */
  descriptor(projectId: string, name: string, generation: number): Promise<unknown> {
    return this.#call("GET", `/services/${segment(projectId)}/${segment(name)}/descriptor?generation=${generation}`, {
      schema: anyJson,
    });
  }

  history(projectId: string, name: string): Promise<HistoryEntry[]> {
    return this.#call("GET", `/services/${segment(projectId)}/${segment(name)}/history`, {
      schema: arrayOf(historyEntrySchema),
    });
  }

  logs(projectId: string, name: string, query: LogsQuery = {}): Promise<LogsResponse> {
    const params = new URLSearchParams();
    if (query.instance) params.set("instance", query.instance);
    if (query.previous) params.set("previous", "true");
    if (query.platform) params.set("platform", "true");
    if (query.tail !== undefined) params.set("tail", String(query.tail));
    if (query.since !== undefined) params.set("since", String(query.since));
    const q = params.size > 0 ? `?${params}` : "";
    return this.#call("GET", `/services/${segment(projectId)}/${segment(name)}/logs${q}`, {
      schema: logsResponseSchema,
    });
  }

  /** Every instance's topology, merged by the control plane; 404 when no instance runs. */
  topology(projectId: string, name: string): Promise<ServiceTopology> {
    return this.#call("GET", `/services/${segment(projectId)}/${segment(name)}/topology`, {
      schema: serviceTopologySchema,
    });
  }

  // ── Transport ─────────────────────────────────────────────────────────────

  async #call<T = void>(
    method: string,
    path: string,
    options: {
      body?: unknown;
      rawBody?: string;
      schema?: { parse: (value: unknown) => T };
      anonymous?: boolean;
    } = {},
  ): Promise<T> {
    const url = this.#baseUrl + path;
    const body = options.rawBody ?? (options.body === undefined ? undefined : JSON.stringify(options.body));
    const send = async (bearer: string | null): Promise<Response> => {
      const headers: Record<string, string> = { accept: "application/json" };
      if (bearer) headers.authorization = `Bearer ${bearer}`;
      if (body !== undefined) headers["content-type"] = "application/json";
      try {
        return await this.#transport(url, { method, headers, body });
      } catch (cause) {
        throw new ControlPlaneUnreachable(this.#baseUrl, cause);
      }
    };

    let response: Response;
    if (options.anonymous) {
      response = await send(null);
    } else {
      const bearer = await this.#bearer({ refresh: false });
      if (bearer === null) throw new SignInRequired();
      response = await send(bearer);
      if (response.status === 401) {
        await response.body?.cancel();
        const fresh = await this.#bearer({ refresh: true });
        if (fresh === null) throw new SignInRequired();
        response = await send(fresh);
        if (response.status === 401) {
          await response.body?.cancel();
          throw new SignInRequired();
        }
      }
    }

    if (!response.ok) throw await ControlPlaneError.from(response);
    if (!options.schema) {
      await response.body?.cancel();
      return undefined as T;
    }
    return options.schema.parse(await response.json());
  }
}
