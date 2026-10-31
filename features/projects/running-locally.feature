Feature: Running every service of a project on one machine
  A developer runs every service of a project on their own machine, with nothing configured: each
  finds the others, and the local console lists them all.

  Background:
    Given a project "shop" with the services "orders" and "billing", where "orders" calls a route of "billing"

  Scenario: every service of a project runs on one machine with nothing configured, and each finds the others
    When the developer runs "orders" and "billing" on their machine with nothing configured
    Then a request to "orders" that calls "billing" is answered
    And the trace of that request holds the call to "billing"

  Scenario: the local console lists every service of a running project
    Given "orders" and "billing" running on the developer's machine
    When the developer starts the local console
    Then the local console lists "orders" and "billing"
