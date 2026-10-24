package com.thinkmorestupidless.ankka.javasdk.fields;

import java.util.Optional;

/** A state whose discount may hold nothing. */
public record Priced(String name, Optional<Integer> discount) {}
