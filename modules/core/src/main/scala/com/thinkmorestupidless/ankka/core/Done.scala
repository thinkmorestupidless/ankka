package com.thinkmorestupidless.ankka.core

/**
 * The acknowledgement type for handlers that change state but have nothing to report.
 *
 * ankka defines its own rather than reusing `pekko.Done` so that `ankka-core` — and therefore every
 * domain and every unit test — stays free of a Pekko dependency.
 */
sealed trait Done

case object Done extends Done:
  /** Reads better than `Done` at a call site returning a value. */
  def done: Done = this
