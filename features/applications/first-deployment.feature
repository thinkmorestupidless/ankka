Feature: Deploying a service started from the template
  A service started from the template outside the platform's own source goes through everything a
  deployed service does: it is applied, provisioned a database, made ready, exposed and called at
  its hostname, with nothing edited on the way.

  Scenario: a service started from the template becomes ready with the descriptor the template gave
    Given the image of a service "orders" started from the template, on a local platform
    When the developer applies the descriptor the template gave "orders", unchanged
    Then "orders" is ready on a database the platform provided

  Scenario: a service started from the template keeps what it was asked at its hostname across a restart
    Given a service "orders" started from the template, deployed and exposed
    And a request at the hostname of "orders" has written state
    When "orders" restarts
    Then a request at the hostname of "orders" reads the state back

  Scenario: the documentation takes a reader from nothing to a service answering at its hostname
    When a reader follows the documentation from having nothing to a service answering at its hostname
    Then every step is there
    And no step refers to the platform's own source
