package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.agent.judgment.Judgments
import com.thinkmorestupidless.ankka.agent.ModelProvider
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  CallOrigin,
  EntityProtocol,
  MetaEntry,
  Observability,
  SpanOutcome,
  Trace
}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore, ServiceClients}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.slf4j.LoggerFactory

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * One instance of an autonomous agent, sharded by instance id.
 *
 * Three parts. The actor holds the subscribers to the instance's notifications, runs operations —
 * assign, suspend, resume, terminate, dequeue — one at a time, and decides when to leave memory.
 * The operations are blocking code on virtual threads, writing the task's record before the
 * instance's. The work itself is one virtual thread, the worker, which runs the iteration loop and
 * re-reads both records at every boundary: that is how it notices a suspension, a cancellation or a
 * termination, with nothing shared between it and the actor but the queue that wakes it.
 *
 * Nothing here touches the `ActorContext` from another thread; the operations and the worker report
 * back by message.
 */
private[ankka] object AutonomousAgentHost:

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * How sharding stops a host — passivation, a rebalance, a node leaving. The host finishes the
   * operation it is running, and any queued behind it, before it stops, so no caller is left
   * without an answer; anything sent after the stop began is held by sharding for the next
   * incarnation.
   */
  case object Stop extends EntityProtocol.ModuleCommand

  private sealed trait Internal                             extends EntityProtocol.ModuleCommand
  private final case class Emit(notification: Notification) extends Internal
  private final case class OpDone(
      replyTo: ActorRef[EntityProtocol.Reply],
      reply: EntityProtocol.Reply
  ) extends Internal
  private final case class WorkerIdle(idle: Boolean) extends Internal
  private case object WorkerStopped                  extends Internal
  private case object IdleTimeout                    extends Internal
  private final case class SubscriberGone(ref: ActorRef[EntityProtocol.StreamToken])
      extends Internal

  private val IdleTimerKey = "idle"

  def behavior(
      descriptor: AutonomousAgentDescriptor[AutonomousAgent],
      instanceId: String,
      shard: ActorRef[ClusterSharding.ShardCommand],
      componentClient: ComponentClient,
      defaultModel: Option[ModelProvider],
      modelTimeout: FiniteDuration,
      judgments: Judgments,
      secrets: SecretStore,
      services: ServiceClients
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        Behaviors.withStash(1024) { stash =>
          val self     = ctx.self
          val settings = descriptor.definition.settings
          val context = SimpleAutonomousAgentContext(
            descriptor.componentId,
            instanceId,
            componentClient,
            defaultModel,
            secrets,
            services
          )
          val emit: Notification => Unit = n => self ! Emit(n)
          val model                      = descriptor.definition.model.orElse(defaultModel)
          val ops                        = Operations(descriptor, instanceId, componentClient, emit)
          val worker = Worker(
            descriptor,
            descriptor.create(context),
            instanceId,
            componentClient,
            model,
            modelTimeout,
            judgments,
            emit,
            idle => self ! WorkerIdle(idle),
            () => self ! WorkerStopped,
            Observability(ctx.system)
          )

          // Announced on the first message, not here: sharding creates the actor before delivering
          // the message that started it, and when that message is a subscription, an announcement
          // made during setup is handled while nobody is subscribed, and lost. Queued behind the
          // first message, it reaches whoever that message subscribed. An instance sharding restarts
          // with no message still announces itself, since its worker reports in at once.
          var announced = false
          worker.start()

          def running(
              subscribers: Set[ActorRef[EntityProtocol.StreamToken]],
              opBusy: Boolean,
              idle: Boolean,
              stopping: Boolean,
              stopRequested: Boolean = false
          ): Behavior[EntityProtocol.Command] =

            // An instance with nothing to do and nobody watching leaves memory after a while; one
            // that is working, or watched, stays.
            def armIdle(subs: Set[?], busy: Boolean, isIdle: Boolean): Unit =
              if isIdle && subs.isEmpty && !busy then
                if !timers.isTimerActive(IdleTimerKey) then
                  timers.startSingleTimer(IdleTimerKey, IdleTimeout, settings.idlePassivationAfter)
              else timers.cancel(IdleTimerKey)

            def handle(message: EntityProtocol.Command): Behavior[EntityProtocol.Command] =
              message match
                case invoke: EntityProtocol.Invoke =>
                  if opBusy then
                    stash.stash(invoke)
                    Behaviors.same
                  else
                    runOp(ctx, ops, worker, invoke)
                    armIdle(subscribers, busy = true, idle)
                    running(subscribers, opBusy = true, idle, stopping, stopRequested)

                case OpDone(replyTo, reply) =>
                  replyTo ! reply
                  if stopRequested && stash.isEmpty then Behaviors.stopped
                  else
                    armIdle(subscribers, busy = false, idle)
                    stash.unstashAll(
                      running(subscribers, opBusy = false, idle, stopping, stopRequested)
                    )

                case Stop =>
                  if !opBusy && stash.isEmpty then Behaviors.stopped
                  else running(subscribers, opBusy, idle, stopping, stopRequested = true)

                case stream: EntityProtocol.InvokeStream =>
                  if stream.method != HostProtocol.Notifications.toString then
                    stream.tokens ! EntityProtocol.StreamFailed(
                      CommandError(
                        s"no stream '${stream.method}' on an autonomous agent",
                        ErrorCode.BadRequest
                      )
                    )
                    Behaviors.same
                  else
                    ctx.watchWith(stream.tokens, SubscriberGone(stream.tokens))
                    val subs = subscribers + stream.tokens
                    armIdle(subs, opBusy, idle)
                    running(subs, opBusy, idle, stopping)

                case SubscriberGone(ref) =>
                  val subs = subscribers - ref
                  armIdle(subs, opBusy, idle)
                  running(subs, opBusy, idle, stopping)

                case Emit(notification) =>
                  val text = String(Notification.serializer.toBytes(notification), "UTF-8")
                  subscribers.foreach(_ ! EntityProtocol.Token(text))
                  Behaviors.same

                case WorkerIdle(isIdle) =>
                  armIdle(subscribers, opBusy, isIdle)
                  running(subscribers, opBusy, isIdle, stopping)

                case IdleTimeout =>
                  if idle && subscribers.isEmpty && !opBusy && !stopping then
                    leave(ctx, shard, worker, subscribers, descriptor.componentId, instanceId)
                    running(subscribers, opBusy, idle, stopping = true)
                  else Behaviors.same

                case WorkerStopped =>
                  // The instance was terminated: tell anyone watching that there is nothing more,
                  // and leave.
                  if !stopping then
                    leave(ctx, shard, worker, subscribers, descriptor.componentId, instanceId)
                  running(Set.empty, opBusy, idle, stopping = true)

                case _ => Behaviors.same

            Behaviors
              .receiveMessage[EntityProtocol.Command] { message =>
                if !announced then
                  announced = true
                  emit(
                    Notification
                      .Activated(descriptor.componentId, instanceId, System.currentTimeMillis())
                  )
                handle(message)
              }
              .receiveSignal { case (_, PostStop) =>
                worker.stop()
                Behaviors.same
              }

          running(Set.empty, opBusy = false, idle = false, stopping = false)
        }
      }
    }

  private def leave(
      ctx: ActorContext[EntityProtocol.Command],
      shard: ActorRef[ClusterSharding.ShardCommand],
      worker: Worker,
      subscribers: Set[ActorRef[EntityProtocol.StreamToken]],
      componentId: ComponentId,
      instanceId: String
  ): Unit =
    val bye  = Notification.Deactivated(componentId, instanceId, System.currentTimeMillis())
    val text = String(Notification.serializer.toBytes(bye), "UTF-8")
    subscribers.foreach { s =>
      s ! EntityProtocol.Token(text)
      s ! EntityProtocol.StreamCompleted
    }
    worker.stop()
    shard ! ClusterSharding.Passivate(ctx.self)

  private def runOp(
      ctx: ActorContext[EntityProtocol.Command],
      ops: Operations,
      worker: Worker,
      invoke: EntityProtocol.Invoke
  ): Unit =
    val observability = Observability(ctx.system)
    val incoming      = MetaEntry.toMetadata(invoke.metadata)
    val work = Future {
      // An operation is a call to the agent like any other: a span, the origin of the records it
      // reads and writes, and one handled call from whoever asked.
      val result = observability.invocation[Either[CommandError, Array[Byte]]](
        ops.componentName,
        invoke.method,
        incoming
      )(_.fold(Observability.outcomeOf, _ => SpanOutcome.Ok))(
        ops.run(MethodName(invoke.method), invoke.payload)
      )
      worker.poke()
      result
    }(using AnkkaExecutors.virtual)
    ctx.pipeToSelf(work) {
      case Success(Right(bytes)) =>
        OpDone(invoke.replyTo, EntityProtocol.Succeeded(bytes, Vector.empty))
      case Success(Left(error)) => OpDone(invoke.replyTo, EntityProtocol.Rejected(error))
      case Failure(failure) =>
        val error = failure match
          case e: CommandError => e
          case other =>
            CommandError(Option(other.getMessage).getOrElse(other.toString), ErrorCode.Internal)
        OpDone(invoke.replyTo, EntityProtocol.Rejected(error))
    }

  // ── Operations ─────────────────────────────────────────────────────────────

  /** What a caller can ask of an instance. Blocking code, run one at a time. */
  private final class Operations(
      descriptor: AutonomousAgentDescriptor[?],
      instanceId: String,
      client: ComponentClient,
      emit: Notification => Unit
  ):
    private val componentId = descriptor.componentId
    private val me          = Assignee(componentId.toString, instanceId)
    private def now()       = System.currentTimeMillis()

    private def instance =
      client.forEventSourcedEntity(InstanceEntity.idFor(componentId, instanceId))
    private def get() = instance.call(InstanceEntity.get).invoke()
    private def record(e: InstanceEvent): InstanceRecord =
      instance.call(InstanceEntity.record).invoke(e)
    private def task(id: String) = client.forEventSourcedEntity(EntityId(id))

    val componentName: String = componentId.toString

    def run(method: MethodName, payload: Array[Byte]): Either[CommandError, Array[Byte]] =
      try
        method match
          case HostProtocol.Assign =>
            assign(HostProtocol.assignRequest.fromBytes(payload).taskIds, single = false)
              .map(HostProtocol.assignResult.toBytes)
          case HostProtocol.RunSingleTask =>
            assign(Vector(HostProtocol.runSingle.fromBytes(payload).taskId), single = true).flatMap {
              result =>
                result.refused.headOption match
                  case Some((id, r)) =>
                    Left(CommandError(s"task '$id': ${r.message}", codeOf(r.code)))
                  case None => Right(Array.emptyByteArray)
            }
          case HostProtocol.Suspend =>
            record(InstanceEvent.Suspended(now()))
            emit(Notification.Suspended(componentId, instanceId, now()))
            done
          case HostProtocol.Resume =>
            record(InstanceEvent.Resumed(now()))
            emit(Notification.Resumed(componentId, instanceId, now()))
            done
          case HostProtocol.Terminate => terminate()
          case HostProtocol.Dequeue =>
            val request = HostProtocol.dequeue.fromBytes(payload)
            val current = get()
            if current.queue.contains(request.taskId) then
              record(InstanceEvent.TaskDequeued(request.taskId, request.reason, now()))
              emit(
                Notification.TaskCancelled(
                  componentId,
                  instanceId,
                  request.taskId,
                  request.reason,
                  now()
                )
              )
            // A task being worked is noticed by the worker, which re-reads its record.
            done
          case other =>
            Left(
              CommandError(
                s"no operation '$other' on autonomous agent '$componentId'",
                ErrorCode.NotFound
              )
            )
      catch case e: CommandError => Left(e)

    private val done: Either[CommandError, Array[Byte]] = Right(Array.emptyByteArray)

    private def codeOf(name: String): ErrorCode =
      ErrorCode.values.find(_.toString == name).getOrElse(ErrorCode.Internal)

    /**
     * Assigns tasks, writing each task's record first: the record decides whether the task is this
     * instance's, and the instance queues only what its records accepted.
     */
    private def assign(
        taskIds: Vector[String],
        single: Boolean
    ): Either[CommandError, AssignResult] =
      val current = get()
      if current.terminated then
        return Left(CommandError(s"instance '$instanceId' has been terminated", ErrorCode.Conflict))
      if single && current.created then
        return Left(CommandError(s"instance '$instanceId' already exists", ErrorCode.Conflict))

      val records = taskIds.distinct.map(id =>
        id -> (try Right(task(id).call(TaskEntity.get).invoke())
        catch case e: CommandError => Left(e))
      )
      // A type this agent does not take is refused before anything is written.
      val foreign = records.collect {
        case (id, Right(r)) if descriptor.definition.accepted(r.typeName).isEmpty =>
          id -> r.typeName
      }
      if foreign.nonEmpty then
        return Left(
          CommandError(
            foreign
              .map((id, t) => s"task '$id' is a '$t' task, which '$componentId' does not accept")
              .mkString("; "),
            ErrorCode.BadRequest
          )
        )

      if !current.created then record(InstanceEvent.Created(single, now())): Unit
      val outcomes = records.map {
        case (id, Left(e)) => id -> Left(e)
        case (id, Right(_)) =>
          id -> (try Right(task(id).call(TaskEntity.assign).invoke(me))
          catch case e: CommandError => Left(e))
      }
      val accepted = outcomes.collect { case (id, Right(_)) => id }
      if accepted.nonEmpty then
        record(InstanceEvent.TasksAssigned(accepted, now()))
        accepted.foreach(id => emit(Notification.TaskAssigned(componentId, instanceId, id, now())))
      Right(
        AssignResult(
          accepted,
          outcomes.collect { case (id, Left(e)) =>
            id -> AssignResult.Refusal(e.message, e.code.toString)
          }.toMap
        )
      )

    /**
     * Terminates the instance: its record refuses everything from now on, including the worker's
     * next write, and its tasks go back to pending for another instance to take.
     */
    private def terminate(): Either[CommandError, Array[Byte]] =
      val current = get()
      if current.terminated then done
      else
        val theirs = current.current.map(_.taskId).toVector ++ current.queue
        record(InstanceEvent.Terminated(now()))
        theirs.foreach { id =>
          try
            task(id)
              .call(TaskEntity.unassign)
              .invoke(TaskEntity.Unassign("assignee terminated")): Unit
          catch case _: CommandError => () // ended meanwhile: nothing to hand back
        }
        emit(Notification.Terminated(componentId, instanceId, now()))
        done

  // ── The worker ─────────────────────────────────────────────────────────────

  /**
   * The instance's work, on a virtual thread of its own: select a task, run its iterations, end it,
   * select the next. Every round starts by reading the instance's record and the task's, so a
   * change made by an operation — or by a caller cancelling a task — is seen at the next boundary.
   */
  private final class Worker(
      descriptor: AutonomousAgentDescriptor[AutonomousAgent],
      agent: AutonomousAgent,
      instanceId: String,
      client: ComponentClient,
      model: Option[ModelProvider],
      modelTimeout: FiniteDuration,
      judgments: Judgments,
      emit: Notification => Unit,
      reportIdle: Boolean => Unit,
      reportStopped: () => Unit,
      observability: Observability
  ):
    private val definition  = descriptor.definition
    private val settings    = definition.settings
    private val componentId = descriptor.componentId
    private val me          = Assignee(componentId.toString, instanceId)
    private val wake        = LinkedBlockingQueue[Unit]()
    // Absent when neither the definition nor the runtime names a model: every task then fails, saying so.
    private val loopOrNone =
      model.map(m =>
        IterationLoop(definition, agent, instanceId, client, m, modelTimeout, judgments, emit)
      )
    private def loop: IterationLoop = loopOrNone.get

    /** A judged guardrail that nobody can answer: no provider of its own, and none configured. */
    private val unanswerable: Option[String] =
      AgentRuntime.unanswerableGuardrail(definition.guardrails, judgments)

    @volatile private var running         = true
    @volatile private var thread: Thread  = null
    private var lastIdle: Option[Boolean] = None
    private var endedATask                = false
    private val waiting                   = scala.collection.mutable.Map.empty[String, Long]
    private val stuckWarned               = scala.collection.mutable.Set.empty[String]

    private def now() = System.currentTimeMillis()

    def start(): Unit =
      thread =
        Thread.ofVirtual().name(s"autonomous-$componentId-$instanceId").start(() => runLoop())

    /** Wakes the worker to look again, now. */
    def poke(): Unit = wake.offer(()): Unit

    def stop(): Unit =
      running = false
      Option(thread).foreach(_.interrupt())

    private def pause(d: FiniteDuration): Unit =
      wake.poll(d.toMillis, TimeUnit.MILLISECONDS): Unit

    private def idle(value: Boolean): Unit =
      if !lastIdle.contains(value) then
        lastIdle = Some(value)
        reportIdle(value)

    private def instance =
      client.forEventSourcedEntity(InstanceEntity.idFor(componentId, instanceId))
    private def get() = instance.call(InstanceEntity.get).invoke()
    private def record(e: InstanceEvent): InstanceRecord =
      instance.call(InstanceEntity.record).invoke(e)
    private def task(id: String)                   = client.forEventSourcedEntity(EntityId(id))
    private def taskRecord(id: String): TaskRecord = task(id).call(TaskEntity.get).invoke()

    // Everything the worker does, it does as this agent: reading its own record between
    // iterations as much as the iterations themselves. So the thread is the agent's for as long as
    // it runs, and a call it makes is never from nobody.
    private val origin       = CallOrigin(componentId.toString, AutonomousAgentDescriptor.Iteration)
    private val componentRef = observability.names.intern(origin.component)
    private val handlerRef   = observability.names.intern(origin.handler)

    /** One piece of work on a task, as a span of its own: a start, or an iteration. */
    private def iteration[A](body: => A): A =
      val span    = observability.recorder.begin(Trace.mint(), 0L, componentRef, handlerRef)
      var outcome = SpanOutcome.Failed
      try
        val result = Trace.within(span.traceId, span.id, origin)(body)
        outcome = SpanOutcome.Ok
        result
      finally observability.recorder.complete(span, outcome)

    private def runLoop(): Unit = Trace.asOrigin(origin)(runRounds())

    private def runRounds(): Unit =
      while running do
        try round()
        catch
          case _: InterruptedException                                         => running = false
          case e: CommandError if e.code == ErrorCode.Conflict && terminated() =>
            // A termination landed while this round was writing: the instance is done.
            finish()
          case NonFatal(e) =>
            log.warn(s"autonomous agent '$componentId' instance '$instanceId': ${e.getMessage}", e)
            try pause(1.second)
            catch case _: InterruptedException => running = false

    private def terminated(): Boolean =
      try get().terminated
      catch case NonFatal(_) => false

    private def finish(): Unit =
      running = false
      reportStopped()

    private def round(): Unit =
      val rec = get()
      if rec.terminated then finish()
      else if !rec.created || rec.suspended then
        idle(true)
        pause(1.minute)
      else
        rec.current match
          case Some(w) =>
            idle(false)
            // Everything done for a task — guardrails at its start, tools and rules in its
            // iterations — runs knowing which task it is for.
            AutonomousAgent.CurrentTask.within(w.taskId)(iteration(work(w)))
          case None =>
            nextRunnable(rec) match
              case Some(id) =>
                idle(false)
                record(InstanceEvent.TaskSelected(id, now())): Unit
              case None if rec.queue.nonEmpty =>
                // Everything queued waits on a dependency: look again shortly.
                idle(false)
                pause(settings.dependencyPoll)
              case None if rec.terminateWhenDone && endedATask =>
                record(InstanceEvent.Terminated(now()))
                emit(Notification.Terminated(componentId, instanceId, now()))
                finish()
              case None =>
                idle(true)
                pause(1.minute)

    /**
     * The first queued task that can start: its dependencies have all completed. A task that has
     * ended meanwhile, or is no longer this instance's, leaves the queue.
     */
    private def nextRunnable(rec: InstanceRecord): Option[String] =
      rec.queue.iterator
        .map(id => id -> taskRecord(id))
        .flatMap { (id, t) =>
          if t.status.terminal || !t.assignee.contains(me) then
            record(InstanceEvent.TaskDequeued(id, t.reason.getOrElse(t.status.wire), now()))
            if t.status == TaskStatus.Cancelled then
              emit(
                Notification.TaskCancelled(
                  componentId,
                  instanceId,
                  id,
                  t.reason.getOrElse("cancelled"),
                  now()
                )
              )
            None
          else
            val incomplete = t.dependencies.find(d => taskRecord(d).status != TaskStatus.Completed)
            incomplete match
              case None =>
                waiting.remove(id).foreach { _ =>
                  t.dependencies.foreach(d =>
                    emit(Notification.DependencyResolved(componentId, instanceId, id, d, now()))
                  )
                }
                Some(id)
              case Some(dep) =>
                val since = waiting.getOrElseUpdate(
                  id, {
                    emit(Notification.TaskDependencyWait(componentId, instanceId, id, dep, now()))
                    now()
                  }
                )
                val waited = now() - since
                if waited >= settings.dependencyStuckAfter.toMillis && stuckWarned.add(id) then
                  emit(
                    Notification
                      .TaskDependencyStuck(componentId, instanceId, id, dep, waited, now())
                  )
                None
        }
        .nextOption()

    private def work(w: Working): Unit =
      val t = taskRecord(w.taskId)
      if t.status.terminal || !t.assignee.contains(me) then
        // Ended some other way — cancelled by a caller or a cascade, or handed back.
        end(w.taskId, outcomeOf(t))
      else
        definition.accepted(t.typeName) match
          case None => fail(t, s"'$componentId' does not accept '${t.typeName}' tasks")
          case Some(_) if loopOrNone.isEmpty =>
            fail(
              t,
              s"'$componentId' has no model: set one with model(...) on its definition, or " +
                "configure a default provider on the AgentRuntime"
            )
          case Some(_) if unanswerable.nonEmpty =>
            fail(t, AgentRuntime.noJudgmentProvider(componentId, unanswerable.get))
          case Some(acceptance) =>
            if !w.started then start(t)
            else if t.status == TaskStatus.ResultRejected then
              task(t.id).call(TaskEntity.start).invoke(): Unit
            if running then iterate(t, acceptance)

    private def start(t: TaskRecord): Unit =
      loop.startCheck(t) match
        case IterationLoop.StartCheck.Refused(reason)      => fail(t, reason)
        case IterationLoop.StartCheck.CouldNotCheck(error) =>
          // Not started: the next round checks again, after the pause a failed iteration gets,
          // and too many in a row fail the task.
          loop.failed(t.id, 0, error): Unit
          faulted(t, get(), error)
        case IterationLoop.StartCheck.Allowed =>
          task(t.id).call(TaskEntity.start).invoke(): Unit
          record(InstanceEvent.TaskStarted(t.id, now()))
          emit(Notification.TaskStarted(componentId, instanceId, t.id, now()))

    private def iterate(t0: TaskRecord, acceptance: TaskAcceptance): Unit =
      val rec = get()
      rec.current.filter(_.taskId == t0.id).filter(_.started) match
        case None => () // failed at start, or ended: the next round sees it
        case Some(w) =>
          val t      = taskRecord(t0.id)
          val budget = acceptance.budget
          val deps = t.dependencies
            .map(taskRecord)
            .map(d => IterationLoop.DependencyResult(d.id, d.typeName, d.result.getOrElse("")))
          val point = loop.resumePoint(w)
          point match
            // Warned as the iteration the model is told about starts, and once per condition.
            case IterationLoop.ResumePoint.NextIteration(n) if n <= budget =>
              warnIfNearBudget(get(), t.id, n, budget)
            case _ => ()
          val result    = loop.run(point, t, acceptance.taskType, budget, deps)
          val after     = get()
          val iteration = after.current.map(_.iteration).getOrElse(w.iteration)
          val usage     = after.taskUsage
          result match
            case IterationLoop.IterationResult.Continue       => ()
            case IterationLoop.IterationResult.Faulted(error) => faulted(t, after, error)
            case IterationLoop.IterationResult.Completed(result) =>
              writeEnd(t.id, TaskOutcome.Completed)(
                task(t.id)
                  .call(TaskEntity.complete)
                  .invoke(TaskEntity.Complete(result, iteration, usage))
              )
            case IterationLoop.IterationResult.Rejected(reason) =>
              try
                task(t.id)
                  .call(TaskEntity.rejectResult)
                  .invoke(TaskEntity.RejectResult(reason, iteration, usage))
                emit(
                  Notification.TaskResultRejected(
                    componentId,
                    instanceId,
                    t.id,
                    reason,
                    iteration,
                    now()
                  )
                )
                note(after, Struggle.ApproachingBudget, reset = true)(())
              catch
                case e: CommandError if e.code == ErrorCode.Conflict =>
                  end(t.id, outcomeOf(taskRecord(t.id)))
            case IterationLoop.IterationResult.Ended(TaskOutcome.Failed(reason)) => fail(t, reason)
            case IterationLoop.IterationResult.Ended(other)                      => end(t.id, other)

    /**
     * An iteration — or a task's start check — failed: pause and try again, or fail the task once
     * too many have failed in a row.
     */
    private def faulted(t: TaskRecord, after: InstanceRecord, error: String): Unit =
      val failures = after.current.map(_.consecutiveFailures).getOrElse(1)
      if failures >= settings.maxConsecutiveFailures then fail(t, error)
      else
        if failures >= settings.repeatedFailureAt then
          note(after, Struggle.RepeatedFailure, reset = false) {
            emit(
              Notification.RepeatedIterationFailure(
                componentId,
                instanceId,
                t.id,
                failures,
                now()
              )
            )
          }
        pause((1L << (failures - 1).min(6)).seconds.min(1.minute))

    private def warnIfNearBudget(rec: InstanceRecord, taskId: String, n: Int, budget: Int): Unit =
      if n >= math.ceil(settings.approachingBudgetAt * budget) then
        note(rec, Struggle.ApproachingBudget, reset = false) {
          emit(
            Notification.TaskApproachingMaxIterations(
              componentId,
              instanceId,
              taskId,
              n,
              budget,
              now()
            )
          )
        }

    /** Records a struggle once; `onFirst` runs only when it was not already noted. */
    private def note(rec: InstanceRecord, struggle: Struggle, reset: Boolean)(
        onFirst: => Unit
    ): Unit =
      val noted = rec.current.exists(_.warned.contains(struggle))
      if noted == reset then
        record(InstanceEvent.StruggleNoted(struggle, reset))
        if !reset then onFirst

    private def fail(t: TaskRecord, reason: String): Unit =
      val rec = get()
      writeEnd(t.id, TaskOutcome.Failed(reason))(
        task(t.id)
          .call(TaskEntity.fail)
          .invoke(TaskEntity.Fail(reason, rec.current.map(_.iteration).getOrElse(0), rec.taskUsage))
      )

    /**
     * Writes a task's end to its record, then the instance's. A task that had already ended some
     * other way refuses the write, and what its record says is what the instance records.
     */
    private def writeEnd(taskId: String, outcome: TaskOutcome)(write: => Done): Unit =
      try
        write: Unit
        end(taskId, outcome)
      catch
        case e: CommandError if e.code == ErrorCode.Conflict =>
          end(taskId, outcomeOf(taskRecord(taskId)))

    private def outcomeOf(t: TaskRecord): TaskOutcome = t.status match
      case TaskStatus.Completed => TaskOutcome.Completed
      case TaskStatus.Failed    => TaskOutcome.Failed(t.reason.getOrElse("failed"))
      case TaskStatus.Cancelled => TaskOutcome.Cancelled(t.reason.getOrElse("cancelled"))
      case _ => TaskOutcome.Cancelled(t.reason.getOrElse("no longer assigned to this instance"))

    private def end(taskId: String, outcome: TaskOutcome): Unit =
      val rec        = get()
      val iterations = rec.current.map(_.iteration).getOrElse(0)
      val usage      = rec.taskUsage
      record(InstanceEvent.TaskEnded(taskId, outcome, iterations, now()))
      endedATask = true
      emit(outcome match
        case TaskOutcome.Completed =>
          Notification.TaskCompleted(componentId, instanceId, taskId, iterations, usage, now())
        case TaskOutcome.Failed(reason) =>
          Notification.TaskFailed(componentId, instanceId, taskId, reason, iterations, now())
        case TaskOutcome.Cancelled(reason) =>
          Notification.TaskCancelled(componentId, instanceId, taskId, reason, now()))
