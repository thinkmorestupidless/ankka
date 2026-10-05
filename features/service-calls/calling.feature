Feature: One service calling another as itself, in every language
  A handler of a service calls a route of another service by the other's name, as itself, whether
  the service is written in Scala, Python or TypeScript. The platform's own program makes the call
  with the service's certificate, so the service called reads the calling workload as the service
  that called and its ACL can admit that service and nothing else.

  Scenario Outline: a service in every language is admitted by name by a route that admits only it
    Given a deployed service "psp-gateway" in the project "payments" written in "<language>"
    And a deployed service "merchant" in the project "payments" whose route "POST /internal/v1/payments/{id}/capture" admits only "psp-gateway"
    When a handler of "psp-gateway" calls that route of "merchant"
    Then the call is admitted
    And the handler of "merchant" reads the calling workload as the service "psp-gateway" of the project "payments"
    And the handler of "psp-gateway" is given the answer of "merchant"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario: a route that admits only one service by name refuses the gateway
    Given a deployed service "merchant" in the project "payments" whose route "POST /internal/v1/payments/{id}/capture" admits only "psp-gateway"
    When a request to that route arrives through the gateway
    Then the request is refused

  Scenario Outline: a refusal by the service called reaches the calling handler as that refusal
    Given a deployed service "psp-gateway" in the project "payments" written in "<language>"
    And a deployed service "merchant" in the project "payments" whose ACL refuses "psp-gateway"
    When a handler of "psp-gateway" calls "merchant"
    Then the handler of "psp-gateway" is given the refusal as "merchant" made it
    And the handler of "psp-gateway" is told that "merchant" answered

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a call to a service that cannot be found fails, naming the service, and is not sent
    Given a deployed service "psp-gateway" in the project "payments" written in "<language>"
    And no service "ledger" in the project "payments"
    When a handler of "psp-gateway" calls "ledger"
    Then the handler of "psp-gateway" is told that the service "ledger" cannot be found
    And no call is sent to any service

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a call is not sent to a workload that is not the service asked for
    Given a deployed service "psp-gateway" in the project "payments" written in "<language>"
    And a workload where "merchant" is reached whose certificate names the service "orders"
    When a handler of "psp-gateway" calls "merchant"
    Then the handler of "psp-gateway" is told that the workload is not the service "merchant"
    And nothing the handler sent reaches the workload

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a request that may change something is sent at most once when no answer comes
    Given a deployed service "psp-gateway" in the project "payments"
    And a deployed service "merchant" in the project "payments" whose instance closes every connection before answering
    When a handler of "psp-gateway" calls "merchant" with the method "<method>"
    Then the handler of "psp-gateway" is told that the call was unanswered
    And "merchant" is sent the call at most <times>

    Examples:
      | method | times |
      | POST   | once  |
      | PUT    | once  |
      | DELETE | once  |
      | PATCH  | once  |
      | GET    | twice |
      | HEAD   | twice |

  Scenario Outline: on a developer's machine a service in every language calls another service running there
    Given a service "merchant" running on a developer's machine
    And a service "psp-gateway" written in "<language>" running on a developer's machine that is told where "merchant" is
    When a handler of "psp-gateway" calls "merchant"
    Then the handler of "psp-gateway" is given the answer of "merchant"
    And the handler of "merchant" reads the calling workload as the local caller

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario: a call that is not answered within the time its service is set to wait is unanswered
    Given a service "psp-gateway" set to wait "2" seconds for the answer of another service
    And a service "slow" that answers after "5" seconds
    When a handler of "psp-gateway" calls "slow"
    Then the handler of "psp-gateway" is told that the call was unanswered
    And the call is sent once
