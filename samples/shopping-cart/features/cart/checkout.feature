Feature: Checking out
  Checking out ends a cart: the checkout answers with what the cart held, and the cart starts
  again with nothing in it.

  Scenario: checking out answers with what the cart held
    Given a cart holding 2 of "Widget" and 1 of "Gadget"
    When the customer checks out
    Then the checkout holds 2 of "Widget"
    And the checkout holds 1 of "Gadget"

  Scenario: a checked-out cart starts again with nothing in it
    Given a checked-out cart holding 1 of "Widget"
    When the customer adds 1 of "Gadget"
    Then the cart holds 1 of "Gadget"
    And the cart holds 0 of "Widget"
    And the cart holds 1 item in total

  Scenario: an empty cart cannot be checked out
    Given an empty cart
    When the customer checks out
    Then the checkout is refused because the cart is empty
