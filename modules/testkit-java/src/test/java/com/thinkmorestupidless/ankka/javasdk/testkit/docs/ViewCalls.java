package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartRows;
import java.util.List;
import java.util.Optional;

/** Reading a view through the view client, by source id or all at once. */
public final class ViewCalls {

  private ViewCalls() {}

  // docs:start get-and-all
  public static Optional<CartRows.Row> row(ViewClient views, String cartId) {
    return views.forView(CartRows.class).get(cartId); // one row, or empty
  }

  public static List<CartRows.Row> rows(ViewClient views) {
    return views.forView(CartRows.class).all(); // every row, up to 1000
  }
  // docs:end get-and-all
}
