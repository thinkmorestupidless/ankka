Feature: Sending a request from the local console
  The local console sends a request to one of a service's routes as any other client of the service
  would, and shows the whole answer. It is not a way past an endpoint's ACL.

  Background:
    Given a service "cart" running on a developer's machine with an endpoint whose route is "POST /carts/{cartId}/items"

  Scenario: a request sent from the local console reaches the service as any client's would and its whole answer is shown
    When the developer sends a request to "POST /carts/{cartId}/items" from the local console
    Then "cart" handles the request exactly as it handles one from any other client
    And the local console shows the whole answer: its outcome, the names and values sent with it, and its body

  Scenario: an endpoint's ACL refuses a request from the local console as it refuses any other
    Given the ACL of "POST /carts/{cartId}/items" denies all
    When the developer sends a request to "POST /carts/{cartId}/items" from the local console
    Then the request is refused exactly as a request from any other client is
    And no handler runs
