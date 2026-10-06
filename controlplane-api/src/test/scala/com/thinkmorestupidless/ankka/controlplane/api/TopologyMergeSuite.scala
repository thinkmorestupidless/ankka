package com.thinkmorestupidless.ankka.controlplane.api

import munit.FunSuite

class TopologyMergeSuite extends FunSuite:

  private val at = "2026-10-01T10:00:00Z"

  private def histogram(counts: (Int, Long)*): Vector[Long] =
    counts.foldLeft(Vector.fill(32)(0L))((h, c) => h.updated(c._1, c._2))

  private def pair(ok: Long, refused: Long = 0L, timedOut: Long = 0L, buckets: Vector[Long]) =
    CallPair(
      "POST /carts/{id}/items",
      "add-item",
      HandledCounts(ok, refused, 0L),
      UnansweredCounts(timedOut, 0L),
      DurationMillis(
        TopologyMerge.percentile(buckets, 0.5),
        TopologyMerge.percentile(buckets, 0.99),
        TopologyMerge.max(buckets)
      ),
      histogram = buckets
    )

  private val cart   = TopologyNode("cart", "EventSourcedEntity", 1, platform = false, Vector.empty)
  private val byCust = TopologyNode("carts-by-customer", "View", 2, platform = false, Vector.empty)
  private val edge   = DeclaredEdge("cart", "carts-by-customer", "events")

  private def document(since: String, nodes: Vector[TopologyNode], p: CallPair) =
    InstanceTopologyDocument(
      TopologyService("cart", "0.10.0", "1", since),
      TopologyWindow(
        600L,
        since,
        p.handled.ok + p.handled.refused + p.handled.failed,
        p.unanswered.timedOut
      ),
      nodes,
      nodes.collect { case n if n.id == "carts-by-customer" => edge },
      Vector(CallEdge("endpoint:/carts", "cart", Vector(p)))
    )

  private def ok(pod: String) =
    InstanceTopology(pod, InstanceStatus.Ok, None, Some("0.10.0"), Some(at))

  test("two identical instances sum their counts, and the window is the later start") {
    val a = document("2026-10-01T09:00:00Z", Vector(cart), pair(2L, 1L, 1L, histogram(10 -> 3L)))
    val b = document("2026-10-01T09:30:00Z", Vector(cart), pair(4L, 0L, 0L, histogram(10 -> 4L)))
    val merged = TopologyMerge.merge("cart", 2, Vector(ok("p-a") -> Some(a), ok("p-b") -> Some(b)))
    assertEquals(merged.contributing, 2)
    assert(!merged.partial)
    assertEquals(merged.differences, Vector.empty)
    val p = merged.calls.head.pairs.head
    assertEquals(p.handled, HandledCounts(6L, 1L, 0L))
    assertEquals(p.unanswered, UnansweredCounts(1L, 0L))
    assertEquals(p.histogram, Vector.empty, "the histogram is dropped after merging")
    assertEquals(merged.window.since, "2026-10-01T09:30:00Z")
    assertEquals(merged.window.calls, 7L)
    assertEquals(merged.window.unanswered, 1L)
  }

  test("a node one instance has and the other does not is a difference naming the instance") {
    val a      = document(at, Vector(cart, byCust), pair(1L, buckets = histogram(10 -> 1L)))
    val b      = document(at, Vector(cart), pair(1L, buckets = histogram(10 -> 1L)))
    val merged = TopologyMerge.merge("cart", 2, Vector(ok("p-a") -> Some(a), ok("p-b") -> Some(b)))
    assert(merged.nodes.exists(_.id == "carts-by-customer"), "the union keeps the node")
    assert(merged.declared.contains(edge))
    assertEquals(merged.differences, Vector(TopologyDifference("carts-by-customer", Vector("p-a"))))
    assert(!merged.partial, "a difference is not a missing instance")
  }

  test("an instance that failed makes the result partial and contributes nothing") {
    val a      = document(at, Vector(cart), pair(3L, buckets = histogram(10 -> 3L)))
    val failed = InstanceTopology("p-b", InstanceStatus.Unreachable, Some("no answer in 2s"))
    val merged = TopologyMerge.merge("cart", 2, Vector(ok("p-a") -> Some(a), failed -> None))
    assert(merged.partial)
    assertEquals(merged.contributing, 1)
    assertEquals(merged.running, 2)
    assertEquals(
      merged.instances.map(_.status),
      Vector(InstanceStatus.Ok, InstanceStatus.Unreachable)
    )
    assertEquals(merged.calls.head.pairs.head.handled.ok, 3L)
    // A failed instance is not "one that lacks every node": differences are among those that answered.
    assertEquals(merged.differences, Vector.empty)
  }

  test("percentiles of the merge are those of the combined distribution's buckets") {
    // One instance saw fast calls, the other slow ones; together the median moves up a bucket and
    // the maximum is the slow instance's.
    val fast = pair(10L, buckets = histogram(8 -> 10L))
    val slow = pair(10L, buckets = histogram(12 -> 10L))
    val merged = TopologyMerge.merge(
      "cart",
      2,
      Vector(
        ok("p-a") -> Some(document(at, Vector(cart), fast)),
        ok("p-b") -> Some(document(at, Vector(cart), slow))
      )
    )
    val combined = histogram(8 -> 10L, 12 -> 10L)
    val p        = merged.calls.head.pairs.head
    assertEquals(p.durationMillis.p50, TopologyMerge.percentile(combined, 0.5))
    assertEquals(p.durationMillis.p99, TopologyMerge.percentile(combined, 0.99))
    assertEquals(p.durationMillis.max, TopologyMerge.upperMillis(12))
    assert(p.durationMillis.bucketed)
    assertEquals(p.durationMillis.p50, TopologyMerge.upperMillis(8))
    assertEquals(p.durationMillis.p99, TopologyMerge.upperMillis(12))
  }

  test("nothing read at all is an empty, partial topology that still names the instances") {
    val merged = TopologyMerge.merge(
      "cart",
      1,
      Vector(
        InstanceTopology("p-a", InstanceStatus.Unsupported, Some("serves no topology")) -> None
      )
    )
    assert(merged.partial)
    assertEquals(merged.contributing, 0)
    assertEquals(merged.nodes, Vector.empty)
    assertEquals(merged.instances.map(_.pod), Vector("p-a"))
  }

  test("the topology of a service shows its socket routes once, merged across its instances") {
    val notices = TopologyNode(
      "endpoint:/notices",
      "Endpoint",
      0,
      platform = false,
      Vector(
        TopologyHandler("GET /notices", "route", Some(false)),
        TopologyHandler("SOCKET /notices/stream", "route", Some(true))
      )
    )
    val a      = document(at, Vector(notices, cart), pair(1L, buckets = histogram(10 -> 1L)))
    val b      = document(at, Vector(notices, cart), pair(1L, buckets = histogram(10 -> 1L)))
    val merged = TopologyMerge.merge("cart", 2, Vector(ok("p-a") -> Some(a), ok("p-b") -> Some(b)))
    assertEquals(
      merged.nodes.filter(_.id == "endpoint:/notices").flatMap(_.handlers.map(_.name)),
      Vector("GET /notices", "SOCKET /notices/stream")
    )
  }
