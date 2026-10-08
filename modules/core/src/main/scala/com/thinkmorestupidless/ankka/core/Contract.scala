package com.thinkmorestupidless.ankka.core

import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * What a topic carries: a name and the fingerprint of the schema document it was declared with. A
 * project declares one on a topic; a component states one on each topic it reads or publishes to;
 * the runtime refuses, at start, a side whose name or fingerprint is not the declared one. The name
 * travels on the wire as every message's `ce-type`. Nothing checks a message against the schema.
 *
 * The fingerprint is `sha256:` and the hex SHA-256 of the document under the JSON Canonicalization
 * Scheme (RFC 8785): keys sorted by UTF-16 code unit, no whitespace, strings escaped the one way,
 * numbers as ECMAScript prints them. Whitespace and key order in a saved file do not change it; a
 * changed field does. Every SDK fingerprints the same way, held to `protocol/fixtures/contracts/`.
 */
final case class Contract(name: String, fingerprint: String)

object Contract:

  /** A contract's name: a topic's letters and `_`, 2 to 100 characters. */
  val NameRule: String = "[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]"

  private val NamePattern = NameRule.r

  def validName(name: String): Boolean = NamePattern.matches(name)

  /** `Left` is why: the name breaks the rule, or the document is not JSON. */
  def fromSchema(name: String, document: Array[Byte]): Either[String, Contract] =
    if !validName(name) then Left(s"contract name '$name' is not $NameRule")
    else fingerprint(document).map(Contract(name, _))

  /** `Left` is why the bytes are not JSON. */
  def fingerprint(document: Array[Byte]): Either[String, String] =
    GraphJson.parse(document).map { json =>
      val canonical = Canonical.render(json).getBytes(StandardCharsets.UTF_8)
      val digest    = MessageDigest.getInstance("SHA-256").digest(canonical)
      "sha256:" + digest.map(b => f"$b%02x").mkString
    }

  /** RFC 8785, over the parsed tree. */
  private[ankka] object Canonical:

    def render(json: GraphJson): String =
      val out = new java.lang.StringBuilder
      write(json, out)
      out.toString

    private def write(json: GraphJson, out: java.lang.StringBuilder): Unit =
      def put(text: String): Unit = { out.append(text); () }
      json match
        case GraphJson.Null        => put("null")
        case GraphJson.Bool(value) => put(if value then "true" else "false")
        case GraphJson.Num(value)  => put(number(value))
        case GraphJson.Str(value)  => string(value, out)
        case GraphJson.Arr(items) =>
          put("[")
          items.zipWithIndex.foreach { (item, i) =>
            if i > 0 then put(",")
            write(item, out)
          }
          put("]")
        case GraphJson.Obj(fields) =>
          put("{")
          // UTF-16 code unit order is String's natural order.
          fields.sortBy(_._1).zipWithIndex.foreach { case ((name, value), i) =>
            if i > 0 then put(",")
            string(name, out)
            put(":")
            write(value, out)
          }
          put("}")

    private def string(value: String, out: java.lang.StringBuilder): Unit =
      def put(text: String): Unit = { out.append(text); () }
      put("\"")
      value.foreach {
        case '"'           => put("\\\"")
        case '\\'          => put("\\\\")
        case '\b'          => put("\\b")
        case '\f'          => put("\\f")
        case '\n'          => put("\\n")
        case '\r'          => put("\\r")
        case '\t'          => put("\\t")
        case c if c < 0x20 => put(f"\\u${c.toInt}%04x")
        case c             => put(c.toString)
      }
      put("\"")

    /**
     * ECMAScript's Number::toString over the value as a double: an integer below 1e21 as its
     * digits, otherwise the shortest round-trip digits, with an exponent at or past 1e21 and below
     * 1e-6.
     */
    private[ankka] def number(value: BigDecimal): String =
      val d = value.toDouble
      if d == 0.0 then "0"
      else if d.isWhole && math.abs(d) < 1e21 then BigDecimal(d).toBigInt.toString
      else
        // Java's shortest repr is `d.dddE±n` past 1e7 or below 1e-3; ECMAScript's thresholds differ.
        val repr = java.lang.Double.toString(d)
        val (mantissa, exp) = repr.split('E') match
          case Array(m, e) => (m, e.toInt)
          case Array(m)    => (m, 0)
        val negative        = mantissa.startsWith("-")
        val digitsWithPoint = if negative then mantissa.drop(1) else mantissa
        val (intPart, frac0) = digitsWithPoint.split('.') match
          case Array(i, f) => (i, f)
          case Array(i)    => (i, "")
        val frac    = frac0.reverse.dropWhile(_ == '0').reverse
        val digits0 = (intPart + frac).dropWhile(_ == '0')
        val digits  = if digits0.isEmpty then "0" else digits0
        // The decimal point sits after `intPart.length` digits, shifted by the exponent; leading zeros
        // dropped from intPart move it too.
        val point = intPart.length + exp - (intPart + frac).takeWhile(_ == '0').length
        val k     = digits.length
        val n     = point
        val body =
          if k <= n && n <= 21 then digits + "0" * (n - k)
          else if 0 < n && n <= 21 then digits.take(n) + "." + digits.drop(n)
          else if -6 < n && n <= 0 then "0." + "0" * (-n) + digits
          else
            val e    = n - 1
            val sign = if e >= 0 then "+" else "-"
            val m    = if k == 1 then digits else digits.take(1) + "." + digits.drop(1)
            s"${m}e$sign${math.abs(e)}"
        (if negative then "-" else "") + body
