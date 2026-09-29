package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.SessionMemoryEntity
import com.thinkmorestupidless.ankka.agent.autonomous.{
  InstanceEntity,
  TaskEntity,
  TaskEvent,
  TaskRecord
}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A transport that routes calls to real task and instance entities, one test kit per id, with no
 * runtime — so client code that calls several entities in an order can be tested for that order.
 *
 * Calls to anything else (an agent's host) are recorded and answered by `other`.
 */
final class EntityRouter(
    other: (ComponentId, EntityId, MethodName, Array[Byte]) => Array[Byte] = (_, _, _, _) =>
      Array.emptyByteArray
) extends CallTransport:

  private val tasks =
    ConcurrentHashMap[String, EventSourcedTestKit[TaskEntity, TaskRecord, TaskEvent]]()
  private val instances = ConcurrentHashMap[String, EventSourcedTestKit[?, ?, ?]]()
  private val sessions  = ConcurrentHashMap[String, EventSourcedTestKit[?, ?, ?]]()

  /** Every call, in order, as `component/entity#method`. */
  val calls: CopyOnWriteArrayList[String] = CopyOnWriteArrayList()

  def askTimeout: FiniteDuration = 5.seconds

  def client: ComponentClient = ComponentClient(this)

  def task(id: String): EventSourcedTestKit[TaskEntity, TaskRecord, TaskEvent] =
    tasks.computeIfAbsent(id, _ => EventSourcedTestKit.of(TaskEntity, id))

  def ask(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    calls.add(s"$componentId/$entityId#$method"): Unit
    val kit =
      if componentId == TaskEntity.componentId then Some(task(entityId))
      else if componentId == InstanceEntity.componentId then
        Some(
          instances.computeIfAbsent(entityId, _ => EventSourcedTestKit.of(InstanceEntity, entityId))
        )
      else if componentId == SessionMemoryEntity.componentId then
        Some(
          sessions
            .computeIfAbsent(entityId, _ => EventSourcedTestKit.of(SessionMemoryEntity, entityId))
        )
      else None
    kit match
      case Some(k) => k.callRaw(method, payload).fold(Future.failed, Future.successful)
      case None    => Future.successful(other(componentId, entityId, method, payload))

  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit =
    calls.add(s"$componentId/$entityId!tell"): Unit

  def callLog: Vector[String] = calls.asScala.toVector
