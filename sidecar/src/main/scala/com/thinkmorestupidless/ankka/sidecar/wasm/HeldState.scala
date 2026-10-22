package com.thinkmorestupidless.ankka.sidecar.wasm

import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.sidecar.Discovery.Shape

/**
 * What the runtime keeps for one loaded instance of a module's stateful-kind component, in both
 * shapes: the encoded state after the last reply (absent when fresh, deleted or expired), the
 * journal sequence it reflects, and the replayed events not yet folded into it.
 *
 * It is what makes a guest disposable. A stateless guest is handed `state` on every call; a
 * stateful one on the first call after it was opened or replaced; and every reply carries the state
 * back, so a trap loses nothing — the next call, on whichever instance, starts from here. The
 * invariant is that `state` is what the journal folds to at `sequence`.
 *
 * One instance's calls arrive one at a time, except that a workflow's step may run while a command
 * is answered, so every access is synchronised.
 */
final class HeldState(
    val key: String,
    val shape: Shape,
    initial: Option[pb.Payload],
    initialSequence: Long
):

  private var current: Option[pb.Payload]          = initial
  private var seq: Long                            = initialSequence
  private var unfolded: Vector[HeldState.Replayed] = Vector.empty

  def state: Option[pb.Payload] = synchronized(current)
  def sequence: Long            = synchronized(seq)

  /** A replayed event, folded before the next call is made. */
  def replayed(event: HeldState.Replayed): Unit =
    synchronized {
      unfolded :+= event
    }

  /**
   * The replayed events not yet folded, which the caller is now folding: taken, not peeked. Each
   * with the installation that wrote it, for a replicated entity (feature 051), and who acted on
   * the command that wrote it and where that command was forwarded from (protocol 1.22).
   */
  def takeUnfolded(): Vector[HeldState.Replayed] = synchronized {
    val taken = unfolded
    unfolded = Vector.empty
    taken
  }

  /** The state after a fold, a command or a step. */
  def update(state: Option[pb.Payload], sequence: Long): Unit = synchronized {
    current = state
    seq = sequence
  }

  /**
   * Whether the guest must be handed the state: always if stateless, else if it does not hold it.
   */
  def toSend(resident: Boolean): Option[pb.Payload] =
    if shape == Shape.Stateless || !resident then state else None

object HeldState:

  /** One replayed event and what its journal record says about where and by whom it was written. */
  final case class Replayed(
      sequence: Long,
      event: pb.Payload,
      installation: Option[String] = None,
      attribution: Option[String] = None,
      forwardedFrom: Option[String] = None
  )
