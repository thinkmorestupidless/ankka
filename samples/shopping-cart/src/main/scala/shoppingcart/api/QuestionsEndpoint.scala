package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.{Answer, CartAnswerer, CartTasks}

/** Questions about carts, answered by an autonomous agent while the caller gets on with things. */
final class QuestionsEndpoint(client: ComponentClient) extends HttpEndpoint("/questions"):

  import QuestionsEndpoint.*

  val acl: Acl = Acl.AllowAll

  // docs:start run-task
  /** Starts the work and answers at once, with where to look for the answer. */
  postBody("/ask") { (question: String) =>
    val taskId   = client.forAutonomousAgent(CartAnswerer).runSingleTask(CartTasks.answer, question)
    val instance = client.forTask(taskId).get().assignee.map(_.instanceId).getOrElse("")
    Asked(taskId, instance)
  }
  // docs:end run-task

  // docs:start read-task
  /** Where the task has got to, with the answer once there is one. */
  get("/{taskId}") { (taskId: String) =>
    val task = client.forTask(taskId).get(CartTasks.answer)
    Question(taskId, task.status.wire, task.result, task.reason, task.record.iterations)
  }
  // docs:end read-task

  // docs:start notifications
  /**
   * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
   * notification's JSON — as a JSON string, as every event's data is, so a reader parses the field
   * and then the notification. Watching keeps the instance in memory while the stream is open.
   */
  sse("/answerer/{instanceId}/notifications") { (instanceId: String) =>
    client
      .forAutonomousAgent(CartAnswerer)(instanceId)
      .notifications()
      .map(n => String(Notification.serializer.toBytes(n), "UTF-8"))
  }
  // docs:end notifications

object QuestionsEndpoint:
  final case class Asked(taskId: String, instanceId: String)
  final case class Question(
      taskId: String,
      status: String,
      answer: Option[Answer],
      reason: Option[String],
      iterations: Int
  )

  given JsonValueCodec[Asked]    = Codecs.make
  given JsonValueCodec[Question] = Codecs.make
