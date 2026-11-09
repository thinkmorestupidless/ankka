// An autonomous agent whose tools wait for a person: the instance records the request on its own
// record and the task waits, spending nothing, until someone decides.
import { Acl, AutonomousAgent, Done, done, Endpoint, post, s, taskAcceptance, taskType, tool } from "ankka"

/** What the tools did, for a test to read. */
export const runs: string[] = []

export const OPERATE = taskType("operate", "Keep a service healthy, restarting it or draining a node when needed")

const Service = s.record("Service", { service: s.string })
const Node = s.record("Node", { node: s.string })

export class Operator extends AutonomousAgent {
  static readonly componentId = "operator"
  static readonly description = "Looks after services"

  // docs:start autonomous-requires-approval
  static readonly tools = {
    restartService: tool(
      "restart_service",
      "Restarts a service. An operator approves every restart.",
      Service,
      (_a: Operator, { service }) => {
        runs.push(`restart_service(${service})`)
        return `restarted ${service}`
      },
      { approval: true },
    ),
    drainNode: tool(
      "drain_node",
      "Drains a node of its work.",
      Node,
      (_a: Operator, { node }) => {
        runs.push(`drain_node(${node})`)
        return `drained ${node}`
      },
      { approval: { withinMs: 2_000 } },
    ),
    readMetrics: tool("read_metrics", "Reads a service's metrics.", Service, (_a: Operator, { service }) => {
      runs.push(`read_metrics(${service})`)
      return `${service} is healthy`
    }),
  }
  // docs:end autonomous-requires-approval

  static readonly accepts = [taskAcceptance(OPERATE, { maxIterations: 10 })]
}

const Decision = s.record("Decision", { approvalId: s.string, by: s.string })

/** Where a person's decision on an instance's request arrives; this route's ACL is who may decide. */
export class OperatorEndpoint extends Endpoint {
  static readonly prefix = "/operators"
  static readonly acl = Acl.authenticated

  static readonly routes = {
    // docs:start decide
    decide: post("/{instanceId}/decisions", Decision, Done, async (ep: OperatorEndpoint, req, { approvalId, by }) => {
      await ep.client.forAutonomousAgent(Operator, req.params.instanceId).decide(approvalId, { approved: true, by })
      return done
    }),
    // docs:end decide
  }
}
