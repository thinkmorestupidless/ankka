Feature: Discarding a cart
  A customer who does not want a cart discards it: the cart is deleted, and the same cart starts
  again with nothing in it. A checked-out cart is the record of an order and is never discarded.

  Scenario: a discarded cart starts again with nothing in it
    Given a cart holding 2 of "Widget"
    When the customer discards the cart
    Then the cart is empty
    And the cart holds 0 items in total

  Scenario: a discarded cart takes items again
    Given a cart holding 2 of "Widget"
    When the customer discards the cart
    And the customer adds 1 of "Gadget"
    Then the cart holds 1 of "Gadget"
    And the cart holds 0 of "Widget"

  Scenario: a checked-out cart cannot be discarded
    Given a checked-out cart holding 1 of "Widget"
    When the customer discards the cart
    Then the discard is refused because the cart is checked out
    And the cart is checked out
