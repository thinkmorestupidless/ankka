/**
 * The control plane's wire types, mirrored from `controlplane-api`'s `descriptors.scala`.
 *
 * Mapping rules, the same everywhere: a Scala `Option` is an optional field (absent or `null`), an
 * `Instant` is an ISO-8601 string, a `LocalDate` is `YYYY-MM-DD`, an enum is its word, and a key this
 * side does not know is dropped on decode, so a newer control plane answers an older console.
 * `console/package/fixtures/control-plane/` holds what the platform's own codecs write for each type,
 * and the package's tests decode every one of them.
 */
import { z } from "zod";

const optional = <T extends z.ZodType>(schema: T) => schema.nullish().transform((v) => v ?? undefined);

/** `Role`'s codec writes the lowercase word. */
export const roleSchema = z.enum(["owner", "member"]);
export type Role = z.infer<typeof roleSchema>;

/**
 * The words `ServiceLifecycle`'s codec writes today. The schema accepts any string, so a word a newer
 * control plane adds is shown as it is rather than failing the page.
 */
export const serviceLifecycles = [
  "NotDeployed",
  "UpdateInProgress",
  "Ready",
  "PartiallyReady",
  "Unavailable",
  "Paused",
  "Failed",
  "Suspended",
] as const;
export type KnownServiceLifecycle = (typeof serviceLifecycles)[number];
export const serviceLifecycleSchema = z.string();
export type ServiceLifecycle = KnownServiceLifecycle | (string & {});

export const authDiscoverySchema = z.object({
  issuer: z.string(),
  clientId: z.string(),
  audience: z.string(),
});
export type AuthDiscovery = z.infer<typeof authDiscoverySchema>;

export const organizationMembershipSchema = z.object({
  id: z.string(),
  name: z.string(),
  role: roleSchema,
});
export type OrganizationMembership = z.infer<typeof organizationMembershipSchema>;

export const whoamiSchema = z.object({
  subject: z.string(),
  name: optional(z.string()),
  email: optional(z.string()),
  emailVerified: z.boolean().default(false),
  platformAdmin: z.boolean().default(false),
  organizations: z.array(organizationMembershipSchema).default([]),
});
export type Whoami = z.infer<typeof whoamiSchema>;

export const quotaSchema = z.object({
  projects: optional(z.number().int()),
  services: optional(z.number().int()),
  instances: optional(z.number().int()),
});
export type Quota = z.input<typeof quotaSchema>;

export const usageSchema = z.object({
  projects: z.number().int(),
  services: z.number().int(),
  instances: z.number().int(),
});
export type Usage = z.infer<typeof usageSchema>;

const zeroUsage: Usage = { projects: 0, services: 0, instances: 0 };

export const organizationDetailSchema = z.object({
  id: z.string(),
  name: z.string(),
  disabled: z.boolean().default(false),
  quota: optional(quotaSchema),
  usage: usageSchema.default(zeroUsage),
});
export type OrganizationDetail = z.infer<typeof organizationDetailSchema>;

export const organizationSummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  projects: z.number().int(),
  disabled: z.boolean().default(false),
  role: optional(roleSchema),
  quota: optional(quotaSchema),
  usage: usageSchema.default(zeroUsage),
});
export type OrganizationSummary = z.infer<typeof organizationSummarySchema>;

export const registrySummarySchema = z.object({
  server: z.string(),
  username: z.string(),
  setAt: optional(z.string()),
  setBy: optional(z.string()),
});
export type RegistrySummary = z.infer<typeof registrySummarySchema>;

export const projectDetailSchema = z.object({
  id: z.string(),
  name: z.string(),
  organizationId: z.string(),
  registry: optional(registrySummarySchema),
});
export type ProjectDetail = z.infer<typeof projectDetailSchema>;

export const projectSummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  organizationId: z.string(),
  services: z.number().int(),
  registry: optional(registrySummarySchema),
});
export type ProjectSummary = z.infer<typeof projectSummarySchema>;

