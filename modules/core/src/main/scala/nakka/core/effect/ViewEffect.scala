package nakka.core.effect

/**
 * What a view's table updater should do with the row for the incoming change.
 *
 * `DeleteRow` removes the row outright; to keep a tombstone visible to queries, update
 * the row with a deleted flag instead — the distinction the `@DeleteHandler` pattern
 * exists to express.
 */
sealed trait ViewEffect[+Row]

object ViewEffect:
  final case class UpdateRow[Row](row: Row) extends ViewEffect[Row]
  case object DeleteRow                     extends ViewEffect[Nothing]
  case object Ignore                        extends ViewEffect[Nothing]

/** The `effects` surface inside a view's table updater. */
final class ViewEffects[Row] private[nakka] ():
  def updateRow(row: Row): ViewEffect[Row] = ViewEffect.UpdateRow(row)
  def deleteRow(): ViewEffect[Row]         = ViewEffect.DeleteRow
  def ignore(): ViewEffect[Row]            = ViewEffect.Ignore
