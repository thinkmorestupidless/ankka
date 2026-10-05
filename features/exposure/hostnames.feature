Feature: The hostname of an exposed service
  An exposed service's hostname is made from its name, its project and the installation's base
  domain, so it can be predicted, written once into whatever calls the service, and never names two
  services. Nobody chooses it, nothing a member does to the service changes it, and a hostname that
  could not be reached is refused rather than given.

  Scenario: a hostname is made from the service's name, its project and the base domain
    Given an installation whose base domain is "example.test"
    When a member exposes the service "cart" of the project "checkout"
    Then the hostname of "cart" is "cart-checkout.example.test"
    And the documentation says how a hostname is made

  Scenario Outline: services of one name in two projects each answer at their own hostname
    Given the service "cart" is exposed in the projects "checkout" and "returns"
    When a person on the internet sends a request to the hostname of "cart" of the project "<project>"
    Then the request is answered by "cart" of the project "<project>"

    Examples:
      | project  |
      | checkout |
      | returns  |

  Scenario Outline: a hostname does not change when a member changes the service
    Given a deployed service "cart" that is exposed
    When a member <changes>
    Then the hostname of "cart" is the hostname it had before

    Examples:
      | changes                                  |
      | restarts "cart"                          |
      | applies the descriptor of "cart" with a new image |
      | scales "cart" to "3" instances           |

  Scenario Outline: a member is shown a service's hostname, or that it is not exposed
    Given a deployed service "cart" that <exposure>
    When a member <reads>
    Then the member is shown <shown>

    Examples:
      | exposure       | reads                                  | shown                        |
      | is exposed     | reads the service "cart"               | the hostname of "cart"       |
      | is exposed     | lists the services of the project      | the hostname of "cart"       |
      | is not exposed | reads the service "cart"               | that "cart" is not exposed   |
      | is not exposed | lists the services of the project      | that "cart" is not exposed   |

  Scenario Outline: a service whose hostname could not be reached is refused when it is exposed
    Given <given>
    When a member exposes the service "<service>" of the project "<project>"
    Then the member is refused
    And the refusal says <why>

    Examples:
      | given                                                            | service                                  | project                        | why                                                   |
      | no other service is exposed                                      | inventory-reconciliation-worker-service  | warehouse-north-east-region    | that the hostname is longer than a hostname may be    |
      | the service "a" of the project "b-c" is exposed                  | a-b                                      | c                              | that the hostname is held by "a" of the project "b-c" |

  Scenario: a service that serves neither HTTP nor gRPC is refused when it is exposed
    Given a deployed service "worker" whose descriptor declares no HTTP and does not declare gRPC
    When a member exposes "worker"
    Then the member is refused
    And the refusal says that "worker" has nothing to expose