export const mountStatusSchema = z.object({
  path: z.string(),
  service: z.string(),
  state: z.string().default(""),
});
export type MountStatus = z.infer<typeof mountStatusSchema>;

export const serviceStatusSchema = z.object({
  name: z.string(),
  projectId: z.string(),
  lifecycle: serviceLifecycleSchema,
  generation: z.number().int(),
  image: z.string(),
  readyInstances: z.number().int(),
  desiredInstances: z.number().int(),
  detail: optional(z.string()),
  confirmed: z.boolean().default(true),
  database: optional(z.string()),
  hostname: optional(z.string()),
  exposed: z.boolean().default(false),
  suspended: z.boolean().default(false),
  paused: z.boolean().default(false),
  hosting: z.string().default("embedded"),
  protocol: optional(z.string()),
  mounts: z.array(mountStatusSchema).default([]),
  callers: z.array(z.string()).default([]),
  processPort: optional(z.number().int()),
  broker: optional(z.string()),
  /** Topics the service's components use that its project does not declare; absent when not read. */
  undeclaredTopics: optional(z.array(z.string())),
  /** Each side the service takes on a declared topic with a contract, checked against it; absent when not read. */
  topicChecks: optional(z.array(z.lazy(() => topicCheckSchema))),
  /** Each topic source of the service with how far behind it is, from its instances; absent when not read. */
  topicSources: optional(z.array(z.lazy(() => topicSourceReportSchema))),
  objectStorage: optional(z.string()),
  bucket: optional(z.string()),
  bucketAddress: optional(z.string()),
  /** `mounted` when the service's instances read their project's grants (feature 040). */
  grants: optional(z.string()),
  /** Each other project's topic the service uses, and whether that project grants it; absent when not read. */
  crossProjectTopics: optional(z.array(z.lazy(() => crossProjectTopicSchema))),
});
export type ServiceStatus = z.infer<typeof serviceStatusSchema>;

export const crossProjectTopicSchema = z.object({
  project: z.string(),
  topic: z.string(),
  right: z.string(),
  status: z.string(),
});
export type CrossProjectTopic = z.infer<typeof crossProjectTopicSchema>;

export const historyActorSchema = z.object({
  subject: z.string(),
  display: optional(z.string()),
  administrative: z.boolean().default(false),
});
export type HistoryActor = z.infer<typeof historyActorSchema>;

export const historyEntrySchema = z.object({
  kind: z.string(),
  generation: z.number().int(),
  actor: optional(historyActorSchema),
  at: optional(z.string()),
  /** On an entry that recorded a descriptor: its image, and a digest two such entries share exactly when the descriptors are the same. */
  image: optional(z.string()),
  digest: optional(z.string()),
  /** On a rollback: the generation whose descriptor was applied again. */
  rolledBackTo: optional(z.number().int()),
});
export type HistoryEntry = z.infer<typeof historyEntrySchema>;

export const rollbackRequestSchema = z.object({ generation: optional(z.number().int()) });
export type RollbackRequest = z.infer<typeof rollbackRequestSchema>;

export const rolledBackSchema = z.object({ rolledBackTo: z.number().int(), status: serviceStatusSchema });
export type RolledBack = z.infer<typeof rolledBackSchema>;

export const instanceLogsSchema = z.object({
  instance: z.string(),
  output: z.string(),
  error: optional(z.string()),
});
export type InstanceLogs = z.infer<typeof instanceLogsSchema>;

export const logsResponseSchema = z.object({
  instances: z.array(instanceLogsSchema),
});
export type LogsResponse = z.infer<typeof logsResponseSchema>;

// ── Topology ──────────────────────────────────────────────────────────────

export const topologyHandlerSchema = z.object({
  name: z.string(),
  type: z.string(),
  streaming: optional(z.boolean()),
});
export type TopologyHandler = z.infer<typeof topologyHandlerSchema>;

