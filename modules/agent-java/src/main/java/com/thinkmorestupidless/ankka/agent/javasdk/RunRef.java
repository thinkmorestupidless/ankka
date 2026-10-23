package com.thinkmorestupidless.ankka.agent.javasdk;

/** The run a handler serves: its id, the step, and the blueprint's name and version. */
public record RunRef(String runId, String step, String blueprint, int version) {}
