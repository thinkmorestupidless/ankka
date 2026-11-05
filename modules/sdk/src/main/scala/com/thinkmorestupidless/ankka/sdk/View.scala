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
 * changed. A view that must name its rows' keys, or read several sources, is a [[KeyedView]].
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
   * A field inside a collection (`contacts[*].email`) cannot be a lookup field (feature 042): a
   * row's lookup column holds one token per path, so only one element could ever be found.
   */
  private[ankka] def refuseCollectionLookup(view: ComponentId, path: String): Unit =
    if path.contains("[*]") then
      throw IllegalArgumentException(
        s"view '$view' marks '$path' for lookup, which is a field inside a collection; a lookup " +
          "field is one value of the row, so mark a field outside any collection"
      )

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
  )(using personal: PersonalFields[Row]):

    def create(ctx: ViewComponentContext): V

    private val lookups = scala.collection.mutable.ArrayBuffer.empty[String]

    /**
     * Marks a personal field of the row for lookup (feature 042): a declared query may compare it
     * by equality, which the runtime answers from a token of the value, never the value. Any other
     * predicate on a personal field is refused when the service starts.
     */
    protected final inline def lookup(inline field: Row => Any): Unit =
      lookupAt(FieldPath.of(field))

    protected final def lookupAt(path: String): Unit = declared.synchronized {
      if taken then
        throw IllegalStateException(
          s"view '$componentId' marks '$path' for lookup after it was registered; mark it in the " +
            "companion's body"
        )
      if !personal.paths.contains(path) then
        throw IllegalArgumentException(
          s"view '$componentId' marks '$path' for lookup, which is not a personal field of its row" +
            personal.paths.mkString(" (its personal fields: ", ", ", ")")
        )
      View.refuseCollectionLookup(componentId, path)
      if !lookups.contains(path) then lookups += path
    }

    /** The table holding this view's rows, for a declared query's statement to name. */
    final def table: String = ViewDescriptor.tableFor(componentId)

    private val declared                 = scala.collection.mutable.ArrayBuffer.empty[DeclaredQuery]
    @volatile private var taken: Boolean = false

    /**
     * Declares a query this view can be asked by name, and returns the handle to ask it with. Kept
     * as a `val` of the companion; a statement is checked when the service starts (see
     * [[DeclaredQuery]]).
     */
    protected final def query(name: String)(statement: String): DeclaredQuery =
      declared.synchronized {
        if taken then
          throw IllegalStateException(
            s"view '$componentId' declares the query '$name' after it was registered; declare " +
              "every query as a val of the companion"
          )
        val query = DeclaredQuery(componentId, name, statement)
        declared += query
        query
      }

    extension (query: DeclaredQuery)
      /**
       * Declares this query as one that can be watched: `query("open")(statement).watched`. Its
       * statement must select `row_key` beside `payload`, have no limit and not aggregate; that is
       * checked when the service starts.
       */
      protected final def watched: DeclaredQuery =
        declared.synchronized {
          val watchable = query.copy(watchable = true)
          val at        = declared.indexWhere(_.name == query.name)
          if at < 0 then
            throw IllegalStateException(
              s"view '$componentId' does not declare the query '${query.name}'; watch a query this " +
                "companion declares"
            )
          declared(at) = watchable
          watchable
        }

    /**
     * How many projection instances share the work.
     *
     * Each instance owns a slice range of the source's persistence ids, so raising this raises
     * throughput. Changing it is safe: offsets are stored per slice, not per instance.
     */
    def parallelism: Int = 4

    /**
     * Raise it to have this view emptied and its source read again: a topic from its start
     * position, as far back as the broker retains; an entity from its first event or state, all of
     * it. `None` is version 1. Raise it only once every instance runs a release that knows versions
     * of views that read entities: an older instance cannot be told to stop writing.
     */
    def version: Option[Int] = None

    final def descriptor: ViewDescriptor[V, Src, Row] =
      val queries = declared.synchronized {
        taken = true
        declared.toVector
      }
      val marked = declared.synchronized(lookups.toVector)
      ViewDescriptor(
        componentId,
        source,
        rowSerializer,
        create,
        parallelism,
        version,
        queries,
        personalFields = personal.paths,
        lookupFields = marked
      )

/** The registered form of a view. */
final case class ViewDescriptor[V <: View[Src, Row], Src, Row](
    componentId: ComponentId,
    source: ChangeSource[Src],
    rowSerializer: Serializer[Row],
    create: ViewComponentContext => V,
    parallelism: Int,
    version: Option[Int] = None,
    queries: Vector[DeclaredQuery] = Vector.empty,
    override val platform: Boolean = false,
    /** The paths of the row's personal fields (feature 042): `email`, `address.line1`. */
    personalFields: Vector[String] = Vector.empty,
    /** Which of them a declared query may compare by equality, through a lookup token. */
    lookupFields: Vector[String] = Vector.empty
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.View

  override def declaredHandlers: Vector[DeclaredHandler] = Vector(ViewDescriptor.OnChange)

  /** The Postgres table holding this view's rows. */
  def tableName: String = ViewDescriptor.tableFor(componentId)

object ViewDescriptor:

  /** A view has one handler, under one name in every language: what it does with a change. */
  val OnChange: DeclaredHandler = DeclaredHandler("on-change", HandlerKind.Update)

  /**
   * Derives a table name from a component id.
   *
   * Component ids allow `.` and `-`, which are not legal in an unquoted SQL identifier, so they are
   * folded to `_`. The `ankka_view_` prefix keeps projections from colliding with a developer's own
   * tables in the same database.
   */
  def tableFor(componentId: ComponentId): String =
    "ankka_view_" + componentId.map(c => if c.isLetterOrDigit then c else '_')
