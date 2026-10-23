package {{package}};

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

/** A quantity of one product in a cart. */
@Manifest("line-item")
public record LineItem(String productId, String name, int quantity) {}
