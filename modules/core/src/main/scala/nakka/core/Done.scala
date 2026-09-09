package nakka.core

/**
 * The acknowledgement type for handlers that change state but have nothing to report.
 *
 * nakka defines its own rather than reusing `pekko.Done` so that `nakka-core` — and therefore every
 * domain and every unit test — stays free of a Pekko dependency.
 */
sealed trait Done

case object Done extends Done:
  /** Reads better than `Done` at a call site returning a value. */
  def done: Done = this
