Feature: A topic source reads its partitions in parallel
  A consumer or view over a topic that asks for it handles the partitions its instance holds at
  once, each partition in order, and commits a partition only once its message's publications are
  accepted. Order within a key is kept; throughput grows with partitions. A topic source that does
  not ask reads one message at a time.

  Background:
    Given a topic "events" with 4 partitions
    And a service with a consumer reading "events" whose handler takes one second
    And the consumer asks for its partitions in parallel

  Scenario: partitions held by one instance are handled at once when asked for
    Given one instance holds every partition
    When one message is produced to each partition
    Then all four are handled within two seconds

  Scenario: a topic source that does not ask reads one message at a time
    Given a consumer reading "events" that does not ask for its partitions in parallel
    And one instance holds every partition
    When one message is produced to each partition
    Then the four are handled one after another

  Scenario: messages under one key are handled in order
    When ten messages are produced under the key "cart-1"
    Then the consumer handles them in the order they were produced

  Scenario: a message that cannot be handled holds its partition and no other
    Given a message on partition 2 that the handler cannot handle
    When messages are produced to every partition
    Then the messages on partitions 0, 1 and 3 are handled
    And the message on partition 2 is handed to the consumer again until it is handled

  Scenario: a partition's offset is committed only after its message's publications are accepted
    Given the consumer publishes to "orders" for each message
    When the service stops after the handler ran and before the broker accepted its publication
    Then the message is handed to the consumer again when the service restarts
