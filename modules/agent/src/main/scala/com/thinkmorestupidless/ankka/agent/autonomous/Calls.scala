package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

/**
 * What the client and an autonomous agent's host say to each other.
 *
 * Operations on an instance are ordinary calls to the agent's component under these reserved method
 * names; the host answers them itself. They live in one place so the two ends cannot disagree about
 * a name or a payload.
 */
private[ankka] object HostProtocol:
  val Assign: MethodName        = MethodName("assign")
  val RunSingleTask: MethodName = MethodName("run-single-task")
  val Dequeue: MethodName       = MethodName("dequeue")
  val Suspend: MethodName       = MethodName("suspend")
  val Resume: MethodName        = MethodName("resume")
  val Terminate: MethodName     = MethodName("terminate")
  val Notifications: MethodName = MethodName("notifications")

  final case class AssignRequest(taskIds: Vector[String])
  final case class RunSingle(taskId: String)
  final case class DequeueRequest(taskId: String, reason: String)

  val assignRequest: Serializer[AssignRequest] = Codecs.serializer[AssignRequest]("agent-assign")
  val runSingle: Serializer[RunSingle]         = Codecs.serializer[RunSingle]("agent-run-single")
  val dequeue: Serializer[DequeueRequest]      = Codecs.serializer[DequeueRequest]("agent-dequeue")
  val assignResult: Serializer[AssignResult] =
    Codecs.serializer[AssignResult]("agent-assign-result")

/**
 * What an assignment did. The task records decide: a task that was not pending, or does not exist,
 * is refused by its record and is not queued.
 */
final case class AssignResult(accepted: Vector[String], refused: Map[String, AssignResult.Refusal]):
  def allAccepted: Boolean = refused.isEmpty

object AssignResult:
  final case class Refusal(message: String, code: String)

/** A task as its record says it is, with the result decoded as the task type's. */
final case class TypedTaskSnapshot[R](record: TaskRecord, result: Option[R]):
  def status: TaskStatus         = record.status
  def reason: Option[String]     = record.reason
  def assignee: Option[Assignee] = record.assignee

/**
 * Calls about tasks, addressed by task id.
 *
 * {{{
 * val id = client.tasks.create(CatalogueTasks.answer, "How many red items?").create()
 * client.forAutonomousAgent(CatalogueAnswerer)("reviewer-1").assign(id)
 * val done = client.forTask(id).await(CatalogueTasks.answer)
 * }}}
 */
final class TaskCalls private[autonomous] (client: ComponentClient, taskId: String):

  private def entity = client.forEventSourcedEntity(EntityId(taskId))

  /** The record as it stands. */
  def get(): TaskRecord = entity.call(TaskEntity.get).invoke()

  def getAsync(): Future[TaskRecord] = entity.call(TaskEntity.get).invokeAsync()

  /** The record with its result decoded; a task of another type is `BadRequest`. */
  def get[R](task: TaskType[R]): TypedTaskSnapshot[R] = typed(task, get())

  /**
   * Waits until the task has completed, failed or been cancelled.
   *
   * Polls the record on the calling thread, which for an endpoint or a component is a virtual
   * thread, so waiting costs nothing. Fails with `Timeout`, naming where the task had got to, if it
   * has not ended within `timeout`.
   */
  def await[R](task: TaskType[R], timeout: FiniteDuration = 10.minutes): TypedTaskSnapshot[R] =
    val deadline = timeout.fromNow
    var record   = get()
    while !record.status.terminal do
      if deadline.isOverdue() then
        throw CommandError(
          s"task '$taskId' had not ended after $timeout; it is ${record.status.wire}" +
            record.reason.fold("")(r => s" ($r)"),
          ErrorCode.Timeout
        )
      scala.concurrent.blocking(Thread.sleep(TaskCalls.PollInterval.toMillis))
      record = get()
    typed(task, record)

  /**
   * Cancels the task. A pending or queued task is cancelled at once; one an agent is working on is
   * cancelled when that agent next reaches the end of an iteration. A task that has already ended
   * is refused with `Conflict`.
   */
  def cancel(reason: String = "cancelled by caller"): Done =
    entity.call(TaskEntity.cancel).invoke(TaskEntity.Cancel(reason))
    // The record is the truth; telling the assignee is so that it notices at once, and so that its
    // queue no longer names the task when this returns. An assignee that cannot be told will see
    // the record at its next boundary anyway.
    get().assignee.foreach { a =>
      try
        ComponentClient.await(
          client.transportRef.ask(
            ComponentId(a.componentId),
            EntityId(a.instanceId),
            HostProtocol.Dequeue,
            HostProtocol.dequeue.toBytes(HostProtocol.DequeueRequest(taskId, reason)),
            Metadata.empty
          ),
          client.transportRef.askTimeout
        ): Unit
      catch case scala.util.control.NonFatal(_) => ()
    }
    Done

  private def typed[R](task: TaskType[R], record: TaskRecord): TypedTaskSnapshot[R] =
    if record.typeName != task.name then
      throw CommandError(
        s"task '$taskId' is a '${record.typeName}' task, not '${task.name}'",
        ErrorCode.BadRequest
      )
    val result = record.result.map(stored =>
      task
        .decode(stored)
        .fold(
          e =>
            throw CommandError(
              s"task '$taskId' has a result that does not decode: $e",
              ErrorCode.Internal
            ),
          identity
        )
    )
    TypedTaskSnapshot(record, result)

