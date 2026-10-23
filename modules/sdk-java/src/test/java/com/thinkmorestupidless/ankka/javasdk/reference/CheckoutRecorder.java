package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;

/** Records each cart checked out on the conformance entity of the same id. */
// docs:start calls-components
@ComponentId("checkout-recorder")
public class CheckoutRecorder extends Consumer<CartEvent, Void> {

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCartEntity.class);
  }

  @Override
  protected ConsumerEffect<Void> onChange(CartEvent event) {
    if (!(event instanceof CartEvent.CheckedOut)) return effects().ignore();
    componentClient()
        .forEventSourcedEntity(messageContext().subject())
        .call(ConformanceEntity::record)
        .invoke("checkout");
    return effects().done();
  }
}
// docs:end calls-components
