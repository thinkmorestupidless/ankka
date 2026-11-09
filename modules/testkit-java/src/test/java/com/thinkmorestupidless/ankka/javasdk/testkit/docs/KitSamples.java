package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ReplicationStatus;
import com.thinkmorestupidless.ankka.javasdk.reference.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.reference.CheckoutFanout;
import com.thinkmorestupidless.ankka.javasdk.reference.Fanned;
import com.thinkmorestupidless.ankka.javasdk.testkit.ConsumerResult;
import com.thinkmorestupidless.ankka.javasdk.testkit.ConsumerTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.Result;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Ledger;
import java.util.List;

/** The test kit calls the pages show, as a Java test writes them; the suites check the answers. */
public final class KitSamples {

  private KitSamples() {}

  // docs:start entity-kit
  public static Result<Done> addItem() {
    var kit = EventSourcedTestKit.of(ShoppingCart.class, "cart-1");
    var result = kit.call(ShoppingCart::addItem, new LineItem("p1", "Widget", 2));

    // result.reply() is Done, and result.events() is [ItemAdded(LineItem("p1", "Widget", 2))]
    return result;
  }
  // docs:end entity-kit

  // docs:start follower-kit
  public static Ledger.State followerState() {
    var kit = EventSourcedTestKit.follower(Ledger.class, "l1", "leipzig", "bratislava");
    kit.replay("leipzig", List.of(new Ledger.Entered("created"), new Ledger.Entered("frame")));
    return kit.call(Ledger::get).reply();
  }
  // docs:end follower-kit

  // docs:start replication-status
  public static ReplicationStatus status(ComponentClient client) {
    var status = client.forEventSourcedEntity("shared").replication(Ledger.class);
    status.home(); // Optional.of("leipzig")
    status.replicationSet(); // Set.of("leipzig", "vienna")
    status.position(); // how many records this copy holds
    return status;
  }
  // docs:end replication-status

  // docs:start consumer-test
  public static ConsumerResult<Fanned> fanout() {
    var kit = ConsumerTestKit.of(CheckoutFanout.class);
    var result = kit.onMessage(new CartEvent.CheckedOut(), "c1", 4);

    // result.payloads() is [Fanned(1), Fanned(2), Fanned(3)];
    // result.recordKeys() is [c1, second:c1, c1]: the key a message named, else its subject;
    // result.messages().get(2).metadata().get("x-n") is "3".
    return result;
  }
  // docs:end consumer-test
}
