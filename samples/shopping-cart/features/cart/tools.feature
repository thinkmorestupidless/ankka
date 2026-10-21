Feature: The cart's tools
  A customer's assistant reaches the cart through the cart's tools rather than its routes: it is
  told what each tool does, and calls them for the customer.

  Scenario: an assistant is told the cart's tools
    When the customer's assistant asks for the cart's tools
    Then the assistant is told the tools "get_cart", "add_item" and "checkout"

  Scenario: an item an assistant adds is in the cart
    Given an empty cart
    When the customer's assistant adds 2 of "Widget" with the tool "add_item"
    Then the cart holds 2 of "Widget"

  Scenario: an assistant reads the cart with a tool
    Given a cart holding 3 of "Widget"
    When the customer's assistant reads the cart with the tool "get_cart"
    Then the assistant is told the cart holds 3 of "Widget"

  Scenario: an addition the cart refuses is told to the assistant as the cart's reason
    Given an empty cart
    When the customer's assistant adds 0 of "Widget" with the tool "add_item"
    Then the assistant is told "quantity must be greater than zero"
    And the cart is empty

  Scenario: an assistant checks the cart out with a tool
    Given a cart holding 1 of "Widget"
    When the customer's assistant checks the cart out with the tool "checkout"
    Then the cart is checked out
