Feature: Deploying the shopping cart sample
  The shopping cart sample is a real service: it is given a database, answers requests and keeps
  what it is told in its database, so it is what proves that applying a descriptor gives a service
  that works.

  Background:
    Given the sample "shopping-cart" as an image the cluster can run

  Scenario: a service whose descriptor says nothing of a database is given one
    When a member applies a descriptor for "shopping-cart" that says nothing about a database
    Then the platform makes a database for "shopping-cart" with what its entities need in it
    And "shopping-cart" is ready

  Scenario: an item added to a cart is read back
    Given "shopping-cart" is deployed and ready
    When the item "socks" is added to the cart "c1"
    Then reading the cart "c1" shows the item "socks"

  Scenario: a cart outlives the instance that held it
    Given "shopping-cart" is deployed and the cart "c1" holds the item "socks"
    When the instance of "shopping-cart" is replaced
    Then reading the cart "c1" shows the item "socks"

  Scenario: the events of a service are in its own database and no other
    Given "shopping-cart" is deployed and the cart "c1" holds the item "socks"
    When the database of "shopping-cart" is read
    Then it holds the event of the item "socks" being added to the cart "c1"
    And the database of no other service holds that event
