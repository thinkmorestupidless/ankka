Feature: Items in a cart
  A customer collects items in a cart before checking out. A cart has one line per product, and
  the line's quantity is how many of that product the cart holds.

  Scenario: an item added to an empty cart is the cart's only line
    Given an empty cart
    When the customer adds 2 of "Widget"
    Then the cart holds 2 of "Widget"
    And the cart holds 2 items in total

  # docs:start merge
  Scenario: adding a product the cart already holds adds to that product's quantity
    Given a cart holding 2 of "Widget"
    When the customer adds 3 of "Widget"
    Then the cart holds 5 of "Widget"
    And the cart holds 5 items in total
  # docs:end merge

  Scenario Outline: a quantity must be greater than zero
    Given an empty cart
    When the customer adds <quantity> of "Widget"
    Then the addition is refused because the quantity is not positive
    And the cart is empty

    Examples:
      | quantity |
      | 0        |
      | -1       |

  Scenario: a removed product leaves the cart
    Given a cart holding 2 of "Widget" and 1 of "Gadget"
    When the customer removes "Widget"
    Then the cart holds 1 of "Gadget"
    And the cart holds 1 item in total

  Scenario: removing a product the cart does not hold is refused
    Given an empty cart
    When the customer removes "Widget"
    Then the removal is refused because the cart does not hold the product
