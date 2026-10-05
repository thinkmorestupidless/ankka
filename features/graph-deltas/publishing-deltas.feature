Feature: Publishing an entity as deltas
  A consumer that publishes deltas describes, for each change, the nodes and relationships the change
  leaves
  in which state, and the tombstones of those it removes. Each is published as one delta under its
  element key, at the version of the change. The handler writes no key, no version and no delta of
  its own.

  Background:
    Given a consumer of the event sourced entity "cart" that publishes deltas to the topic "cart-elements"

  Scenario: a node is published as one node delta under its element key
    Given the consumer's handler describes a node with the element id "c1", the label "Cart" and the property "items" of 3
    When a change of the entity "c1" is handled
    Then the topic holds one delta, under the key "node:c1"
    And the delta is a node with the label "Cart" and the property "items" of 3

  Scenario: a relationship is published as one relationship delta under its element key
    Given the consumer's handler describes a relationship with the element id "e1" of type "CHECKED_OUT" from "c1" to "k1"
    When a change of the entity "c1" is handled
    Then the topic holds one delta, under the element key of the relationship "e1"
    And the delta is a relationship of type "CHECKED_OUT" from "c1" to "k1"

  Scenario: a tombstone is published under the element key of the element it marks
    Given the consumer's handler describes a tombstone for the node with the element id "c1"
    When a change of the entity "c1" is handled
    Then the topic holds one delta, under the key "node:c1"
    And the delta is a tombstone

  Scenario: a node and a relationship with the same element id are published under different keys
    Given the consumer's handler describes a node and a relationship, each with the element id "x1"
    When a change of the entity "c1" is handled
    Then the node's delta and the relationship's are under different keys
    And neither replaces the other in a compacted topic

  Scenario Outline: an element the sink would refuse cannot be described
    Given the consumer's handler describes a node with <fault>
    When a change of the entity "c1" is handled
    Then describing the node fails, naming <fault>
    And nothing is published for the change
    And the change is delivered again

    Examples:
      | fault                                     |
      | an empty element id                       |
      | the label "not a label"                   |
      | the property "_version"                   |
      | a property whose value is a list inside a list |

  Scenario Outline: an element key is the one the sink computes for the same delta
    Given the consumer's handler describes a node with the element id "<id>"
    When a change of the entity "c1" is handled
    Then the delta's key is the one the sink computes for the node "<id>"

    Examples:
      | id       |
      | a:b:c    |
      | größe    |
      | カート    |
