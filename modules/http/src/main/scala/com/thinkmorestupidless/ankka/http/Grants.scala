package com.thinkmorestupidless.ankka.http

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a grant opens on this service: one route, by its method and its whole path as a template, or
 * one gRPC method, by its full name. A socket route is a `GET`.
 */
enum GrantTarget:
  case Route(method: String, template: String)
  case Method(fullName: String)

  /**
   * Whether a grant naming `granted` opens this target. A route's parameters are compared by
   * position, not by name, so `/v1/wallets/{player}` and `/v1/wallets/{id}` are the same route; a
   * gRPC method granted as `WalletService/Deposit` opens `ankka.wallet.WalletService/Deposit`, the
   * full name the server sees, and nothing in another service definition.
   */
  def openedBy(granted: GrantTarget): Boolean = (this, granted) match
    case (Route(m, t), Route(gm, gt)) =>
      m.equalsIgnoreCase(gm) && GrantTarget.shape(t) == GrantTarget.shape(gt)
    case (Method(full), Method(g)) => full == g || full.endsWith("." + g)
    case _                         => false

object GrantTarget:
  private val Parameter = "\\{[^}/]*\\}".r

  /** A template with its parameters' names removed: what two spellings of one route share. */
  def shape(template: String): String =
    Parameter.replaceAllIn(template.stripSuffix("/"), "{}")

/** One grant this service holds a record of: which caller, and what it opens. */
final case class GrantEntry(grantee: Caller, target: GrantTarget)

/**
 * The grants that name this service, as an ACL reads them (feature 040). Answered from memory on
 * the server's dispatcher, so it never blocks: a file reader re-reads in the background of an
 * access, a test sets its entries directly.
 */
trait Grants:

  /** Whether `caller` holds a grant that opens `target`. */
  def admits(caller: Caller, target: GrantTarget): Boolean

  /**
   * Calls `listener` after the grants change, so a server can end what a revoked grant admitted. A
   * handle whose grants never change never calls it.
   */
  def onChange(listener: () => Unit): Unit = ()

object Grants:

  /** No grants: `Callers.granted` admits only a local caller. */
  val none: Grants = new Grants:
    def admits(caller: Caller, target: GrantTarget): Boolean = false

  /** A fixed set, changed only by `set`: a test's, or the file reader's current one. */
  def of(entries: GrantEntry*): Mutable = Mutable(entries.toVector)

  /** Grants held in memory and replaced whole, telling every listener after each replacement. */
  final class Mutable(initial: Vector[GrantEntry]) extends Grants:
    @volatile private var held: Vector[GrantEntry] = initial
    private val listeners                          = CopyOnWriteArrayList[() => Unit]()

    def entries: Vector[GrantEntry] = held

    def set(entries: Vector[GrantEntry]): Unit =
      val changed = entries != held
      held = entries
      if changed then listeners.forEach(listener => listener())

    def admits(caller: Caller, target: GrantTarget): Boolean =
      held.exists(e => e.grantee == caller && target.openedBy(e.target))

    override def onChange(listener: () => Unit): Unit = listeners.add(listener): Unit
