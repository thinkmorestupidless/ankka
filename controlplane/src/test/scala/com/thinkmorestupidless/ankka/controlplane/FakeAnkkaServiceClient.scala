package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.{AnkkaServiceClient, AnkkaServiceResource}
import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, AnkkaServiceStatus}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

/**
 * An in-memory cluster.
 *
 * Satisfies the same obligations as the real client — idempotent `put`, tolerant `delete`, throwing
 * on failure, never retrying internally — and adds the controls a test needs to drive the paths a
 * real cluster will not produce on demand: an outage, a disconnected watch, and someone editing a
 * resource behind the control plane's back.
 *
 * This is what lets the projector's retry, backoff and staleness behaviour be tested offline.
 */
final class FakeAnkkaServiceClient extends AnkkaServiceClient:

  private val resources = TrieMap.empty[(String, String), AnkkaServiceResource]
  private val watchers  = TrieMap.empty[Int, AnkkaServiceResource => Unit]
  private val nextId    = new AtomicInteger(0)

  private val failures                     = new AtomicInteger(0)
  @volatile private var failure: Throwable = new RuntimeException("cluster unreachable")
  @volatile private var reachable          = true

  /** Every write the control plane performed, so a test can assert on "no write at all". */
  private val writes  = new AtomicInteger(0)
  private val deletes = new AtomicInteger(0)

  def writeCount: Int  = writes.get()
  def deleteCount: Int = deletes.get()

  private val namespaces = TrieMap.empty[String, Boolean]

  def ensureNamespace(namespace: String): Unit =
    guard()
    namespaces.put(namespace, true): Unit

  def namespaceExists(namespace: String): Boolean = namespaces.contains(namespace)

  def put(namespace: String, name: String, spec: AnkkaServiceSpec): Unit =
    guard()
    val key      = (namespace, name)
    val existing = resources.get(key)
    // C1: an unchanged spec performs no write. Asserted on by the steady-state test, which
    // is the one that would otherwise pass while the control plane hammered the API server.
    if !existing.exists(_.spec == spec) then
      writes.incrementAndGet(): Unit
      val updated = AnkkaServiceResource(
        namespace,
        name,
        spec,
        // A fresh resource has no status: nothing has reported on it yet. Preserving the
        // old status across a spec change is what a real API server does too.
        status = existing.flatMap(_.status),
        uid = existing.map(_.uid).getOrElse(s"uid-${nextId.incrementAndGet()}")
      )
      resources.put(key, updated): Unit
      notifyWatchers(updated)

  def delete(namespace: String, name: String): Unit =
    guard()
    deletes.incrementAndGet(): Unit
    // C4: deleting what is not there succeeds, because deletion is retried.
    resources.remove((namespace, name)): Unit

  def list(): Vector[AnkkaServiceResource] =
    guard()
    resources.values.toVector.sortBy(r => (r.namespace, r.name))

  def watch(onChange: AnkkaServiceResource => Unit): AutoCloseable =
    val id = nextId.incrementAndGet()
    watchers.put(id, onChange): Unit
    () => watchers.remove(id): Unit

  def connected: Boolean = reachable

  // ── Test controls ─────────────────────────────────────────────────────────

  /** The next `n` calls throw. Drives retry, backoff and `confirmed = false`. */
  def failNext(n: Int, error: Throwable = new RuntimeException("cluster unreachable")): Unit =
    failure = error
    failures.set(n)

  /** As the operator would: set a status without going through the control plane. */
  def setStatus(namespace: String, name: String, status: AnkkaServiceStatus): Unit =
    resources.get((namespace, name)).foreach { current =>
      val updated = current.copy(status = Some(status))
      resources.put((namespace, name), updated): Unit
      notifyWatchers(updated)
    }

  /** A resource nothing has reported on, which is how "no operator is running" looks. */
  def clearStatus(namespace: String, name: String): Unit =
    resources.get((namespace, name)).foreach { current =>
      val updated = current.copy(status = None)
      resources.put((namespace, name), updated): Unit
      notifyWatchers(updated)
    }

  /** Someone edited the resource with `kubectl`. The control plane must restore its record. */
  def driftEdit(namespace: String, name: String)(f: AnkkaServiceSpec => AnkkaServiceSpec): Unit =
    resources.get((namespace, name)).foreach { current =>
      resources.put((namespace, name), current.copy(spec = f(current.spec))): Unit
    }

  /** Someone deleted the resource out of band. */
  def driftDelete(namespace: String, name: String): Unit =
    resources.remove((namespace, name)): Unit

  def disconnect(): Unit = reachable = false
  def reconnect(): Unit  = reachable = true

  def current(namespace: String, name: String): Option[AnkkaServiceResource] =
    resources.get((namespace, name))

  def all: Vector[AnkkaServiceResource] = resources.values.toVector

  def resetCounters(): Unit =
    writes.set(0)
    deletes.set(0)

  private def guard(): Unit =
    if !reachable then throw failure
    if failures.get() > 0 then
      failures.decrementAndGet(): Unit
      throw failure

  private def notifyWatchers(resource: AnkkaServiceResource): Unit =
    watchers.values.foreach(_(resource))
