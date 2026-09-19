package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*

/**
 * A queryable projection of one source's changes.
 *
 * Views exist to answer questions an entity cannot: "which carts contain this product", "which
 * customers live in this city". An entity is addressable only by its id, so any other access path
 * has to be projected.
 *
 * Rows are keyed by the source entity's id. Querying by any other attribute is done in the query,
 * not by re-keying the row — re-keying would silently orphan the old row when the attribute
 * changed.
 */
abstract class View[Src, Row]:

  private var row: Option[Row]                  = None
  private var contextOpt: Option[ChangeContext] = None

  final type Effect = ViewEffect[Row]

  protected final val effects: ViewEffects[Row] = new ViewEffects[Row]()

  /** The row as it currently stands, or `None` if this subject has no row yet. */
  protected final def rowState: Option[Row] = row

  protected final def updateContext: ChangeContext =
    contextOpt.getOrElse(
      throw IllegalStateException("updateContext is only available while handling a change")
    )

  /** Turns one source change into a row update, deletion, or nothing. */
  def onChange(change: Src): Effect

  /**
   * What to do when the source entity is deleted.
   *
   * Removing the row is the default. Override to keep a tombstone instead — for instance when a
   * checked-out cart should still show up in an order history.
   */
  def onDelete: Effect = effects.deleteRow()

  private[ankka] def _setRow(value: Option[Row]): Unit             = row = value
  private[ankka] def _setContext(ctx: Option[ChangeContext]): Unit = contextOpt = ctx

object View:

  /**
   * Declares a view to the runtime.
   *
   * {{{
   * object CartRows
   *     extends View.Companion[CartRows, ShoppingCartEvent, CartRow](
   *       ComponentId("cart-rows"),
   *       ChangeSource.eventsOf(ShoppingCartEntity),
   *       Codecs.serializer[CartRow]("cart-row")
   *     ):
   *   def create(ctx: ViewComponentContext) = new CartRows
   * }}}
   */
  abstract class Companion[V <: View[Src, Row], Src, Row](
      val componentId: ComponentId,
      val source: ChangeSource[Src],
      val rowSerializer: Serializer[Row]
  ):

    def create(ctx: ViewComponentContext): V

    /**
     * How many projection instances share the work.
     *
     * Each instance owns a slice range of the source's persistence ids, so raising this raises
     * throughput. Changing it is safe: offsets are stored per slice, not per instance.
     */
    def parallelism: Int = 4

    final def descriptor: ViewDescriptor[V, Src, Row] =
      ViewDescriptor(componentId, source, rowSerializer, create, parallelism)

/** The registered form of a view. */
final case class ViewDescriptor[V <: View[Src, Row], Src, Row](
    componentId: ComponentId,
    source: ChangeSource[Src],
    rowSerializer: Serializer[Row],
    create: ViewComponentContext => V,
    parallelism: Int
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.View

  /** The Postgres table holding this view's rows. */
  def tableName: String = ViewDescriptor.tableFor(componentId)

object ViewDescriptor:

  /**
   * Derives a table name from a component id.
   *
   * Component ids allow `.` and `-`, which are not legal in an unquoted SQL identifier, so they are
   * folded to `_`. The `ankka_view_` prefix keeps projections from colliding with a developer's own
   * tables in the same database.
   */
  def tableFor(componentId: ComponentId): String =
    "ankka_view_" + componentId.map(c => if c.isLetterOrDigit then c else '_')
