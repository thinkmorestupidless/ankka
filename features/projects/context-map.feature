Feature: Holding the context map true
  A project's context map is one file in its repository. The project's checks compare it with every
  service's descriptor, so the services and the declared topics and contracts that cross between
  them cannot disagree with the code, and a rendering for people to read is kept in step with it.

  Background:
    Given a project "shop" with the services "orders" and "billing"
    And the descriptor of "orders" publishes to the declared topic "orders-placed" carrying the contract "order.v1", which the descriptor of "billing" reads

  Scenario: a context map that names a service the project does not hold, or does not name one it holds, fails the project's checks
    Given the context map of "shop" names the services "orders", "billing" and "shipping"
    When the developer runs the checks of "shop"
    Then the checks fail
    And they say that "shop" has no service "shipping"
    And with "billing" taken out of the context map instead, they say that the context map does not name "billing"

  Scenario: a crossing whose declared topic or contract no service of the project declares fails the project's checks
    Given the context map of "shop" records a crossing from "orders" to "billing" by the declared topic "orders-placed" carrying the contract "order.v2"
    When the developer runs the checks of "shop"
    Then the checks fail
    And they say that no service of "shop" declares "orders-placed" carrying "order.v2"

  Scenario: a declared topic a service reads or publishes that the context map records no crossing for fails the project's checks
    Given the context map of "shop" records no crossing by "orders-placed"
    When the developer runs the checks of "shop"
    Then the checks fail
    And they say that "orders" publishes to "orders-placed" and "billing" reads it, and the context map records no crossing for it

  Scenario: a context map that agrees with every descriptor passes the project's checks and is rendered for people to read
    Given the context map of "shop" records the crossing from "orders" to "billing" by the declared topic "orders-placed" carrying the contract "order.v1"
    When the developer runs the checks of "shop"
    Then the checks pass
    And the context map is rendered for people to read, naming "orders", "billing", what each owns, why their boundary is there and the crossing
    And with the rendering out of step with the context map, the checks fail
