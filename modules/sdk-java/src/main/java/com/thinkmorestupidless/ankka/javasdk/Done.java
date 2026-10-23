package com.thinkmorestupidless.ankka.javasdk;

/**
 * The reply of a handler that changed something and has nothing to report. Written as zero bytes
 * under the manifest {@code done}, as the Scala SDK's {@code Done} is.
 */
public final class Done {

  /** The only value. */
  public static final Done DONE = new Done();

  private Done() {}

  /** Reads well at a call site: {@code thenReply(state -> Done.done())}. */
  public static Done done() {
    return DONE;
  }

  @Override
  public String toString() {
    return "Done";
  }
}