object TaskCalls:
  private val PollInterval = 250.millis

/** Creates tasks. */
final class TaskCreation private[autonomous] (client: ComponentClient):
  def create[R](task: TaskType[R], instructions: String): TaskBuilder[R] =
    TaskBuilder(client, task, instructions, None, Vector.empty, Vector.empty)

/** A task about to be created. `create()` creates it and answers its id. */
final case class TaskBuilder[R] private[autonomous] (
    private val client: ComponentClient,
    task: TaskType[R],
    instructions: String,
    id: Option[String],
    attachments: Vector[Attachment],
    dependencies: Vector[String]
):
  def withId(taskId: String): TaskBuilder[R] = copy(id = Some(taskId))

  def attach(name: String, contentType: String, content: String): TaskBuilder[R] =
    copy(attachments =
      attachments :+ Attachment(name, contentType, AttachmentContent.Inline(content))
    )

  def attachReference(name: String, contentType: String, uri: String): TaskBuilder[R] =
    copy(attachments =
      attachments :+ Attachment(name, contentType, AttachmentContent.Reference(uri))
    )

  /** Tasks that must complete before this one starts. Each must already exist. */
  def dependsOn(taskIds: String*): TaskBuilder[R] = copy(dependencies = dependencies ++ taskIds)

  /**
   * Creates the task.
   *
   * A dependency that does not exist is `NotFound`, before anything is written. Each dependency
   * then records this task as its dependent, so that it can cancel it if it fails; one that has
   * already failed or been cancelled answers so, and this task is cancelled at once rather than
   * waiting for a dependency that will never complete.
   */
  def create(): String =
    val taskId = id.getOrElse(UUID.randomUUID().toString)
    dependencies.distinct.foreach(dep => client.forTask(dep).get(): Unit)
    client
      .forEventSourcedEntity(EntityId(taskId))
      .call(TaskEntity.createTask)
      .invoke(TaskEntity.Create(task.name, instructions, attachments, dependencies.distinct))
    val ended = dependencies.distinct.iterator
      .map(dep =>
        dep -> client
          .forEventSourcedEntity(EntityId(dep))
          .call(TaskEntity.addDependent)
          .invoke(TaskEntity.AddDependent(taskId))
      )
      .collectFirst { case (dep, TaskEntity.DependentAdded(Some(status), _)) => dep -> status }
    ended.foreach { (dep, status) =>
      client
        .forEventSourcedEntity(EntityId(taskId))
        .call(TaskEntity.cancel)
        .invoke(TaskEntity.Cancel(s"dependency '$dep' ${status.wire}"))
    }
    taskId

  def createAsync(): Future[String] =
    Future(create())(using AnkkaExecutors.virtual)

/**
 * Calls to one instance of an autonomous agent.
 *
 * An instance is created by its first assignment and exists until it is terminated; its id is the
 * caller's to choose.
 */
