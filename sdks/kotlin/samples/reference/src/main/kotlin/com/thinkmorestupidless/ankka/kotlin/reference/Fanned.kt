package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest

/**
 * The n-th message of a change, as every topic component of the reference reads and writes. The
 * getter is `n()`, as the Java record's is: the conformance suite builds and reads one by reflection.
 */
@Manifest("fanned")
data class Fanned(@get:JvmName("n") val n: Int)
