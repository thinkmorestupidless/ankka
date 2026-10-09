Feature: The customer's details
  The name and email of the customer a cart belongs to are personal: kept encrypted under the
  customer's key, so once the customer is erased every copy of them reads as erased.

  Scenario: an erased customer's details read as erased
    Given a cart whose customer is "Ada Byron" at "ada@example.com"
    When the customer is erased
    Then the cart's customer reads as erased
    And nothing the service keeps reads as "ada@example.com"
