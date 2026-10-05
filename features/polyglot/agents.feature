Feature: Agents in every language
  An agent's loop runs in the platform's own program whatever language its service is written in:
  talking to the model, keeping the session, running the guardrails' checks and counting what the
  model was asked. The developer's code is asked only to run a tool or a guardrail, so every language
  has the same loop, and a session never lives in the developer's code.

  Background:
    Given an agent "planner" with the tool "weather" and the guardrail "no-secrets"

  Scenario Outline: a tool runs in the developer's code with the model's arguments
    Given a service "trips" written in "<language>" with the agent "planner"
    And a model that asks for "weather" in "Lisbon" and then answers "Take a hat"
    When a caller sends a message to the session "s1" of "planner"
    Then "weather" runs in the developer's code, asked for "Lisbon"
    And the model is given what "weather" answered
    And the caller is answered "Take a hat"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a guardrail is consulted in the developer's code
    Given a service "trips" written in "<language>" with the agent "planner"
    And "no-secrets" refuses a message that holds a password
    When a caller sends a message holding a password to the session "s1" of "planner"
    Then "no-secrets" runs in the developer's code
    And the caller is refused

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: an agent's reply reaches its caller as a stream while the model produces it
    Given a service "trips" written in "<language>" with the agent "planner"
    And a model that answers "Take a hat" a part at a time
    When a caller asks the session "s1" of "planner" for its reply as a stream
    Then the caller is given each part as the model produces it, as a caller of an agent written in Scala is

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a session survives a restart of the developer's code
    Given a service "trips" written in "<language>" with the agent "planner"
    And the session "s1" of "planner" has had 2 turns
    When <restarted> restarts
    And a caller sends a message to the session "s1"
    Then the model is given the 2 earlier turns of "s1"

    Examples:
      | language   | restarted                       |
      | Python     | the process                     |
      | TypeScript | the process                     |
      | Rust       | the platform's own program      |

  Scenario Outline: a tool that fails is told to the model and the loop goes on
    Given a service "trips" written in "<language>" with the agent "planner"
    And "weather" fails when it runs
    When a caller sends a message to the session "s1" of "planner" and the model asks for "weather"
    Then the model is told that "weather" failed
    And the model is asked again, and its answer is the caller's reply

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
