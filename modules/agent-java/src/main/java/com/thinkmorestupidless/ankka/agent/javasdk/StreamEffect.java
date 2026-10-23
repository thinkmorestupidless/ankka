package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * What a {@code @Stream} handler of an agent returns: an interaction whose reply is streamed, built
 * by {@code effects()...thenStream()}. A distinct type from {@link AgentEffect}, so a streaming
 * handler cannot be called for one value, nor the reverse.
 */
public interface StreamEffect {}
