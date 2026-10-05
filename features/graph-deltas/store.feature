Feature: A store filled from a service
  A service that publishes its entities as deltas fills a store through a pipeline whose only part
  is the sink. No second program turns the service's messages into deltas, and the store can be
  built again from the topic alone.

  Background:
    Given the shopping cart sample with a consumer that publishes its carts and checkouts as deltas
    And a pipeline whose only part is the sink, reading the topic the consumer publishes to

  Scenario: carts created, changed and checked out appear in the store
    When carts are created, changed and checked out
    Then the store holds a node for each cart and for each checkout, and a relationship between each cart and its checkout
    And each element is at the version of the last change its entity made to it

  Scenario: a deleted cart's elements are marked deleted in the store
    Given a cart that is in the store
    When the cart is deleted and its tombstones are applied
    Then its elements are marked deleted in the store

  Scenario: a topic the pipeline declares as its own is compacted and holds only element keys
    Given the topic is declared as the pipeline's own
    When the pipeline is deployed before the service publishes
    Then the topic is compacted
    And every message on the topic is under its element key

  Scenario: a consumer restarted while carts change leaves the store as an uninterrupted run would
    Given carts are changing
    When the consumer is restarted and has caught up
    Then the sink has refused no delta
    And the store holds the same nodes and relationships as a run without the restart

  Scenario: a store is built again from the topic alone
    Given the store holds the nodes and relationships of the carts
    When every element is removed from the store and the sink reads the topic again from the start
    Then the store holds the same nodes and relationships as before
    And no request was made to the service

  Scenario Outline: the same consumer written in another language fills the store the same way
    Given the consumer is written in "<language>" in place of "scala"
    When carts are created, changed, checked out and deleted
    Then the store holds the same nodes and relationships as with the consumer written in "scala"

    Examples:
      | language   |
      | python     |
      | typescript |
      | rust       |
