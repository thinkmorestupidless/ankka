package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** The sessions and tasks started with one data subject (id: the subject). */
final case class SubjectIndex(sessions: Vector[String], tasks: Vector[String])

enum SubjectIndexEvent:
  case SessionTagged(sessionId: String)
  case TaskTagged(taskId: String)

/**
 * What an erasure of a data subject must reach in a service's agents (feature 042): every session
 * and every task started with them, recorded as each is tagged, so the erasure finds them by the
 * subject alone. It holds ids and the pseudonymous subject, nothing personal.
 */
final class SubjectIndexEntity extends EventSourcedEntity[SubjectIndex, SubjectIndexEvent]:
  def emptyState: SubjectIndex = SubjectIndex(Vector.empty, Vector.empty)

  def applyEvent(event: SubjectIndexEvent): SubjectIndex = event match
    case SubjectIndexEvent.SessionTagged(id) =>
      currentState.copy(sessions = currentState.sessions :+ id)
    case SubjectIndexEvent.TaskTagged(id) => currentState.copy(tasks = currentState.tasks :+ id)

  def addSession(sessionId: String): Effect[Done] =
    if currentState.sessions.contains(sessionId) then effects.reply(Done)
    else effects.persist(SubjectIndexEvent.SessionTagged(sessionId)).thenReply(_ => Done)

  def addTask(taskId: String): Effect[Done] =
    if currentState.tasks.contains(taskId) then effects.reply(Done)
    else effects.persist(SubjectIndexEvent.TaskTagged(taskId)).thenReply(_ => Done)

  def get: ReadOnlyEffect[SubjectIndex] = effects.reply(currentState)

object SubjectIndexEntity
    extends EventSourcedEntity.Companion[SubjectIndexEntity, SubjectIndex, SubjectIndexEvent](
      componentId = ComponentId("ankka-subject-index"),
      stateSerializer = Codecs.serializer[SubjectIndex]("ankka-subject-index"),
      eventSerializer = Codecs.serializer[SubjectIndexEvent]("ankka-subject-index-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  given Serializer[SubjectIndex]                 = stateSerializer
  def create(context: EventSourcedEntityContext) = new SubjectIndexEntity
  val addSession                                 = command("add-session")(_.addSession)
  val addTask                                    = command("add-task")(_.addTask)
  val get                                        = query("get")(_.get)
