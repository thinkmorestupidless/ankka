package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata

/**
 * Who acted (spec 057): the subject of the principal an endpoint authenticated, carried as
 * `ankka-attribution` in the metadata of every component call made on that person's behalf.
 *
 * Only the subject travels. A bearer never does, and neither does anything else about the
 * principal: what a component at the end of the chain learns is who it was, not what they may do. A
 * call chain keeps the first attribution, and a transport carries metadata unchanged, so a write
 * forwarded to its home arrives with the person who made it beside the installation that forwarded
 * it.
 */
object Attribution:

  val MetadataKey: String = "ankka-attribution"

  /** Runs `body` as `subject`'s work: every component call it makes carries them. */
  def within[A](subject: String)(body: => A): A = Trace.attributed(subject)(body)

  /** Who the current thread is acting for, if an endpoint said. */
  def current: Option[String] = Trace.currentAttribution

  /** Who a call was made for, from its metadata. */
  def from(metadata: Metadata): Option[String] = metadata.get(MetadataKey)
