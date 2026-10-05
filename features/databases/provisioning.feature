Feature: A database provisioned for every deployed service
  A deployed service whose descriptor says nothing of a database is given one of its own by the
  platform. Nobody makes it, names it or writes down how to reach it, and it outlives the service's
  instances, so what the service recorded is still there when they are replaced.

  Scenario: a service whose descriptor says nothing of a database is given a provisioned database of its own
    Given a project "shop" with no services
    When a member applies a descriptor for the service "cart" in the project "shop" that says nothing of a database
    Then a database is provisioned for "cart"
    And every instance of "cart" is ready, connected to the database of "cart"
    And the report of "cart" says that its database was provisioned

  Scenario: what a service recorded outlives its instances
    Given a deployed service "cart" with a provisioned database
    And "cart" has recorded an event
    When "cart" is restarted
    Then "cart" still holds the event

  Scenario: each service of a project is given a database of its own
    Given a deployed service "cart" in the project "shop" with a provisioned database
    When a member applies a descriptor for the service "payments" in the project "shop" that says nothing of a database
    Then a database is provisioned for "payments"
    And the database of "payments" is not the database of "cart"

  Scenario: applying a descriptor again provisions nothing and keeps what the service recorded
    Given a deployed service "cart" with a provisioned database
    And "cart" has recorded an event
    When a member applies the same descriptor for "cart" again
    Then no database is provisioned
    And "cart" still holds the event
