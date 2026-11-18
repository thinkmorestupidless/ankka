package shoppingcart.javasdk;

/** A cart's receipt, as the receipt route answers it. */
public record Receipt(String cartId, String note, int items, String currency, int copies) {}
