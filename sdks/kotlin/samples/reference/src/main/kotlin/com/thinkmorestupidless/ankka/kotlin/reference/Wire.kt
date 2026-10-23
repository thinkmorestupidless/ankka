package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.http.Caller

// The little the reference's routes write by hand: JSON text and a caller's word. (The Java
// reference's mapped stream is a Flow's map here.)

/** A string as a JSON string. */
internal fun quoted(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/** A string as a JSON string, or `null`. */
internal fun quotedOrNull(text: String?): String = if (text == null) "null" else quoted(text)

/** Strings as a JSON array, sorted. */
internal fun sortedArray(values: Collection<String>): String = values.sorted().joinToString(",", "[", "]") { quoted(it) }

/** A caller as every reference names it. */
internal fun callerWord(caller: Caller): String =
    when (caller) {
        is Caller.Service -> "service:${caller.project}/${caller.name}"
        is Caller.Machine -> "machine:${caller.organization}/${caller.name}"
        is Caller.Gateway -> "gateway"
        is Caller.Local -> "local"
    }
