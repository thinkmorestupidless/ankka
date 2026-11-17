Feature: What a project runs beside its services on a developer's machine
  A project declares each companion it has: what must run beside its services on a developer's
  machine that is not a service of the project, such as an identity provider. Bringing the project up
  starts each companion before the services that need it, and bringing it down stops them.

  Background:
    Given a project "shop" with the services "orders" and "billing"
    And "shop" declares a companion "identity" that "orders" needs

  Scenario: what a project declares beside its services is started before the services
    When the developer brings "shop" up
    Then the companion "identity" is running
    And "identity" was ready before "orders" started

  Scenario: a service is started only when what it needs beside it is ready
    Given the companion "identity" does not become ready
    When the developer brings "shop" up
    Then the command line fails, naming "identity"
    And "orders" is not started

  Scenario: something declared beside the services is given a database in the project's database server when it asks for one
    Given the companion "identity" asks for a database
    When the developer brings "shop" up
    Then the database server of "shop" holds a database named "identity"
    And "identity", "orders" and "billing" use different databases in one database server

  Scenario: bringing a project down stops what was started beside its services
    Given "shop" is up
    When the developer brings "shop" down
    Then the companion "identity" is not running
