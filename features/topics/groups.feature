Feature: A topic source's group
  A view or consumer that reads a topic reads it through a group of its own, named for its project,
  its service, its kind and its id. Two services reading one topic are two groups, so each is
  delivered every message.

  Scenario: two services reading one topic through views of the same id each hold every message
    Given the services "orders" and "billing" in the project "shop" on one broker
    And each service has a view "summary" reading the topic "order-changes"
    When 100 messages with distinct subjects are published to the topic
    Then the view of the service "orders" holds 100 rows
    And the view of the service "billing" holds 100 rows

  Scenario: the instances of one service read each partition of a topic once between them
    Given a service with 2 instances and a view reading the topic "order-changes"
    When 100 messages with distinct subjects are published to the topic
    Then the view holds 100 rows
    And each partition of the topic is read by one instance

  Scenario Outline: a deployed service's group is named for its project, its service, its kind and its id
    Given a deployed service "orders" in the project "shop"
    And the service has a <kind> "summary" reading the topic "order-changes"
    When the <kind> subscribes
    Then its group is "<group>"

    Examples:
      | kind     | group                              |
      | view     | ankka.shop.orders.view.summary     |
      | consumer | ankka.shop.orders.consumer.summary |

  Scenario Outline: a local service that states its name has a group named for it
    Given a local service that states the name "orders"
    And the service has a <kind> "summary" reading the topic "order-changes"
    When the <kind> subscribes
    Then its group is "<group>"

    Examples:
      | kind     | group                               |
      | view     | ankka.local.orders.view.summary     |
      | consumer | ankka.local.orders.consumer.summary |

  Scenario Outline: a local service that states no name has a group named for its kind and id alone
    Given a local service that states no name
    And the service has a <kind> "summary" reading the topic "order-changes"
    When the <kind> subscribes
    Then its group is "<group>"

    Examples:
      | kind     | group                  |
      | view     | ankka-view-summary     |
      | consumer | ankka-consumer-summary |

  Scenario: two named local services reading one topic each hold every message
    Given the local services "orders" and "billing" on one broker
    And each service has a view "summary" reading the topic "order-changes"
    When 100 messages with distinct subjects are published to the topic
    Then the view of the service "orders" holds 100 rows
    And the view of the service "billing" holds 100 rows

  Scenario Outline: a project made from a template states its service's name
    Given a project named "orders" made from the "<language>" template
    When its service runs as a local service
    Then the service states the name "orders"

    Examples:
      | language   |
      | scala      |
      | python     |
      | typescript |
      | rust       |

  Scenario: no project is named "local"
    When a project named "local" is created
    Then the creation is refused because the name is reserved for local services

  Scenario: an upgraded service starts again under its new group
    Given a deployed service whose view has read the topic "order-changes" under the group "ankka-view-summary"
    When the service is upgraded to name its group for its project and its service
    Then the view reads the topic from its start position under the new group
    And the group "ankka-view-summary" is left as it was

  Scenario: the longest permitted ids make a group the broker accepts
    Given a project, a service and a view each with the longest id permitted
    When the view subscribes
    Then the broker accepts its group
