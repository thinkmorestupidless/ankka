package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentId, ComponentKind}

/**
 * The consumer group a topic source reads under. Every topic subscription gets its name here and
 * nowhere else.
 *
 * {{{
 *   deployed, project p, service s     ankka.p.s.<kind>.<id>           ankka.p.s.<kind>-v<N>.<id>
 *   run locally, stating the name s    ankka.local.s.<kind>.<id>       ankka.local.s.<kind>-v<N>.<id>
 *   run locally, stating no name       ankka-<kind>-<id>               ankka-<kind>.v<N>-<id>
 * }}}
 *
 * Kafka divides a topic's partitions among the members of one group, so two services whose
 * components share a group each receive part of the topic, silently. A group is therefore named for
 * the service before the component.
 *
 * The component id comes last, and the version sits beside the kind, because a component id is the
 * one part that may contain `.`, `-` and `_`: attached to the id, a version could be spelled by
 * another id (`summary` at version 2, `v2.summary` at version 1). Project ids and service names are
 * DNS labels with no `.`, so in the dotted forms the kind is always the fourth segment and no id
 * can reach it; `local` is a reserved project id, so a named local run and a deployed service never
 * meet. The unnamed form is the name every group had before services were named, kept so that such
 * a service goes on reading where it was.
 *
 * Version 1, which is also "no version declared", adds nothing to the name.
 */
private[ankka] object ConsumerGroups:

  def name(
      identity: ServiceIdentity,
      kind: ComponentKind,
      componentId: ComponentId,
      version: Int = 1
  ): String =
    require(version >= 1, s"a version is a whole number of 1 or more, not $version")
    val word = kindWord(kind)
    (identity.project, identity.service) match
      case (project, Some(service)) =>
        val qualifiedKind = if version == 1 then word else s"$word-v$version"
        s"ankka.${project.getOrElse(ServiceIdentity.LocalProject)}.$service.$qualifiedKind.$componentId"
      case _ =>
        if version == 1 then s"ankka-$word-$componentId" else s"ankka-$word.v$version-$componentId"

  private def kindWord(kind: ComponentKind): String = kind match
    case ComponentKind.View     => "view"
    case ComponentKind.Consumer => "consumer"
    case other =>
      throw IllegalArgumentException(s"only a view or a consumer reads a topic, not a $other")

  /**
   * The longest name the rules for project ids (57), service names (63) and component ids (128)
   * permit, at the largest version: 277 characters. Kafka states no limit on a group id; a test
   * shows a broker accepts this one.
   */
  val Longest: String =
    name(
      ServiceIdentity.deployed("p" * 57, "s" * 63),
      ComponentKind.Consumer,
      ComponentId("c" * 128),
      Int.MaxValue
    )