export const topologyNodeSchema = z.object({
  id: z.string(),
  kind: z.string(),
  layer: z.number().int(),
  platform: z.boolean(),
  handlers: z.array(topologyHandlerSchema),
});
export type TopologyNode = z.infer<typeof topologyNodeSchema>;

export const declaredEdgeSchema = z.object({ from: z.string(), to: z.string(), kind: z.string() });
export type DeclaredEdge = z.infer<typeof declaredEdgeSchema>;

export const callPairSchema = z.object({
  caller: z.string(),
  callee: z.string(),
  handled: z.object({ ok: z.number(), refused: z.number(), failed: z.number() }),
  unanswered: z.object({ timedOut: z.number(), undelivered: z.number() }),
  durationMillis: z.object({ p50: z.number(), p99: z.number(), max: z.number(), bucketed: z.boolean().default(true) }),
  streaming: z.boolean().default(false),
  histogram: z.array(z.number()).default([]),
});
export type CallPair = z.infer<typeof callPairSchema>;

export const callEdgeSchema = z.object({ from: z.string(), to: z.string(), pairs: z.array(callPairSchema) });
export type CallEdge = z.infer<typeof callEdgeSchema>;

export const topologyWindowSchema = z.object({
  seconds: z.number(),
  since: z.string(),
  calls: z.number(),
  unanswered: z.number().default(0),
});
export type TopologyWindow = z.infer<typeof topologyWindowSchema>;

export const instanceTopologyDocumentSchema = z.object({
  service: z.object({ name: z.string(), runtime: z.string(), instance: z.string(), startedAt: z.string() }),
  window: topologyWindowSchema,
  nodes: z.array(topologyNodeSchema),
  declared: z.array(declaredEdgeSchema),
  calls: z.array(callEdgeSchema),
});
export type InstanceTopologyDocument = z.infer<typeof instanceTopologyDocumentSchema>;

export const instanceStatusSchema = z.enum(["ok", "unreachable", "unsupported", "failed"]);
export type InstanceStatus = z.infer<typeof instanceStatusSchema>;

export const instanceTopologySchema = z.object({
  pod: z.string(),
  status: instanceStatusSchema,
  problem: optional(z.string()),
  runtime: optional(z.string()),
  readAt: optional(z.string()),
});
export type InstanceTopology = z.infer<typeof instanceTopologySchema>;

export const topologyDifferenceSchema = z.object({ node: z.string(), presentOn: z.array(z.string()) });
export type TopologyDifference = z.infer<typeof topologyDifferenceSchema>;

/** `GET /services/{projectId}/{name}/topology`: every instance's topology, merged. */
export const serviceTopologySchema = z.object({
  service: z.string(),
  running: z.number().int(),
  contributing: z.number().int(),
  partial: z.boolean(),
  instances: z.array(instanceTopologySchema),
  window: topologyWindowSchema,
  nodes: z.array(topologyNodeSchema),
  declared: z.array(declaredEdgeSchema),
  calls: z.array(callEdgeSchema),
  differences: z.array(topologyDifferenceSchema),
});
export type ServiceTopology = z.infer<typeof serviceTopologySchema>;

export const memberSummarySchema = z.object({
  subject: z.string(),
  role: roleSchema,
  email: optional(z.string()),
  display: optional(z.string()),
  since: optional(z.string()),
  addedBy: optional(z.string()),
});
export type MemberSummary = z.infer<typeof memberSummarySchema>;

export const invitationSummarySchema = z.object({
  email: z.string(),
  role: roleSchema,
  invitedAt: optional(z.string()),
  invitedBy: optional(z.string()),
});
export type InvitationSummary = z.infer<typeof invitationSummarySchema>;

export const membersResponseSchema = z.object({
  members: z.array(memberSummarySchema).default([]),
  invitations: z.array(invitationSummarySchema).default([]),
});
export type MembersResponse = z.infer<typeof membersResponseSchema>;

