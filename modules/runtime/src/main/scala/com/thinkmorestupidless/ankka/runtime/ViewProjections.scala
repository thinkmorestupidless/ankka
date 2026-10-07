package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * The name a view's progress through a source is recorded under, and the daemon process its
 * projections run in. Every view's names come from here and nowhere else.
 *
 * {{{
 *                 version 1                              version n ≥ 2
 *   plain view    ankka-view-<id>                        ankka-view.v<n>-<id>
 *   keyed view    ankka-keyed-view-<id>+<source>         ankka-keyed-view.v<n>-<id>+<source>
 * }}}
 *
 * A name that has read nothing begins at its source's first event or state, which is how raising a
 * version reads every source again, and the name it replaces keeps its offsets, never used again.
 * So no two views, versions or sources may ever share a name. A component id may contain `.`, `-`
 * and `_`: attached to the id, a version could be spelled by another id — `summary` at version 2
 * and `summary-v2` at version 1 — and the two would share one set of offsets, silently. As
 * `ConsumerGroups` does for a topic source's group, the version therefore sits before the id,
 * behind a `.` that no version-1 name has there; a keyed view's names have a prefix no plain view's
 * has; and a keyed view's id and its source's are joined by `+`, which no component id contains.
 *
 * Version 1, which is also "no version declared", is exactly the name a plain view's offsets have
 * always been stored under, so no existing view reads its source again on upgrade.
 */
private[ankka] object ViewProjections:

  /**
   * The daemon process a view's projections run in: the version-1 name, whatever the version. Every
   * instance of the service starts the same daemon process, which is what lets it run at all: a
   * sharded daemon process's coordinator runs on the oldest node, and only if that node has started
   * the same name. Under a name carrying the version, the instances that declared a higher one
   * started a process the oldest instance never knew, and none of its projections ran. The version
   * is in the projection's id instead, which is what its offsets are stored under.
   */
  def daemon(view: ComponentId, source: Option[ComponentId]): String = name(view, source, 1)

  def name(view: ComponentId, source: Option[ComponentId], version: Int): String =
    require(version >= 1, s"a version is a whole number of 1 or more, not $version")
    val kind      = if source.isEmpty then "ankka-view" else "ankka-keyed-view"
    val versioned = if version == 1 then s"$kind-" else s"$kind.v$version-"
    versioned + view + source.fold("")(s => s"+$s")
