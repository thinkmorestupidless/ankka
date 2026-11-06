package com.thinkmorestupidless.ankka.javasdk.erasure;

import com.thinkmorestupidless.ankka.javasdk.DataSubject;

/**
 * Asking for an erasure request from code: {@code erasures().ask(subject)}. The request is asked of
 * the control plane as this service, which needs the {@code erasure} grant of the project it asks
 * in, its own included, and is named as who asked. Given to endpoints, consumers and erasure
 * handlers. A refusal is a {@code CommandException}: {@code FORBIDDEN} without the grant, {@code
 * UNAVAILABLE} where no control plane is reachable.
 */
public interface Erasures {

  /** Asks for the erasure of {@code subject} in this service's own project. */
  ErasureAsked ask(DataSubject subject);

  /** Asks for the erasure of {@code subject} as {@code options} say: another project, a hold. */
  ErasureAsked ask(DataSubject subject, ErasureAsk options);
}
