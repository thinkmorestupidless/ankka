Feature: A web-hosted service calling other services
  The process of a web-hosted service calls another service at the calling address, and the proxy sends
  the call on as the web-hosted service. The service called is told who called it, so its access rule
  can admit the web-hosted service and nothing else, and it never has to be exposed.

  Background:
    Given a web-hosted service "web" deployed in the project "shop"

  Scenario: the process calls a service in its project by name
    Given a service "cart" deployed in the project "shop"
    When the process of "web" calls "cart" at the calling address
    Then "cart" is told that the call came from the service "web" in the project "shop"
    And the process is given the answer of "cart"

  Scenario: the process calls several services, each by its own name
    Given a service "cart" deployed in the project "shop"
    And a service "orders" deployed in the project "shop"
    And a service "catalogue" deployed in the project "shop"
    When the process of "web" calls "cart", "orders" and "catalogue" at the calling address
    Then each of those services is given the call made to it
    And each is told that the call came from the service "web" in the project "shop"

  Scenario: the process calls a service in another project by project and name
    Given a service "invoices" deployed in the project "billing"
    When the process of "web" calls "invoices" in the project "billing" at the calling address
    Then "invoices" is told that the call came from the service "web" in the project "shop"

  Scenario: a service that admits only the web-hosted service is not reachable from the internet
    Given a service "cart" deployed in the project "shop" whose access rule admits only "web"
    And "cart" is not exposed
    And "web" is exposed
    When a person on the internet asks "web" for what its process reads from "cart"
    Then the person is shown what "cart" answered
    And a person on the internet who sends a request to "cart" without "web" is refused

  Scenario: a service that admits only the web-hosted service refuses every other service
    Given a service "cart" deployed in the project "shop" whose access rule admits only "web"
    When the service "orders" in the project "shop" calls "cart"
    Then "orders" is refused

  Scenario: a refusal reaches the process as the service made it
    Given a service "cart" deployed in the project "shop" that answers every call with a refusal
    When the process of "web" calls "cart" at the calling address
    Then "cart" is given the call as the process made it, with nothing added but who called
    And the process is given the refusal as "cart" made it

  Scenario: a call to a service that does not exist is answered and not sent
    Given no service "ledger" in the project "shop"
    When the process of "web" calls "ledger" at the calling address
    Then the process is told that there is no service "ledger"
    And no call is sent to any service

  Scenario: a call that names no service is answered and not sent
    When the process of "web" sends a call to the calling address that names no service
    Then the process is told that a call names a service
    And no call is sent to any service
