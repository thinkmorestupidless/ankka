// `import { ... } from "ankka/testkit"`: the unit testkits (no sidecar) and the integration testkit
// (Postgres and the real sidecar image in Docker). The integration testkit's Docker dependencies,
// `testcontainers` and `@testcontainers/postgresql`, are loaded only when `AnkkaTestKit.start` runs.
export { EventSourcedTestKit, EndpointTestKit, Response, type RequestOptions, type Materialised } from "./unit.ts"
export {
  KeyValueTestKit,
  WorkflowTestKit,
  ViewTestKit,
  ConsumerTestKit,
  GraphConsumerTestKit,
  TimedActionTestKit,
  AgentTestKit,
  ScriptedModel,
  type WorkflowProgress,
  type ModelResponse,
  type ModelCall,
  type GraphChange,
} from "./kinds.ts"
export { AnkkaTestKit, PUBLISHED_SIDECAR, sidecarImage, type AnkkaTestKitOptions, type HttpResponse, type Http, type Beside } from "./integration.ts"
