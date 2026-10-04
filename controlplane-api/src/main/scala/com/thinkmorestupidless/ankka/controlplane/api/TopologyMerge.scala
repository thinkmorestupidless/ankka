package com.thinkmorestupidless.ankka.controlplane.api

/**
 * One topology from many instances' documents (data-model *Merge rules*). Pure, so the control
 * plane and anything else holding instance documents produce the same answer.
 *
 * Nodes and declared connections are the union; a node not on every instance that answered is a
 * difference, naming the instances that have it. Calls are summed per pair of handlers and their
 * percentiles are read again from the summed histograms — which is why an instance sends a
 * histogram and not its percentiles — and the histogram is then dropped. The window starts at the
 * latest start of any instance. An instance that did not answer contributes nothing and makes the
 * result partial; it is never guessed at.
 */
object TopologyMerge:

  /** `[2^(i-10), 2^(i-9))` milliseconds: a bucket's upper edge, as the runtime reads it. */
  def upperMillis(bucket: Int): Double = math.pow(2, bucket - 9)

  def merge(
      service: String,
      running: Int,
      read: Vector[(InstanceTopology, Option[InstanceTopologyDocument])]
  ): ServiceTopology =
    val answered = read.collect {
      case (instance, Some(document)) if instance.status == InstanceStatus.Ok =>
        instance.pod -> document
    }
    val pods      = answered.map(_._1)
    val documents = answered.map(_._2)

    val nodes = documents.flatMap(_.nodes).distinctBy(_.id).sortBy(n => (n.layer, n.id))
    val declared =
      documents.flatMap(_.declared).distinct.sortBy(e => (e.from, e.to, e.kind))

    val differences = nodes
      .map(node =>
        node.id -> answered.collect { case (pod, d) if d.nodes.exists(_.id == node.id) => pod }
      )
      .collect { case (id, on) if on.size < pods.size => TopologyDifference(id, on) }

    val calls = documents
      .flatMap(_.calls)
      .groupBy(c => (c.from, c.to))
      .toVector
      .sortBy((ends, _) => ends)
      .map { case ((from, to), edges) =>
        val pairs = edges
          .flatMap(_.pairs)
          .groupBy(p => (p.caller, p.callee))
          .toVector
          .sortBy((names, _) => names)
          .map((_, same) => sum(same))
        CallEdge(from, to, pairs)
      }

    val window = TopologyWindow(
      seconds = documents.map(_.window.seconds).maxOption.getOrElse(0L),
      since = documents.map(_.window.since).maxOption.getOrElse(""),
      calls = documents.map(_.window.calls).sum,
      unanswered = documents.map(_.window.unanswered).sum
    )

    ServiceTopology(
      service = service,
      running = running,
      contributing = pods.size,
      partial = read.exists(_._1.status != InstanceStatus.Ok) || pods.size < running,
      instances = read.map(_._1),
      window = window,
      nodes = nodes,
      declared = declared,
      calls = calls,
      differences = differences
    )

  /**
   * The same two handlers on several instances: counts added, percentiles from the added histogram.
   */
  private def sum(pairs: Vector[CallPair]): CallPair =
    val histogram = pairs.map(_.histogram).filter(_.nonEmpty) match
      case Vector() => Vector.empty[Long]
      case several =>
        val width = several.map(_.size).max
        Vector.tabulate(width)(i => several.map(h => if i < h.size then h(i) else 0L).sum)
    val duration =
      if histogram.nonEmpty then
        DurationMillis(percentile(histogram, 0.50), percentile(histogram, 0.99), max(histogram))
      else
        // Nothing to add from: the largest an instance reported, which is at least true of each.
        DurationMillis(
          pairs.map(_.durationMillis.p50).max,
          pairs.map(_.durationMillis.p99).max,
          pairs.map(_.durationMillis.max).max
        )
    CallPair(
      caller = pairs.head.caller,
      callee = pairs.head.callee,
      handled = HandledCounts(
        pairs.map(_.handled.ok).sum,
        pairs.map(_.handled.refused).sum,
        pairs.map(_.handled.failed).sum
      ),
      unanswered = UnansweredCounts(
        pairs.map(_.unanswered.timedOut).sum,
        pairs.map(_.unanswered.undelivered).sum
      ),
      durationMillis = duration,
      streaming = pairs.exists(_.streaming),
      histogram = Vector.empty
    )

  /** The bucket's upper edge below which `share` of the calls fell, as the runtime reads it. */
  def percentile(histogram: Vector[Long], share: Double): Double =
    val total = histogram.sum
    if total == 0 then 0.0
    else
      val wanted = math.max(1L, math.ceil(total * share).toLong)
      var seen   = 0L
      var bucket = 0
      while bucket < histogram.size - 1 && seen + histogram(bucket) < wanted do
        seen += histogram(bucket)
        bucket += 1
      upperMillis(bucket)

  def max(histogram: Vector[Long]): Double =
    histogram.lastIndexWhere(_ != 0L) match
      case -1     => 0.0
      case bucket => upperMillis(bucket)
