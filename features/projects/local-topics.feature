Feature: A project's topics on a developer's machine
  A project's repository holds a topic declaration for each of the project's topics. Bringing the
  project up makes each on the local broker as declared, before any service starts, and the local
  broker makes no topic of its own.

  Background:
    Given a project "shop" with the services "orders" and "billing"

  Scenario: each topic the project declares is made on the local broker with its partitions before any service starts
    Given "shop" holds a topic declaration for "orders-placed" with 6 partitions
    When the developer brings "shop" up
    Then the local broker holds the topic "orders-placed" with 6 partitions
    And the topic was made before "orders" and "billing" started

  Scenario: a topic declared as compacted is made compacted
    Given "shop" holds a topic declaration for "customers" that is compacted
    When the developer brings "shop" up
    Then the local broker holds the topic "customers", compacted

  Scenario: a topic that already exists with other partitions is reported, and the command fails
    Given the local broker of "shop" holds the topic "orders-placed" with 1 partition
    And "shop" holds a topic declaration for "orders-placed" with 6 partitions
    When the developer brings "shop" up
    Then the command line fails, naming "orders-placed", 1 partition and 6 partitions
    And no service is started

  Scenario: the local broker makes no topic that the project does not declare
    Given "shop" holds no topic declaration for "returns"
    And "shop" is up
    When "orders" publishes to the topic "returns"
    Then the local broker holds no topic "returns"
    And the log of "orders" names "returns"

  Scenario: a topic crossing in the context map that names no declared topic is refused before anything starts
    Given the context map of "shop" has a crossing from "orders" to "billing" on the topic "returns"
    And "shop" holds no topic declaration for "returns"
    When the developer brings "shop" up
    Then the command line refuses, naming the topic "returns"
    And nothing is started
