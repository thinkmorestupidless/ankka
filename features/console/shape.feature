Feature: A deployed service's shape
  A service's page shows what the platform runs for the service: its hostname when it is
  exposed, the service itself with its generation and image, its instances with how many are
  ready, and its database. The parts are joined in the direction traffic and data flow: hostname
  to service, service to instances, service to database. The shape is in the page without
  scripts, and changes as the platform reports with them.

  Background:
    Given a service "cart" deployed in the project "checkout" with 3 instances, of which 3 are ready
    And "cart" is exposed at the hostname "cart-checkout.example.test"
    And the cluster has reported the database "cart" for "cart"
    And a member of "checkout"

  Scenario: a service's shape shows its hostname, the service with its controls, its instances and its database, joined in order
    When the member reads the page of "cart"
    Then the shape shows the hostname "cart-checkout.example.test"
    And the shape shows "cart" with its generation and its image
    And the part of the shape that is "cart" offers the operations logs, pause and restart, each the same operation the inspector offers
    And the shape shows that 3 of 3 instances are ready
    And the shape shows the database "cart"
    And the hostname is joined to "cart", and "cart" is joined to its instances and to its database

  Scenario: a service that is not exposed has no hostname in its shape
    Given "cart" is not exposed
    When the member reads the page of "cart"
    Then the shape shows no hostname
    And the shape shows "cart", its instances and its database

  Scenario: a database the cluster has not reported yet is shown as waiting
    Given the cluster has not reported a database for "cart"
    When the member reads the page of "cart"
    Then the shape shows the database as not reported yet

  Scenario: a service that is not deployed yet still has a shape
    Given a service "orders" in the project "checkout" with 2 instances, of which 0 are ready
    When the member reads the page of "orders"
    Then the shape shows that 0 of 2 instances are ready

  Scenario: a web-hosted service's shape shows each of its mounts, and no database
    Given a web-hosted service "web" deployed in the project "checkout" with "cart" mounted at "/backend/cart"
    When the member reads the page of "web"
    Then the shape shows "web" joined to its instances and to "cart" at "/backend/cart"
    And the shape shows no database

  Scenario: a mount with no service behind it is marked in the shape
    Given a web-hosted service "web" deployed in the project "checkout" with "orders" mounted at "/backend/orders"
    And no service "orders" in the project "checkout"
    When the member reads the page of "web"
    Then the shape marks the mount at "/backend/orders" as having no service

  Scenario: the shape is on the page with scripts off
    Given the member's browser runs no scripts
    When the member reads the page of "cart"
    Then the shape is shown

  Scenario: the shape changes as the platform reports
    Given the member's browser runs scripts
    And the member is reading the page of "cart"
    When the platform reports that 2 of 3 instances of "cart" are ready
    Then the shape shows that 2 of 3 instances are ready, without the page being reloaded

  Scenario Outline: a value longer than its part is clipped, and whole below
    Given "cart" has the <value> "<long>"
    When the member reads the page of "cart"
    Then the part of the shape that shows the <value> is clipped
    And the facts of "cart" show the whole <value>

    Examples:
      | value   | long                                                                 |
      | image   | registry.example.test/a-very-long-organization-name/a-longer-image:1 |
      | hostname | a-service-with-a-very-long-name-in-a-project-with-a-long-name.example.test |
