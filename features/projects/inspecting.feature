Feature: Checking a scenario that crosses services
  A coding agent holding the platform's skills checks a spec's scenario that crosses services
  against the services running on the developer's machine: through their real routes and their
  declared queries, never their storage. It reports the scenario met or not, with what it saw, and
  keeps each met scenario as a test that starts the services it needs.

  Background:
    Given a coding agent holding the platform's skills
    And a spec of "shop" with the scenario "an order placed is invoiced", in which "orders" publishes to the declared topic "orders-placed" and "billing" invoices what it reads
    And the context map of "shop" records the crossing from "orders" to "billing" by the declared topic "orders-placed"

  Scenario: a scenario that crosses services is reported met with the requests, what was published and the trace that joins them
    Given "orders" and "billing" running on the developer's machine
    When the coding agent checks the scenario "an order placed is invoiced"
    Then it reports the scenario met
    And the report holds the request to "orders", what was published to "orders-placed", the answer of the declared query of "billing", and the trace that joins "orders" and "billing"
    And the report names the crossing from "orders" to "billing" by "orders-placed", read from the context map of "shop"

  Scenario Outline: a scenario that crosses services is reported not met, naming the service that failed it
    Given "orders" running on the developer's machine
    And "billing" <billing>
    When the coding agent checks the scenario "an order placed is invoiced"
    Then it reports the scenario not met
    And the report names "billing" and <says>

    Examples:
      | billing                                  | says                                   |
      | not running                              | says that it did not answer            |
      | running, stating the contract "order.v2" | names the contract each service states |

  Scenario: a met scenario that crosses services is kept as a test that starts the services it needs
    Given the coding agent reported the scenario "an order placed is invoiced" met
    When the developer runs the tests of "shop" with no service running
    Then a test of the scenario "an order placed is invoiced" starts "orders" and "billing" and passes
    And with "billing" made to invoice nothing, that test fails
