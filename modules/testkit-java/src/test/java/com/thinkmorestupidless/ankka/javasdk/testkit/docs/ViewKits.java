package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.reference.JoinedLeft;
import com.thinkmorestupidless.ankka.javasdk.reference.JoinedRight;
import com.thinkmorestupidless.ankka.javasdk.reference.JoinedRows;
import com.thinkmorestupidless.ankka.javasdk.reference.Noted;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.KeyedViewTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.ViewTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartRows;
import java.util.Optional;

/** The view kits as the pages show them. */
public final class ViewKits {

  private ViewKits() {}

  // docs:start view-test
  /** One change to a view's handler, and the row it wrote, with no runtime. */
  public static Optional<CartRows.Row> cartRow() {
    var kit = ViewTestKit.of(CartRows.class);
    kit.change("c1", new CartEvent.ItemAdded(new LineItem("p1", "Pen", 2)));
    return kit.row("c1"); // Row("c1", 1, false)
  }
  // docs:end view-test

  // docs:start keyed-view-test
  /** The right's change notes every row holding it, found by the view's own query. */
  public static KeyedViewTestKit<JoinedRows.JoinedRow> rightNotesEveryRow() {
    var kit = KeyedViewTestKit.of(JoinedRows.class);
    kit.change(JoinedLeft.class, "a", new Noted("r1|b"));
    kit.change(JoinedLeft.class, "a", new Noted("r2|b"));
    kit.change(JoinedLeft.class, "a", new Noted("r3|c"));
    // The query is the database's to run; the test says what it answers.
    kit.answering(
        JoinedRows.OF_RIGHT,
        values ->
            kit.rows().values().stream()
                .filter(row -> row.holding().equals(values.get("holding")))
                .toList());
    kit.change(JoinedRight.class, "b", new Noted("anything"));
    // kit.row("r1") and kit.row("r2") carry the notes [left, right]; kit.row("r3") only [left]
    return kit;
  }
  // docs:end keyed-view-test

  // docs:start service-kit
  /** The whole service, with its view projected into a real database. */
  public static AnkkaTestKit cartService() {
    return AnkkaTestKit.start(
        Ankka.service("carts").register(ShoppingCart.class).register(CartRows.class));
  }
  // docs:end service-kit
}
