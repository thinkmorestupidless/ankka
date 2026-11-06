package com.thinkmorestupidless.ankka.kotlin

import com.thinkmorestupidless.ankka.javasdk.DataSubject
import com.thinkmorestupidless.ankka.javasdk.Personal

// Personal fields (feature 042) in Kotlin: a data class property of type `Personal<T>` needs nothing
// else declared; these read it the Kotlin way.

/** The data subject `value` names, refused with the shared message when it breaks the rule. */
fun subject(value: String): DataSubject = DataSubject.of(value)

/** `value` as a personal field of `subject`. */
fun <A : Any> personal(subject: DataSubject, value: A): Personal<A> = Personal.of(subject, value)

/** The value, or `null` once the data subject is erased. */
val <A : Any> Personal<A>.valueOrNull: A?
    get() =
        when (this) {
            is Personal.Present -> value()
            is Personal.Erased -> null
        }