export const deployTokenSummarySchema = z.object({
  id: z.string(),
  label: z.string(),
  subject: z.string(),
  createdBy: optional(z.string()),
  createdAt: optional(z.string()),
  expiresAt: optional(z.string()),
  lastUsed: optional(z.string()),
});
export type DeployTokenSummary = z.infer<typeof deployTokenSummarySchema>;

export const deployTokenCreatedSchema = z.object({
  id: z.string(),
  label: z.string(),
  secret: z.string(),
  subject: z.string(),
  expiresAt: optional(z.string()),
});
export type DeployTokenCreated = z.infer<typeof deployTokenCreatedSchema>;

export const errorBodySchema = z.object({
  status: z.number().int(),
  error: z.string(),
});
export type ErrorBody = z.infer<typeof errorBodySchema>;

// ── Request bodies ──────────────────────────────────────────────────────────

export const ownerSchema = z.object({
  subject: z.string(),
  email: optional(z.string()),
  display: optional(z.string()),
});
export type Owner = z.input<typeof ownerSchema>;

export const createOrganizationSchema = z.object({ name: z.string(), owner: optional(ownerSchema) });
export type CreateOrganization = z.input<typeof createOrganizationSchema>;

export const createProjectSchema = z.object({ name: z.string(), organizationId: z.string() });
export type CreateProject = z.input<typeof createProjectSchema>;

export const renameSchema = z.object({ name: z.string() });
export type Rename = z.input<typeof renameSchema>;

export const inviteSchema = z.object({ email: z.string(), role: roleSchema.default("member") });
export type Invite = z.input<typeof inviteSchema>;

export const roleChangeSchema = z.object({ role: roleSchema });
export type RoleChange = z.input<typeof roleChangeSchema>;

export const repairSchema = z.object({ role: roleSchema.default("owner") });
export type Repair = z.input<typeof repairSchema>;

export const createDeployTokenSchema = z.object({
  label: z.string(),
  expiresIn: optional(z.number().int()),
});
export type CreateDeployToken = z.input<typeof createDeployTokenSchema>;

export const setRegistrySchema = z.object({
  server: z.string(),
  username: z.string(),
  password: z.string(),
});
export type SetRegistry = z.input<typeof setRegistrySchema>;

/** `PUT /projects/{id}/secrets/{name}`: entries merged into a project secret. */
export const setProjectSecretSchema = z.object({
  entries: z.record(z.string(), z.string()),
});
export type SetProjectSecret = z.input<typeof setProjectSecretSchema>;

/** A contract as declared on a topic: its name and the fingerprint of its schema document. */
export const contractSchema = z.object({
  name: z.string(),
  fingerprint: z.string(),
});
export type Contract = z.infer<typeof contractSchema>;

/** One side of a declared topic as a running service states it, against the declared contract. */
export const topicCheckSchema = z.object({
  topic: optional(z.string()),
  service: z.string(),
  component: z.string(),
  direction: z.string(),
  stated: optional(z.string()),
  state: z.string(),
});
export type TopicCheck = z.infer<typeof topicCheckSchema>;

/**
 * `PUT /projects/{id}/topics/{name}`: the partitions a declared topic has, whether the broker keeps
 * only the last message under each key, and the contract it carries with its schema document.
 */
export const topicDeclarationRequestSchema = z.object({
  partitions: z.number().int(),
  compacted: z.boolean().default(false),
  contract: optional(z.object({ name: z.string(), schema: z.unknown() })),
});
export type TopicDeclarationRequest = z.input<typeof topicDeclarationRequestSchema>;

/** A topic a project declares, and how far the platform has got with it. */
export const projectTopicSchema = z.object({
  name: z.string(),
  partitions: z.number().int(),
  phase: optional(z.string()),
  detail: optional(z.string()),
  compacted: z.boolean().default(false),
  contract: optional(contractSchema),
  checks: z.array(topicCheckSchema).default([]),
});
export type ProjectTopic = z.infer<typeof projectTopicSchema>;

