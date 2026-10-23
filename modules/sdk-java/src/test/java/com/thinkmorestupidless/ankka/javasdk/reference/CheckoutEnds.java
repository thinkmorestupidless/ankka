package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;

/** Records each checkout that ended, completed or failed, and nothing else. */
// docs:start workflow-consumer
@ComponentId("checkout-ends")
public class CheckoutEnds extends Consumer<Checkout.CheckoutState, Void> {

  @Override
  protected ChangeSource<Checkout.CheckoutState> source() {
    return ChangeSource.ofWorkflow(Checkout.class);
  }

  @Override
  protected ConsumerEffect<Void> onChange(Checkout.CheckoutState state) {
    var standing = messageContext().standing();
    // Running or paused: not an end, and nothing to do.
    if (standing.isEmpty() || !standing.get().isTerminal()) return effects().ignore();
    var lifecycle = standing.get();
    var end = lifecycle.failure().map(why -> lifecycle.status() + ": " + why);
    componentClient()
        .forKeyValueEntity("end-" + messageContext().subject())
        .call(Profile::set)
        .invoke(end.orElse(lifecycle.status()));
    return effects().done();
  }
}
// docs:end workflow-consumer
