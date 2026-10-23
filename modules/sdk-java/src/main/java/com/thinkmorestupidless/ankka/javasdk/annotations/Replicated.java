package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An event sourced entity held at every installation in its replication set, written only at its
 * home and read at any of them. An entity without it writes the journal it always wrote.
 *
 * <pre>{@code
 * @ComponentId("account")
 * @Replicated
 * public class Account extends EventSourcedEntity<Balance, AccountEvent> { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Replicated {}
