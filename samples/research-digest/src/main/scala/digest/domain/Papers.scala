package digest.domain

/** A paper as a source reports it: the identifier it is known by across sources, and its title. */
final case class Paper(identifier: String, title: String)

/**
 * What the watch keeps for one paper: the sources that found it and when it was first found. One
 * entry per paper, by its identifier, however many sources find it.
 */
final case class Entry(
    identifier: String,
    title: String,
    sources: Vector[String],
    firstFound: Long
):
  def exists: Boolean = firstFound > 0L

object Entry:
  def empty(identifier: String): Entry = Entry(identifier, "", Vector.empty, 0L)
