Feature: Deploying a service written in another language
  A service written in Python or TypeScript is deployed from an image holding only the developer's
  process, and the platform runs its own program beside it. A service written in Rust is deployed
  from an image holding only its module, which the platform's own program loads. Either way the
  service is deployed, scaled, restarted, paused, exposed and read exactly as a service written in
  Scala is, with the same words for what it is doing, and its descriptor chooses nothing of the
  platform's own program.

  Scenario Outline: a service hosted as a process runs beside the platform's own program and is ready only when both are
    Given an image "shop" holding only a process written in "<language>"
    When a member applies a descriptor for the service "shop" hosted as a process with the image "shop"
    Then every instance of "shop" runs the platform's own program beside the process
    And "shop" is ready only once the platform's own program and the process are both ready

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: a deployed service says which SDK its code was built with
    Given a service "shop" written in "<language>", built with the SDK at the version "1.4.0"
    When a member applies its descriptor
    Then "shop" is ready
    And the service says it was built with the SDK for "<language>" at the version "1.4.0"
    And the cart "c1" of "shop" answers a request sent through the gateway

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a deployed service runs the platform's own program at the installation's version
    Given a descriptor for the service "shop" written in "<language>"
    When a member applies the descriptor
    Then the platform's own program of "shop" is the installation's own, at its version

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service written in another language scales into one cluster without replacing an instance
    Given a service "shop" written in "<language>" deployed with 1 instance
    When a member scales "shop" to 3 instances
    Then the 3 instances of "shop" form one cluster
    And the instance that was running before is still running

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service written in another language is restarted one instance at a time and refuses no request
    Given a service "shop" written in "<language>" deployed with 3 instances
    And a caller sending requests to "shop" one after another
    When a member restarts "shop"
    Then each instance of "shop" is replaced one at a time
    And every request the caller sends is answered

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service written in another language is exposed, paused and resumed like any other service
    Given a service "shop" written in "<language>" deployed in the project "store", <state>
    When a member <action>
    Then <outcome>

    Examples:
      | language   | state           | action          | outcome                                                     |
      | Python     | not exposed     | exposes "shop"  | "shop" answers a request sent to its hostname                |
      | TypeScript | not exposed     | exposes "shop"  | "shop" answers a request sent to its hostname                |
      | Rust       | not exposed     | exposes "shop"  | "shop" answers a request sent to its hostname                |
      | Python     | with 1 instance | pauses "shop"   | the lifecycle of "shop" is "Paused" and "shop" has no instances |
      | TypeScript | with 1 instance | pauses "shop"   | the lifecycle of "shop" is "Paused" and "shop" has no instances |
      | Rust       | with 1 instance | pauses "shop"   | the lifecycle of "shop" is "Paused" and "shop" has no instances |
      | Python     | paused          | resumes "shop"  | "shop" is ready with 1 instance                              |
      | TypeScript | paused          | resumes "shop"  | "shop" is ready with 1 instance                              |
      | Rust       | paused          | resumes "shop"  | "shop" is ready with 1 instance                              |

  Scenario Outline: the trace of a deployed service attributes each handler's time to the component that did the work
    Given a service "shop" written in "<language>" deployed in the project "store"
    When a member reads the trace of a request that added an item to the cart "c1"
    Then the trace shows the time spent in the handler of "add-item" of the entity "cart"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a process that stops is started again while its instance stays in the cluster
    Given a service "shop" written in "<language>" deployed with 2 instances
    When the process of 1 instance stops
    Then that process is started again
    And the platform's own program of that instance stays in the cluster
    And a request to "shop" sent again until it is answered is answered

    Examples:
      | language   |
      | Python     |
      | TypeScript |
