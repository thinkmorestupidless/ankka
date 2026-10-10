Feature: The documentation of waiting for a workflow
  A developer learns from the documentation how to start a workflow and wait for its end, how to
  wait for one already started, what each ending answers, that the caller gives the time, and
  when to serve the wait as a stream.

  Scenario: the documentation describes starting a workflow and waiting for its end
    Given the published documentation
    When a reader reads about calling a workflow
    Then the documentation describes sending a command and waiting for the end as one call
    And the documentation describes waiting for a workflow already started
    And the documentation shows both in each language

  Scenario: the documentation says what each ending answers and when to serve a wait as a stream
    Given the published documentation
    When a reader reads about waiting for a workflow
    Then the documentation says what a completed, a failed, a deleted and a paused workflow answer
    And the documentation says the caller gives the time and that there is no default
    And the documentation says when to serve the wait as a stream

  Scenario: the documentation says Akka offers no wait for a workflow's end
    Given the published documentation
    When a reader reads where ankka differs from Akka about calling a workflow
    Then the documentation says a caller can wait for a workflow's end, which Akka does not offer
