package com.thinkmorestupidless.ankka.operator

/**
 * How many distinct clusters a set of per-node membership views describes.
 *
 * Every node reports the members it knows. Views that share a member are one cluster seen at
 * different instants — a rollout in progress, a join not yet gossiped everywhere. Only views that
 * share **nothing** are separate clusters, and that is the split this platform must never produce.
 * A node whose management endpoint is up but which has not joined yet reports no members at all:
 * that is a node waiting, not a cluster, and is not counted.
 *
 * Used by the cluster suites to assert "one cluster, never two"; here rather than in a test
 * directory so both suites, in different modules, read one definition.
 */
object Membership:

  def disjointClusters(views: Iterable[Set[String]]): Int =
    views
      .filter(_.nonEmpty)
      .foldLeft(Vector.empty[Set[String]]) { (clusters, view) =>
        val (overlapping, separate) = clusters.partition(_.intersect(view).nonEmpty)
        separate :+ overlapping.foldLeft(view)(_ union _)
      }
      .size
