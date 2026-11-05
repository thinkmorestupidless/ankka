package com.thinkmorestupidless.ankka.javasdk.erasure;

/** An erasure request as asked: its id in its project and its state (held, applying, applied). */
public record ErasureAsked(String id, String state) {}
