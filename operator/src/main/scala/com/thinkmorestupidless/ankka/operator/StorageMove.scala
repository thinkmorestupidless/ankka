package com.thinkmorestupidless.ankka.operator

/**
 * A move of one service's bucket from Garage to Google Cloud Storage (feature 039), as the operator
 * drives it, one transition per reconcile pass, with its state kept in the service's status.
 */
object StorageMove:

  /** Where a move is. Written to `status.objectStorage.move.state`, and declared in the schema. */
  enum State:
    case Requested, Copying, Pausing, Verifying, Switched, Failed

  /** The names the schema's enum must hold, exactly. */
  val States: Set[String] = State.values.map(_.toString).toSet
