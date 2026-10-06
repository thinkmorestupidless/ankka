package com.thinkmorestupidless.ankka.core.effect

/** One row a keyed view writes, or deletes, by the key it is kept under. */
sealed trait RowChange[+Row]:
  def key: String

object RowChange:
  final case class Upsert[Row](key: String, row: Row) extends RowChange[Row]
  final case class Delete(key: String)                extends RowChange[Nothing]

/**
 * What a keyed view's handler says to do with one change: rows to write and rows to delete, each
 * named by its key, in order. Empty is "nothing".
 *
 * The platform deletes no row a handler did not name. A row is moved by deleting its old key and
 * writing its new one in the same effect: `effects.deleteRow(old) ++ effects.updateRow(new, row)`.
 * Building one writes nothing; every row of one change is written together, or none is.
 */
final case class KeyedViewEffect[+Row](changes: Vector[RowChange[Row]]):

  /** This effect's changes, then `other`'s. */
  def ++[R >: Row](other: KeyedViewEffect[R]): KeyedViewEffect[R] =
    KeyedViewEffect(changes ++ other.changes)

  def isEmpty: Boolean = changes.isEmpty

object KeyedViewEffect:
  val Nothing: KeyedViewEffect[Nothing] = KeyedViewEffect(Vector.empty)

/** The `effects` surface inside a keyed view's handlers. */
final class KeyedViewEffects[Row] private[ankka] ():

  def updateRow(key: String, row: Row): KeyedViewEffect[Row] =
    KeyedViewEffect(Vector(RowChange.Upsert(key, row)))

  def deleteRow(key: String): KeyedViewEffect[Row] = KeyedViewEffect(Vector(RowChange.Delete(key)))

  def updateRows(rows: Iterable[(String, Row)]): KeyedViewEffect[Row] =
    KeyedViewEffect(rows.iterator.map((key, row) => RowChange.Upsert(key, row)).toVector)

  def deleteRows(keys: Iterable[String]): KeyedViewEffect[Row] =
    KeyedViewEffect(keys.iterator.map(RowChange.Delete(_)).toVector)

  def ignore(): KeyedViewEffect[Row] = KeyedViewEffect.Nothing

/**
 * The one reading of a keyed view's effect, shared by the runtime's hosts and every test kit so
 * they cannot disagree about what an effect means: the final change for each key, a later change to
 * a key winning, keys in the order they first appear. `None` is a deletion.
 */
object RowChanges:
  def reduce[Row](changes: Vector[RowChange[Row]]): Vector[(String, Option[Row])] =
    val order = changes.map(_.key).distinct
    val last = changes.foldLeft(Map.empty[String, Option[Row]]) {
      case (acc, RowChange.Upsert(key, row)) => acc.updated(key, Some(row))
      case (acc, RowChange.Delete(key))      => acc.updated(key, None)
    }
    order.map(key => key -> last(key))
