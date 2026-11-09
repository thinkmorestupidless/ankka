// Questions about carts, answered by an autonomous agent while the caller gets on with things: a task
// is started, read, assigned beside others, and an instance is watched.
import { Acl, Done, done, Endpoint, get, post, s, sse, type Infer } from "ankka"
import { ANSWER, Answer, CartAnswerer } from "./answerer.ts"

export const Asked = s.record("Asked", { taskId: s.string, instanceId: s.string })
export type Asked = Infer<typeof Asked>
export const Question = s.record("Question", {
  taskId: s.string,
  status: s.string,
  answer: s.option(Answer),
  reason: s.option(s.string),
  iterations: s.int,
})
export type Question = Infer<typeof Question>

export class QuestionsEndpoint extends Endpoint {
  static readonly prefix = "/questions"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    // docs:start run-task
    /** Starts the work and answers at once, with where to look for the answer. */
    ask: post("/ask", s.string, Asked, async (ep: QuestionsEndpoint, _req, question) => {
      const taskId = await ep.client.forAutonomousAgent(CartAnswerer).runSingleTask(ANSWER, question)
      const task = await ep.client.forTask(taskId).get()
      return { taskId, instanceId: task.assignee?.instanceId ?? "" }
    }),
    /** Where the task has got to, with the answer once there is one. */
    read: get("/{taskId}", Question, async (ep: QuestionsEndpoint, req) => {
      const task = await ep.client.forTask(req.params.taskId).get(ANSWER)
      return { taskId: task.id, status: task.status, answer: task.result ?? null, reason: task.reason ?? null, iterations: task.iterations }
    }),
    // docs:end run-task
    // docs:start assign-tasks
    /** Two questions for one reviewer, the second once the first has been answered. */
    review: post("/review", Done, async (ep: QuestionsEndpoint) => {
      const first = await ep.client.tasks.create(ANSWER, "What is in cart c-1?")
      const second = await ep.client.tasks.create(ANSWER, "And in c-2?", { dependsOn: [first] })
      await ep.client.forAutonomousAgent(CartAnswerer, "reviewer-1").assign(first, second)
      return done
    }),
    // docs:end assign-tasks
    // docs:start notifications
    /**
     * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
     * notification's JSON — as a JSON string, as every event's data is, so a reader parses the field
     * and then the notification. Watching keeps the instance in memory while the stream is open.
     */
    notifications: sse("/answerer/{instanceId}/notifications", async function* (ep: QuestionsEndpoint, req) {
      for await (const n of ep.client.forAutonomousAgent(CartAnswerer, req.params.instanceId).notifications()) {
        yield JSON.stringify(n)
      }
    }),
    // docs:end notifications
  }
}
