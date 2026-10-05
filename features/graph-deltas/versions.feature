Feature: The versions of deltas
  A delta's version is the sequence number of the change it was published for, unless the handler
  states one. A change delivered again publishes the same deltas, so the sink passes over them, and
  whatever an entity has been through, the versions of the elements it owns only rise.

  Scenario Outline: a delta's version is the sequence number of the change it was published for
    Given a consumer of the <kind> "cart" that publishes deltas
    And a change of the entity "c1" whose sequence number is 7
    When the change is handled
    Then every delta published for the change has the version 7

    Examples:
      | kind                  |
      | event sourced entity  |
      | key value entity      |

  Scenario: a change delivered again publishes the same deltas
    Given a consumer of the event sourced entity "cart" that publishes deltas
    And a change of the entity "c1" that has been handled
    When the change is delivered again
    Then the deltas published are the same as the first time in key, version and value

  Scenario Outline: the tombstone published for a deleted entity outranks every earlier delta
    Given a consumer of the <kind> "cart" that publishes deltas
    And deltas have been published for the entity "c1"
    When the entity "c1" is deleted and the consumer publishes a tombstone for its node
    Then the tombstone's version is greater than that of every delta published before for "c1"

    Examples:
      | kind                  |
      | event sourced entity  |
      | key value entity      |

  Scenario Outline: an entity created again under the same entity id comes back in the store
    Given a consumer of the <kind> "cart" that publishes deltas
    And the entity "c1" has been deleted and a tombstone published for its node
    When the entity "c1" is created again
    Then the version of its node's delta is greater than the tombstone's

    Examples:
      | kind                  |
      | event sourced entity  |
      | key value entity      |

  Scenario: an element published for a message from a topic must state its version
    Given a consumer of the topic "carts" that publishes deltas
    And the consumer's handler describes a node and states no version
    When a message from the topic is handled
    Then describing the node fails, saying a version is needed for a message from a topic

  Scenario Outline: a version the handler states is used in place of the sequence number
    Given a consumer of <source> that publishes deltas
    And the consumer's handler describes a node and states the version 12
    When <change> is handled
    Then the node's delta has the version 12

    Examples:
      | source                           | change                                |
      | the event sourced entity "cart"  | a change whose sequence number is 7   |
      | the topic "carts"                | a message from the topic              |
