Feature: The sink is ankka's
  The platform provides the sink: it reads a delta topic from its start, applies each delta to a
  store only when its version is newer than the element's there, refuses a delta that breaks the
  rules of one and says which, and at a higher version builds the store again from the topic.
  The fixtures the sink and the SDKs are tested against are ankka's own.

  Background:
    Given an installation with a broker
    And a project "shop" with the topic "cart-deltas" declared compacted
    And a service "cart" whose consumer publishes deltas to "cart-deltas"
    And a store

  Scenario: the sink fills a store from a delta topic
    Given the sink is deployed into "shop" reading "cart-deltas" into the store
    When "cart" publishes a delta for each of 20 carts and their items
    Then the store holds a node for each cart and each item and a relationship from each cart to its items

  Scenario: the sink applies a delta only when its version is newer
    Given the sink is deployed into "shop" reading "cart-deltas" into the store
    And the store holds the node "cart-1" at version 5
    When "cart" publishes a delta for "cart-1" at version 3
    Then the store holds "cart-1" at version 5 as it was

  Scenario: the sink refuses a delta that breaks the rules and says which
    Given the sink is deployed into "shop" reading "cart-deltas" into the store
    When a delta for a relationship whose node the store does not hold is published to "cart-deltas"
    Then the sink's log names the delta's key and the rule it breaks
    And the status of the sink counts one refused delta
    And the sink reads on

  Scenario: the sink at a higher version builds the store again from the topic
    Given the sink is deployed into "shop" at version 1 and the store holds every element the topic describes
    And every element is removed from the store
    When a member deploys the sink at version 2
    Then the sink reads "cart-deltas" again from its start
    And the store holds every element the topic describes

  Scenario: the sink's fixtures are ankka's own
    When the fixtures of deltas, keys and refused deltas are read
    Then each is written here and named as such
    And every SDK and the sink are tested against them
