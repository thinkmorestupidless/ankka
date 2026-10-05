Feature: What the documentation says of publishing several messages and deltas
  The rules a consumer that publishes deltas keeps cannot all be enforced when an element is
  described: a delta is an element's whole state, and an element has one writer. The documentation
  says them where a reader will meet them, beside how to publish several messages and deltas.

  Scenario: the documentation describes publishing several messages for one change
    When a reader looks up publishing more than one message in the documentation of consumers
    Then the documentation shows a handler publishing several messages and naming a key
    And the documentation says when the change counts as handled

  Scenario: the documentation describes publishing deltas and the rules of a delta
    When a reader looks up publishing deltas in the documentation
    Then the documentation shows a consumer publishing nodes, relationships and tombstones
    And the documentation states the rules a consumer that publishes deltas keeps
    And the documentation says the topic must be compacted and that the pipeline reading it makes it so
    And the documentation says where the sink and building a store again are documented

  Scenario: the documentation says what to do when events do not carry an element's whole state
    When a reader whose events do not carry everything an element shows looks up publishing deltas in the documentation
    Then the documentation says how to describe the element from the entity's state instead

  Scenario: the documentation's reference describes several messages and describing deltas
    When a reader looks up a consumer's handler in the documentation's reference
    Then the documentation describes publishing several messages for one change
    And the documentation describes how a handler describes nodes, relationships and tombstones
