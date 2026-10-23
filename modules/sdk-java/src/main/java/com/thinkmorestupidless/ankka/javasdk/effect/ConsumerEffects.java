package com.thinkmorestupidless.ankka.javasdk.effect;

import java.util.List;

/** The effects a consumer's handlers return. */
public interface ConsumerEffects<Out> {

  /** Publish one message, keyed by the change's subject. */
  ConsumerEffect<Out> publish(Out message);

  /** Publish one message under {@code key}, which orders it with the key's other messages. */
  ConsumerEffect<Out> publish(Out message, String key);

  /** Publish several messages in order, each keyed by the change's subject. */
  ConsumerEffect<Out> publishAll(List<Out> messages);

  /** Publish several messages in order, each with its own key and metadata. */
  ConsumerEffect<Out> publishMessages(List<Outgoing<Out>> messages);

  /** The change is handled and nothing is published. */
  ConsumerEffect<Out> done();

  /** The change is of no interest. */
  ConsumerEffect<Out> ignore();
}
