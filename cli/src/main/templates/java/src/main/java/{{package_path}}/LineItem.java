package {{package}};

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

// docs:start line-item
/** A quantity of one product in a cart. */
@Manifest("line-item")
public record LineItem(String productId, String name, int quantity) {}
// docs:end line-item
