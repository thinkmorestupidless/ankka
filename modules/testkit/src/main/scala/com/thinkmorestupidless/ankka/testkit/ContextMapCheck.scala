package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ContextMap}
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, DeclaredTopics}

/**
 * A project's context map held true at run time (feature 062): what one running service states in
 * its components, every topic it reads and publishes to and the contract it names for each,
 * compared with the map's topic crossings from and to it. `ankka context-map check` holds the map
 * to the project's files; this holds it to the code. A project's tests module runs it for each of
 * its Scala services, so a publication added without its crossing fails the project's checks.
 */
object ContextMapCheck:

  /** The problems between what `kit`'s service states and the map's crossings naming `service`. */
  def problems(map: ContextMap, service: String, kit: AnkkaTestKit): Vector[String] =
    problems(map, service, kit.service)

  def problems(map: ContextMap, service: String, running: AnkkaService): Vector[String] =
    problems(map, service, DeclaredTopics.of(running))

  /**
   * The same, over a service's component descriptors, with no service started: what the project
   * template's tests module runs, since the sides are read from the descriptors either way.
   */
  def problems(
      map: ContextMap,
      service: String,
      components: Seq[ComponentDescriptor]
  ): Vector[String] =
    problems(map, service, DeclaredTopics.of(components.toVector))

  /** The comparison itself, over the sides a service's components declare. */
  def problems(
      map: ContextMap,
      service: String,
      sides: Vector[DeclaredTopics.Side]
  ): Vector[String] =
    val crossings = ContextMap.topicCrossings(map, service)
    val published = sides.filter(_.publishes)
    val read      = sides.filterNot(_.publishes)

    val unrecordedPublications = published.distinctBy(_.topic).collect {
      case side if !crossings.published.exists(_.topic.contains(side.topic)) =>
        val as = side.contract.fold("")(c => s" as \"${c.name}\"")
        s"\"$service\" publishes to \"${side.topic}\"$as, and the context map records no crossing " +
          s"from \"$service\" by \"${side.topic}\""
    }
    val unrecordedReads = read.distinctBy(_.topic).collect {
      case side if !crossings.read.exists(_.topic.contains(side.topic)) =>
        s"\"$service\" reads \"${side.topic}\", and the context map records no crossing to " +
          s"\"$service\" by \"${side.topic}\""
    }
    // A side that states a contract other than the crossing's; one that states none is left to the
    // project's declarations, which the runtime holds it to when deployed.
    def differing(
        sides: Vector[DeclaredTopics.Side],
        recorded: Vector[ContextMap.Crossing],
        verb: String
    ) =
      for
        side     <- sides
        stated   <- side.contract.toVector
        crossing <- recorded.filter(_.topic.contains(side.topic))
        expected <- crossing.contract.toVector
        if stated.name != expected
      yield s"\"$service\" $verb \"${side.topic}\" as \"${stated.name}\"; the context map says \"$expected\""
    val wrongContracts =
      (differing(published, crossings.published, "publishes to") ++
        differing(read, crossings.read, "reads")).distinct
    val unstatedPublications = crossings.published.flatMap(_.topic).distinct.collect {
      case topic if !published.exists(_.topic == topic) =>
        s"the context map records a crossing from \"$service\" by \"$topic\", and no component of " +
          s"\"$service\" publishes to it"
    }
    val unstatedReads = crossings.read.flatMap(_.topic).distinct.collect {
      case topic if !read.exists(_.topic == topic) =>
        s"the context map records a crossing to \"$service\" by \"$topic\", and no component of " +
          s"\"$service\" reads it"
    }
    unrecordedPublications ++ unrecordedReads ++ wrongContracts ++ unstatedPublications ++ unstatedReads
