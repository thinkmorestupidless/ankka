Feature: One service cannot reach another's database
  Each provisioned database belongs to one service. Another service of the same project cannot
  connect to it, nothing one service does reaches what another recorded, and no workload outside the
  project can connect to the project's databases at all.

  Background:
    Given a deployed service "cart" in the project "shop" with a provisioned database
    And a deployed service "payments" in the project "shop" with a provisioned database

  Scenario: a service cannot connect to another service's database with its own credential
    When "cart" connects to the database of "payments" with the credential "cart" was given
    Then the database of "payments" refuses the connection

  Scenario: a service's database credential is given to that service alone
    When an instance of "payments" starts
    Then the instance holds the credential for the database of "payments"
    And the instance holds no credential for the database of "cart"

  Scenario: the timers of two services of one project fire whatever the other does
    Given "cart" and "payments" have each set a timer
    When the timers of both services are due
    Then the timer of "cart" fires
    And the timer of "payments" fires

  Scenario: views of the same id in two services of one project hold their own rows
    Given "cart" and "payments" each have a view "summary"
    When each view "summary" writes a row
    Then the view "summary" of "cart" holds only the rows "cart" wrote
    And the view "summary" of "payments" holds only the rows "payments" wrote

  Scenario: a workload outside a project cannot connect to the project's databases
    Given a workload in the project "finance"
    When the workload connects to the database of "cart"
    Then the connection is refused before anything is exchanged
