Feature: Every kind of component in every language
  Key value entities, views, consumers, timed actions and workflows are written in any language the
  platform hosts, and behave the same in each. The developer's code decides; the platform's own
  program keeps the state, the rows, how far a consumer has read, the timers and the workflow's
  progress.

  Scenario Outline: a key value entity's state is kept and recovered after a restart
    Given a service "shop" written in "<language>" with a key value entity "profile"
    And a caller has set the state of the profile "p1" to "Ada"
    When the service restarts and a caller asks the profile "p1" for its state
    Then the caller is answered "Ada"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a view's rows follow its source's events and are removed with its source
    Given a service "shop" written in "<language>" with a view "carts-by-owner" of the events of the entity "cart"
    When the cart "c1" of "ada" records that "socks" was added, and is then deleted
    Then the view's row for "c1" held "socks" before the deletion
    And the view holds no row for "c1" after it
    And the view's rows were written by the developer's code

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a consumer is given each event at least once and in the order its entity recorded them
    Given a service "shop" written in "<language>" with a consumer "checkout-recorder" of the events of the entity "cart"
    When the cart "c1" records 3 events and the service restarts
    Then "checkout-recorder" has been given the 3 events of "c1" in the order they were recorded
    And after the restart "checkout-recorder" is not given again an event it had handled

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a consumer publishes the messages its handler produces
    Given a service "shop" written in "<language>" with a consumer that publishes to the topic "checkouts" for each event it is given
    When the cart "c1" records that it was checked out
    Then a message about "c1" is published to the topic "checkouts"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a timer set by a handler runs its timed action, and again after a failure
    Given a service "shop" written in "<language>" whose handler sets a timer for the timed action "remind"
    And the action fails the first time it runs
    When the timer is due
    Then the action runs in the developer's code
    And the action runs again after it failed

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a timer due while the process is not running runs once it is
    Given a service "shop" written in "<language>" with a timer for the timed action "remind"
    And the process of "shop" is not running when the timer is due
    When the process starts again
    Then the action runs in the process

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: a workflow's steps run in the developer's code and each step is recorded
    Given a service "shop" written in "<language>" with a workflow "checkout" of the steps "reserve" and "charge"
    When a caller starts the workflow "checkout"
    Then "reserve" and then "charge" run in the developer's code
    And the platform has recorded the workflow moving from each step to the next

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a step that fails is retried and then failed over as the workflow declared
    Given a service "shop" written in "<language>" with a workflow "checkout" whose step "charge" is retried twice and then fails over to "refund"
    When "charge" fails every time it runs
    Then "charge" runs 3 times
    And then "refund" runs

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a command to a workflow during a step is answered from the state before the step
    Given a service "shop" written in "<language>" with a workflow "checkout" whose step "reserve" is running
    When a caller asks the workflow "checkout" for its state
    Then the caller is answered from the state before "reserve"
    And "reserve" finishes as it would have without the question

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a call through the SDK's client reaches its component wherever it runs
    Given a service "shop" written in "<language>" running as 3 instances
    And a workflow step that calls the entity "cart" through the SDK's client
    When the step calls the cart "c1"
    Then the cart "c1" is given the call, whichever instance it is loaded on
    And the step is answered with the value the cart replied, as the type the step asked for

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a call made by a handler is nested under that handler in the trace
    Given a service "shop" written in "<language>" whose consumer "checkout-recorder" calls the entity "ledger"
    When "checkout-recorder" handles an event
    Then the trace shows the call to "ledger" nested under the handler of "checkout-recorder"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
