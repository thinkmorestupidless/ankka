Feature: Deploying a web-hosted service
  A member deploys an interface as a web-hosted service: an image whose process only serves requests,
  run beside the platform's proxy. It is a service like any other, applied, exposed, scaled, paused
  and read with what every service has, and it is given nothing it has no use for.

  Scenario: a web-hosted service is deployed from an image that only serves requests
    Given an image "shop-web" whose process serves requests and has nothing of the platform in it
    When a member applies a descriptor for the web-hosted service "web" with the image "shop-web"
    Then "web" is ready once its process listens for requests

  Scenario: a web-hosted service is given no database
    Given a web-hosted service "web" deployed in the project "shop"
    When a member reads the report of "web"
    Then the report says that "web" has no database
    And no database exists for "web"

  Scenario: a web-hosted service is private until it is exposed
    Given a web-hosted service "web" deployed in the project "shop"
    And "web" is not exposed
    When a person on the internet sends a request to the hostname of "web"
    Then the request reaches no instance of "web"

  Scenario: an exposed web-hosted service answers at its hostname
    Given a web-hosted service "web" deployed in the project "shop"
    And "web" is exposed
    When a browser sends a request to the hostname of "web"
    Then the browser is shown what the process of "web" answered

  Scenario Outline: a web-hosted service is scaled, restarted, paused and resumed like any other service
    Given a web-hosted service "web" deployed in the project "shop", <state>
    When a member <action>
    Then <outcome>

    Examples:
      | state            | action                      | outcome                                                    |
      | with 1 instance  | scales "web" to 3 instances | "web" is ready with 3 instances                            |
      | with 1 instance  | restarts "web"              | "web" is ready with 1 instance that started after the restart |
      | with 1 instance  | pauses "web"                | the lifecycle of "web" is "Paused" and "web" has no instances |
      | paused           | resumes "web"               | "web" is ready with 1 instance                             |

  Scenario Outline: a web-hosted service whose image changes refuses no request
    Given a web-hosted service "web" deployed with the image "shop-web:1" and <instances>
    And a browser sending requests to "web" one after another
    When a member applies the descriptor of "web" with the image "shop-web:2"
    Then every request the browser sends is answered
    And "web" is ready with the image "shop-web:2"

    Examples:
      | instances   |
      | 1 instance  |
      | 3 instances |

  Scenario Outline: the process is told the port to listen on
    Given a descriptor for the web-hosted service "web" that <states>
    When a member applies the descriptor
    Then the process of "web" is told to listen on <port>
    And "web" is ready once its process listens there

    Examples:
      | states                               | port                         |
      | states no port for its process       | the port the platform gives  |
      | states the port "8181" for its process | the port "8181"            |

  Scenario: a process that never listens is reported with the reason
    Given an image "silent" whose process listens for nothing
    When a member applies a descriptor for the web-hosted service "web" with the image "silent"
    Then the lifecycle of "web" is "Failed"
    And the report says that the process did not listen for requests

  Scenario: a process that stops listening takes its instance out of the service
    Given a web-hosted service "web" deployed with 2 instances
    When the process of 1 instance stops listening for requests
    Then that instance is not ready
    And every request to "web" is answered by the process of the other instance

  Scenario: the logs of a web-hosted service are what its process printed
    Given a web-hosted service "web" whose process has printed "listening"
    When a member reads the logs of "web"
    Then the logs show "listening"
    And the logs show nothing the proxy printed

  Scenario: a member reads what the proxy of a web-hosted service printed
    Given a web-hosted service "web" deployed in the project "shop"
    When a member reads the logs of the proxy of "web"
    Then the logs show what the proxy printed
    And the logs show nothing the process printed

  Scenario: a web-hosted service counts towards the quota of its organization
    Given an organization that has every service its quota allows
    When a member applies a descriptor for the web-hosted service "web" in a project of that organization
    Then the member is refused
    And the refusal says that the organization has every service its quota allows

  Scenario: a member is shown that a service is a web-hosted service
    Given a web-hosted service "web" deployed in the project "shop" with "cart" mounted at "/backend/cart"
    When a member reads the report of "web"
    Then the report says that the hosting of "web" is web hosting
    And the report shows the mount of "cart" at "/backend/cart"
    And the report shows which services "web" admits

  Scenario: the size a descriptor asks for is the size of the process
    Given a descriptor for the web-hosted service "web" that asks for the size "medium"
    When a member applies the descriptor
    Then the process of "web" has the size "medium"
    And the proxy takes nothing from the size of the process

  Scenario: a mount of a service that does not exist is applied and marked
    Given a web-hosted service "web" deployed in the project "shop"
    And no service "ledger" in the project "shop"
    When a member applies the descriptor of "web" with "ledger" mounted at "/backend/ledger"
    Then "web" is ready
    And the report of "web" marks the mount of "ledger" as having no service
