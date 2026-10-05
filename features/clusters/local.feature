Feature: Service clusters on a developer's machine
  How the instances of a service find each other is chosen by where the service runs, never by its
  code. On a developer's machine a service forms a service cluster of one with nothing set, and a
  second instance joins the first when it is told where the first is.

  Scenario: a service on a developer's machine forms a service cluster of one and answers requests
    Given a developer's machine with nothing set about how instances find each other
    When the developer starts the service "cart"
    Then "cart" is a service cluster of 1 instance
    And "cart" answers requests

  Scenario: a second instance told the address of the first joins its service cluster
    Given an instance of "cart" running on a developer's machine
    When the developer starts a second instance of "cart" told the address of the first
    Then both instances are in one service cluster

  Scenario Outline: the same build of a service forms a service cluster wherever it runs
    Given one build of the service "cart" with nothing in it about how instances find each other
    When the build is <run>
    Then the instances of "cart" form one service cluster

    Examples:
      | run                                          |
      | run on a developer's machine                 |
      | deployed in the project "shop" with 3 instances |

  Scenario: a service's own setting about its service cluster applies when it is deployed
    Given a service "cart" built with a setting of its own about how its instances find each other
    When a member applies a descriptor for "cart"
    Then the setting of "cart" applies in place of the platform's
