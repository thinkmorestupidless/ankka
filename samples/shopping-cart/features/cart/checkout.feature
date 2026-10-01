Feature: Checking out
  Checking out ends a cart: the checkout answers with what the cart held, and the checked-out cart
  is kept, as the record of what was ordered, refusing every change after it.

  Scenario: checking out answers with what the cart held
    Given a cart holding 2 of "Widget" and 1 of "Gadget"
    When the customer checks out
    Then the checkout holds 2 of "Widget"
    And the checkout holds 1 of "Gadget"

  Scenario: a checked-out cart keeps what it held
    Given a checked-out cart holding 2 of "Widget"
    Then the cart is checked out
    And the cart holds 2 of "Widget"

  Scenario: a checked-out cart takes no more items
    Given a checked-out cart holding 1 of "Widget"
    When the customer adds 1 of "Gadget"
    Then the addition is refused because the cart is checked out
    And the cart holds 1 item in total

  Scenario: a checked-out cart gives up no items
    Given a checked-out cart holding 1 of "Widget"
    When the customer removes "Widget"
    Then the removal is refused because the cart is checked out
    And the cart holds 1 of "Widget"

  Scenario: a cart is checked out once
    Given a checked-out cart holding 1 of "Widget"
    When the customer checks out
    Then the checkout is refused because the cart is checked out

  Scenario: an empty cart cannot be checked out
    Given an empty cart
    When the customer checks out
    Then the checkout is refused because the cart is empty
