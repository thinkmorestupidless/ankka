package nakka.http

/**
 * Minimal JSON string encoding, for server-sent event payloads.
 *
 * Raw text cannot be put in an SSE `data:` field safely. The protocol strips one space
 * after the colon, so a token beginning with a space arrives short; and a newline inside
 * a token terminates the field, splitting one token into two events. Both are silent
 * corruptions that only show up on text a model happened to generate.
 *
 * Encoding each event as a JSON string makes the framing unambiguous and the payload
 * byte-exact. Clients parse the `data:` value as JSON.
 */
private[http] object JsonText:

  def encode(text: String): String =
    val out = StringBuilder(text.length + 2)
    out.append('"')
    text.foreach {
      case '"'  => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case '\b' => out.append("\\b")
      case '\f' => out.append("\\f")
      case c if c < 0x20 => out.append("\\u%04x".format(c.toInt))
      case c    => out.append(c)
    }
    out.append('"')
    out.toString
