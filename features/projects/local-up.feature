Feature: Bringing a project up on one machine with one command
  A developer starts every service of a project on their own machine with one command, run in the
  project's directory. The command line reads the project's context map and each service's
  descriptor, runs one database server and one broker for the project, gives each service its own
  database, and starts each service from its image. The developer brings the project down with one
  command too, and may leave a service out to run it from its source.

  Background:
    Given a project "shop" with the services "orders" and "billing", where "orders" calls a route of "billing"

  Scenario: one command starts every service of a project, each in its own database, and each finds the others
    When the developer brings "shop" up
    Then "orders" and "billing" are running on the developer's machine
    And "orders" and "billing" use different databases in one database server
    And a request to "orders" that calls "billing" is answered
    And "orders" and "billing" are ready when the command line returns
    And the command line names "orders" and "billing" and the address each answers at

  Scenario: a service that does not become ready is reported by name, with what it logged, and the command fails
    Given "billing" fails when it starts
    When the developer brings "shop" up
    Then the command line fails, naming "billing"
    And the command line shows the end of the log of "billing"
    And "orders" is still running

  Scenario: bringing a project down stops every service and everything started for it
    Given "shop" is up
    When the developer brings "shop" down
    Then "orders" and "billing" are not running
    And the database server and the broker of "shop" are not running

  Scenario: what the services recorded is kept when a project is brought down and up again
    Given "shop" is up and "orders" has recorded an order "o-1"
    When the developer brings "shop" down and up again
    Then "orders" answers with the order "o-1"

  Scenario: what the services recorded is removed when the developer asks for it
    Given "shop" is up and "orders" has recorded an order "o-1"
    When the developer brings "shop" down, asking for what was recorded to be removed, and up again
    Then "orders" has no order "o-1"

  Scenario: bringing up a project that is already up starts only what is not running
    Given "shop" is up and "billing" has stopped
    When the developer brings "shop" up
    Then "billing" is running
    And "orders" was not restarted

  Scenario: a directory that holds no project is refused, saying what a project's directory holds
    Given a directory with no context map
    When the developer brings a project up in that directory
    Then the command line refuses, saying that a project's directory holds a context map and a descriptor for each service
    And nothing is started

  Scenario: a service the developer leaves out is not started, and the command says how to run it
    When the developer brings "shop" up without "billing"
    Then "orders" is running and "billing" is not
    And the command line shows the one command that runs "billing" from its source

  Scenario: a service run from its source is reached by name by the services the command started
    Given "shop" is up without "billing"
    When the developer runs "billing" from its source
    Then a request to "orders" that calls "billing" is answered

  Scenario: a service run from its source reaches the services the command started by name
    Given "shop" is up without "orders"
    When the developer runs "orders" from its source
    Then a request to "orders" that calls "billing" is answered

  Scenario: only the services the developer names are started, with what they need
    When the developer brings "shop" up naming only "billing"
    Then "billing" is running and "orders" is not
    And the database server and the broker of "shop" are running

  Scenario: the developer is told which services of a project are running and ready
    Given "shop" is up and "billing" has stopped
    When the developer asks what of "shop" is running
    Then the command line says that "orders" is running and ready and where it answers
    And the command line says that "billing" is not running

  Scenario: the developer reads a service's log by the service's name
    Given "shop" is up
    When the developer asks for the log of "orders"
    Then the command line shows the log of "orders"
