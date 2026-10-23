package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.component.ViewContext
import com.thinkmorestupidless.ankka.sdk.ChangeContext

import java.util.Optional

/**
 * What a Java view reads while it handles a change: the row and the change's context, set by its
 * host before each call. Handed to the view's constructor through a thread-local, as an entity's
 * cell is.
 */
final class ViewCell private[bridge] ():
  private var current: Any                  = null
  private var change: Option[ChangeContext] = None

  private[bridge] def set(row: Option[Any], context: Option[ChangeContext]): Unit =
    current = row.orNull
    change = context

  def row(): Any = current

  def context(): ViewContext = change match
    case Some(ctx) => JavaViewContext(ctx)
    case None =>
      throw IllegalStateException("updateContext() is only available while handling a change")

object ViewCell:
  private val constructing = ThreadLocal[ViewCell]()

  def take(): ViewCell =
    val cell = constructing.get()
    if cell == null then ViewCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: ViewCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private final case class JavaViewContext(ctx: ChangeContext) extends ViewContext:
  def subject(): String      = ctx.subject
  def sequenceNumber(): Long = ctx.sequenceNumber
  def localOrigin(): Boolean = ctx.localOrigin
  def installation(): Optional[String] =
    ctx.installation.fold(Optional.empty[String]())(Optional.of(_))