final class AutonomousAgentCalls private[autonomous] (
    client: ComponentClient,
    componentId: ComponentId,
    instanceId: String
):
  private def transport: CallTransport = client.transportRef

  private def ask(method: MethodName, payload: Array[Byte]): Future[Array[Byte]] =
    transport.ask(componentId, EntityId(instanceId), method, payload, Metadata.empty)

  private def await[A](future: Future[A]): A = ComponentClient.await(future, transport.askTimeout)

  /** Queues existing, pending tasks on this instance, in order. */
  def assign(taskIds: String*): AssignResult = await(assignAsync(taskIds*))

  def assignAsync(taskIds: String*): Future[AssignResult] =
    ask(
      HostProtocol.Assign,
      HostProtocol.assignRequest.toBytes(HostProtocol.AssignRequest(taskIds.toVector))
    )
      .map(HostProtocol.assignResult.fromBytes)(using ExecutionContext.parasitic)

  /** Stops before the next model call, keeping the task and the queue, until resumed. */
  def suspend(): Done              = await(suspendAsync())
  def suspendAsync(): Future[Done] = done(ask(HostProtocol.Suspend, Array.emptyByteArray))

  def resume(): Done              = await(resumeAsync())
  def resumeAsync(): Future[Done] = done(ask(HostProtocol.Resume, Array.emptyByteArray))

  /**
   * Stops the instance for good. Its tasks return to pending, unassigned, for another instance to
   * take; the id cannot be used again.
   */
  def terminate(): Done              = await(terminateAsync())
  def terminateAsync(): Future[Done] = done(ask(HostProtocol.Terminate, Array.emptyByteArray))

  /**
   * Where the instance has got to. Read from its record, so asking does not wake an instance that
   * is not running.
   */
  def state(): AgentState = await(stateAsync())

  def stateAsync(): Future[AgentState] =
    client
      .forEventSourcedEntity(InstanceEntity.idFor(componentId, instanceId))
      .call(InstanceEntity.get)
      .invokeAsync()
      .map(record => AgentState.from(record, None))(using ExecutionContext.parasitic)

  /**
   * What the instance does from now on, as it happens. Nothing is replayed, and a subscriber that
   * reads too slowly is told how many it missed rather than slowing the instance. Subscribing keeps
   * the instance in memory while the stream is open.
   */
  def notifications(): Source[Notification, NotUsed] =
    NotificationSource(transport, componentId, instanceId)

  private def done(f: Future[Array[Byte]]): Future[Done] =
    f.map(_ => Done)(using ExecutionContext.parasitic)

/** Runs one task on an instance the platform creates for it, which ends when the task does. */
final class SingleTaskCalls private[autonomous] (client: ComponentClient, componentId: ComponentId):

  /** Creates the task, starts an instance on it and answers the task's id at once. */
  def runSingleTask[R](task: TaskType[R], instructions: String): String =
    runSingleTask(client.tasks.create(task, instructions))

  def runSingleTask[R](builder: TaskBuilder[R]): String =
    val taskId     = builder.create()
    val instanceId = UUID.randomUUID().toString
    ComponentClient.await(
      client.transportRef.ask(
        componentId,
        EntityId(instanceId),
        HostProtocol.RunSingleTask,
        HostProtocol.runSingle.toBytes(HostProtocol.RunSingle(taskId)),
        Metadata.empty
      ),
      client.transportRef.askTimeout
    ): Unit
    taskId

/**
 * Adds tasks and autonomous agents to `ComponentClient`, which lives in a module that cannot see
 * them.
 */
extension (client: ComponentClient)
  def tasks: TaskCreation = TaskCreation(client)

  def forTask(taskId: String): TaskCalls = TaskCalls(client, taskId)

  def forAutonomousAgent(companion: AutonomousAgent.Companion[?])(
      instanceId: String
  ): AutonomousAgentCalls =
    forAutonomousAgent(companion.componentId, instanceId)

  /** One instance, by its component's id — how a caller without the companion reaches it. */
  def forAutonomousAgent(componentId: ComponentId, instanceId: String): AutonomousAgentCalls =
    if instanceId.isEmpty then throw CommandError("an instance needs an id", ErrorCode.BadRequest)
    else AutonomousAgentCalls(client, componentId, instanceId)

  /** For running one task on an instance the platform names. */
  def forAutonomousAgent(companion: AutonomousAgent.Companion[?]): SingleTaskCalls =
    SingleTaskCalls(client, companion.componentId)
