Feature: Starting a project from the project template
  A developer starts a project of several services from the project template, beside the template
  for one service, which stays as it is. A project started from it is one repository: two services,
  each with its own descriptor, image, database and tests, a shared module holding the types of the
  contracts between them, a place for the context map, and the jobs that check and release each
  service. A service added inside the project is wired into all of it with nothing edited by hand.

  Scenario: a project started from the project template holds two services and a shared module, each service with its own descriptor, image and database, all named for it
    When a developer starts a project "shop" from the project template with the services "orders" and "billing"
    Then "shop" holds the services "orders" and "billing" and a shared module
    And each of "orders" and "billing" has its own descriptor, image and database, carrying its own name
    And "shop" has a place for its context map

  Scenario: the tests of every service of a project started from the project template pass unchanged
    Given a project "shop" started from the project template with the services "orders" and "billing"
    When the developer runs the tests of "shop"
    Then the tests of "orders" and the tests of "billing" pass

  Scenario: a service added inside a project is wired into its build, its checks and its releases with nothing edited by hand
    Given a project "shop" started from the project template with the services "orders" and "billing"
    When the developer adds the service "stock" inside "shop" with the command line
    Then "stock" has its own descriptor, image, database and tests, carrying its own name
    And the tests of "stock" run and pass
    And a service tag naming "stock" deploys "stock"
    And nothing in "shop" was edited by hand

  Scenario: adding a service in a directory that holds no project is refused and says why
    Given a directory that holds no project
    When the developer adds the service "stock" in that directory with the command line
    Then the command line refuses
    And it says that the directory holds no project started from the project template
    And nothing is written
