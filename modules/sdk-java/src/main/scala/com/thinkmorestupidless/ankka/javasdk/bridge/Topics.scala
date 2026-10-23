package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core
import com.thinkmorestupidless.ankka.javasdk.component.Contract

/** A topic's contract, between its Java form and the Scala one the runtime checks. */
object Topics:

  /** The contract named `name` over the schema `document`, or why there is none. */
  def contract(name: String, document: Array[Byte]): Contract =
    core.Contract
      .fromSchema(name, document)
      .fold(why => throw IllegalArgumentException(why), c => Contract(c.name, c.fingerprint))

  private[bridge] def scala(contract: Contract): core.Contract =
    core.Contract(contract.name, contract.fingerprint)
