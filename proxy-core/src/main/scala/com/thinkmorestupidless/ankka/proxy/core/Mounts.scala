package com.thinkmorestupidless.ankka.proxy.core

/**
 * A web-hosted service's mounts: which paths another service of its project answers (feature 021).
 *
 * A mount matches whole segments: `/api/cart` is the mount's own path and everything under it,
 * never `/api/cartoons`. What the mounted service receives is the rest of the path, which keeps its
 * leading `/` and is `/` when nothing follows. The descriptor's rules have already refused two
 * mounts at one path and one mount inside another, so at most one mount matches.
 */
final class Mounts(entries: Vector[(String, String)]):

  /** The mounted service and the path it receives, or nothing when no mount matches. */
  def find(path: String): Option[(String, String)] =
    entries.collectFirst {
      case (mount, service) if path == mount => (service, "/")
      case (mount, service) if path.startsWith(mount + "/") =>
        (service, path.substring(mount.length))
    }

  def isEmpty: Boolean = entries.isEmpty

object Mounts:
  val none: Mounts = Mounts(Vector.empty)