/**
 * A topic source as a service's instances report it: what reads which topic under which group, the
 * declared broker and contract it states, `lag` (messages past the last one handled, as of the last
 * poll) and `failing` (the reason of the change being delivered again, until one succeeds).
 */
export const topicSourceReportSchema = z.object({
  kind: z.string(),
  component: z.string(),
  topic: z.string(),
  group: z.string(),
  start: z.string(),
  version: z.number().int(),
  recordedVersion: optional(z.number().int()),
  behind: z.boolean().default(false),
  broker: optional(z.string()),
  contract: optional(z.string()),
  lag: optional(z.number().int()),
  failing: optional(z.string()),
});
export type TopicSourceReport = z.infer<typeof topicSourceReportSchema>;

/** `PUT /projects/{id}/brokers/{name}`: a broker declared beside the installation's, and how it is reached. */
export const brokerDeclarationRequestSchema = z.object({
  bootstrap: z.string(),
  shape: z.string(),
  secret: z.string(),
});
export type BrokerDeclarationRequest = z.input<typeof brokerDeclarationRequestSchema>;

/** A broker a project declares, as listed: where it is, the shape of its credential, and the project secret holding it. */
export const projectBrokerSchema = z.object({
  name: z.string(),
  bootstrap: z.string(),
  shape: z.string(),
  secret: z.string(),
  declaredAt: optional(z.string()),
});
export type ProjectBroker = z.infer<typeof projectBrokerSchema>;

/** A project secret as the control plane lists it: entries' names, never a value. */
export const projectSecretSummarySchema = z.object({
  name: z.string(),
  entries: z.array(z.string()),
  setAt: optional(z.string()),
  setBy: optional(z.string()),
});
export type ProjectSecretSummary = z.infer<typeof projectSecretSummarySchema>;

// ── Grants and machines (feature 040) ───────────────────────────────────────

/** What a grant opens: one route, one gRPC method, one topic with one right, or the erasure. */
export const grantTargetSchema = z.object({
  kind: z.string(),
  service: optional(z.string()),
  method: optional(z.string()),
  path: optional(z.string()),
  topic: optional(z.string()),
  right: optional(z.string()),
  decrypt: z.boolean().default(false),
});
export type GrantTarget = z.infer<typeof grantTargetSchema>;

/** Who did something to a grant, and when. */
export const grantActSchema = z.object({
  by: optional(z.string()),
  at: optional(z.string()),
});
export type GrantAct = z.infer<typeof grantActSchema>;

export const grantRequestSchema = z.object({
  grantee: z.string(),
  target: grantTargetSchema,
});
export type GrantRequest = z.infer<typeof grantRequestSchema>;

/** A grant as its project lists it; `effect` is `in effect`, or why not. */
export const grantDetailSchema = z.object({
  id: z.string(),
  grantee: z.string(),
  target: grantTargetSchema,
  state: z.string(),
  effect: z.string(),
  granted: grantActSchema,
  answered: optional(grantActSchema),
  ended: optional(grantActSchema),
});
export type GrantDetail = z.infer<typeof grantDetailSchema>;

export const grantChangeRecordSchema = z.object({
  change: z.string(),
  by: optional(z.string()),
  at: optional(z.string()),
});

export const topicSettingsSchema = z.object({
  partitions: z.number().int(),
  compacted: z.boolean().default(false),
  retention: optional(z.string()),
  copies: optional(z.number().int()),
});

/** A grant as its grantee's side records it. */
export const receivedGrantDetailSchema = z.object({
  id: z.string(),
  grantingProject: z.string(),
  grantingOrganization: z.string(),
  grantee: z.string(),
  target: grantTargetSchema,
  state: z.string(),
  changes: z.array(grantChangeRecordSchema).default([]),
  topic: optional(topicSettingsSchema),
});
export type ReceivedGrantDetail = z.infer<typeof receivedGrantDetailSchema>;

export const machineRegistrationSchema = z.object({ name: z.string() });

