package com.thinkmorestupidless.ankka.javasdk.client;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * A call ready to be made. {@link #invoke} waits for the reply, parking the calling virtual
 * thread, and is the ordinary form; {@link #invokeAsync} returns at once. A rejection is a {@link
 * CommandException}.
 */
public interface Invocation<I, O> {

  O invoke(I input);

  CompletionStage<O> invokeAsync(I input);

  /** For a handler that takes no argument. */
  default O invoke() {
    return invoke(nothing());
  }

  /** For a handler that takes no argument. */
  default CompletionStage<O> invokeAsync() {
    return invokeAsync(nothing());
  }

  /** The same call, waiting at most {@code timeout} rather than the platform's ask timeout. */
  Invocation<I, O> withTimeout(Duration timeout);

  /** The same call, carrying {@code value} as the request's metadata entry {@code key}. */
  Invocation<I, O> withMetadata(String key, String value);

  @SuppressWarnings("unchecked")
  private static <I> I nothing() {
    @Nullable Object none = null;
    return (I) none;
  }
}
