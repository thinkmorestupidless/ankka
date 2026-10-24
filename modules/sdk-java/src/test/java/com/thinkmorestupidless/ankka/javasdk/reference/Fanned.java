package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

/** The n-th message of a change, as every topic component of the reference reads and writes. */
@Manifest("fanned")
public record Fanned(int n) {}
