Feature: A project's topics are its own
  The installation has one broker for every project, so the boundary between projects is the
  broker's to keep: a service's credential reaches the topics of its own project and nothing else.
  The platform checks nothing when a service reads or publishes; the broker refuses.

  Background:
    Given an installation with a broker
    And the topic "transactions" is declared on "money"
    And a deployed service "lobby" in the project "casino"

  Scenario: a service reads nothing of another project's topic of the same name
    Given a view "seen" of "lobby" that reads the topic "transactions"
    When a service of "money" publishes to the topic "transactions"
    Then the view "seen" shows nothing of what was published
    And the logs of "lobby" name the topic "transactions" as one "casino" has not declared

  Scenario Outline: a service's credential is refused a topic of another project
    When something holding the credential of "lobby" <asks> "money.transactions" on the installation's broker
    Then the broker refuses it

    Examples:
      | asks          |
      | reads         |
      | publishes to  |

  Scenario: a service's credential reaches the topics of its own project and nothing else
    When what the installation's broker allows the credential of "lobby" is listed
    Then the credential may read and publish to the topics of "casino"
    And the credential may do nothing else on the broker
