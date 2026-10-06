package com.thinkmorestupidless.ankka.runtime

/**
 * W3C Trace Context's `traceparent`: how a trace context crosses from one service to another, on an
 * HTTP request, a gRPC call and a message published to a topic, and how one arrives from outside
 * the cluster.
 *
 * One parser and one writer for every transport, so that no two can disagree about what a valid one
 * is. What is not valid is no context at all: a request or a message carrying a malformed one
 * starts a new trace, and nothing is echoed. `tracestate` is neither read nor written, which W3C
 * allows a participant with nothing to add.
 *
 * A context is taken as given: it says which trace a request belongs to and proves nothing about
 * who sent it. Who called is the certificate's to say, and nothing is admitted or refused by a
 * trace id.
 */
object Traceparent:

  /** The header, metadata key and message metadata entry it travels under. */
  val Name: String = "traceparent"

  /**
   * The context a value names, or none. Version `00` exactly as W3C writes it; a later version by
   * its first four fields, as W3C asks of a reader that does not know it; never version `ff`.
   */
  def parse(value: String): Option[TraceContext] =
    val v = value.trim
    if v.length < 55 then None
    else if v.length > 55 && (v.startsWith("00") || v.charAt(55) != '-') then None
    else if v.charAt(2) != '-' || v.charAt(35) != '-' || v.charAt(52) != '-' then None
    else
      val version = v.substring(0, 2)
      val traceId = v.substring(3, 35)
      val spanId  = v.substring(36, 52)
      val flags   = v.substring(53, 55)
      if version == "ff" || !Vector(version, traceId, spanId, flags).forall(isLowerHex) then None
      else
        val high = java.lang.Long.parseUnsignedLong(traceId.substring(0, 16), 16)
        val low  = java.lang.Long.parseUnsignedLong(traceId.substring(16), 16)
        val span = java.lang.Long.parseUnsignedLong(spanId, 16)
        if (high == 0L && low == 0L) || span == 0L then None
        else Some(TraceContext(high, low, span))

  /** The value naming a context: version `00`, sampled, since every span recorded is exported. */
  def render(context: TraceContext): String =
    s"00-${context.traceIdHex}-${context.spanIdHex}-01"

  private def isLowerHex(s: String): Boolean =
    s.nonEmpty && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