/** A machine just registered: its secret, shown this once. */
export const machineRegisteredSchema = z.object({
  name: z.string(),
  clientId: z.string(),
  clientSecret: z.string(),
  tokenUrl: z.string(),
  brokerBootstrap: optional(z.string()),
});
export type MachineRegistered = z.infer<typeof machineRegisteredSchema>;

export const byteRatesRequestSchema = z.object({
  produceBytesPerSecond: z.number().int(),
  consumeBytesPerSecond: z.number().int(),
  requestPercentage: z.number().int(),
});
export type ByteRatesRequest = z.infer<typeof byteRatesRequestSchema>;

export const machineSummarySchema = z.object({
  name: z.string(),
  clientId: z.string(),
  registeredBy: optional(z.string()),
  registeredAt: optional(z.string()),
  byteRates: optional(byteRatesRequestSchema),
});
export type MachineSummary = z.infer<typeof machineSummarySchema>;

export const tokenResponseSchema = z.object({
  access_token: z.string(),
  token_type: z.string(),
  expires_in: z.number().int(),
});

export const jsonWebKeySetSchema = z.object({
  keys: z.array(
    z.object({ kty: z.string(), use: z.string(), alg: z.string(), kid: z.string(), n: z.string(), e: z.string() }),
  ),
});

/** Every schema by the Scala type's name, as the fixture files name them. */
export const schemasByType: Record<string, z.ZodType> = {
  AuthDiscovery: authDiscoverySchema,
  OrganizationMembership: organizationMembershipSchema,
  Whoami: whoamiSchema,
  Quota: quotaSchema,
  Usage: usageSchema,
  OrganizationDetail: organizationDetailSchema,
  OrganizationSummary: organizationSummarySchema,
  RegistrySummary: registrySummarySchema,
  ProjectDetail: projectDetailSchema,
  ProjectSummary: projectSummarySchema,
  MountStatus: mountStatusSchema,
  ServiceStatus: serviceStatusSchema,
  HistoryActor: historyActorSchema,
  HistoryEntry: historyEntrySchema,
  RollbackRequest: rollbackRequestSchema,
  RolledBack: rolledBackSchema,
  InstanceLogs: instanceLogsSchema,
  LogsResponse: logsResponseSchema,
  InstanceTopologyDocument: instanceTopologyDocumentSchema,
  InstanceTopology: instanceTopologySchema,
  ServiceTopology: serviceTopologySchema,
  MemberSummary: memberSummarySchema,
  InvitationSummary: invitationSummarySchema,
  MembersResponse: membersResponseSchema,
  DeployTokenSummary: deployTokenSummarySchema,
  DeployTokenCreated: deployTokenCreatedSchema,
  ErrorBody: errorBodySchema,
  Owner: ownerSchema,
  CreateOrganization: createOrganizationSchema,
  CreateProject: createProjectSchema,
  Rename: renameSchema,
  Invite: inviteSchema,
  RoleChange: roleChangeSchema,
  Repair: repairSchema,
  CreateDeployToken: createDeployTokenSchema,
  SetRegistry: setRegistrySchema,
  SetProjectSecret: setProjectSecretSchema,
  ProjectSecretSummary: projectSecretSummarySchema,
  TopicDeclarationRequest: topicDeclarationRequestSchema,
  ProjectTopic: projectTopicSchema,
  Contract: contractSchema,
  BrokerDeclarationRequest: brokerDeclarationRequestSchema,
  ProjectBroker: projectBrokerSchema,
  GrantRequest: grantRequestSchema,
  GrantDetail: grantDetailSchema,
  ReceivedGrantDetail: receivedGrantDetailSchema,
  MachineRegistration: machineRegistrationSchema,
  MachineRegistered: machineRegisteredSchema,
  MachineSummary: machineSummarySchema,
  ByteRatesRequest: byteRatesRequestSchema,
  TokenResponse: tokenResponseSchema,
  JsonWebKeySet: jsonWebKeySetSchema,
};
