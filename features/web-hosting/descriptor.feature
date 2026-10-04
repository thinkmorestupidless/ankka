Feature: Describing a web-hosted service
  A descriptor says a service is a web-hosted service by its hosting. What has no meaning for one
  is refused when the descriptor is applied, in the same words wherever it is checked, and
  every problem is named at once.

  Scenario Outline: a descriptor for a web-hosted service is refused for what does not apply to it
    Given a descriptor for the web-hosted service "web" that <declares>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names <named>

    Examples:
      | declares                                        | named                      |
      | says the service serves no requests             | what it said               |
      | declares a protocol version                     | the protocol version       |
      | declares a runtime version                      | the runtime version        |
      | declares a database of its own                  | the variable it declared   |
      | sets the variable "PORT"                        | the variable "PORT"        |
      | states the port "70000" for its process         | the port                   |
      | states the port of the service for its process  | the port                   |
      | gives the service a port the proxy listens on   | the port                   |
      | states a port the proxy listens on for its process | the port                |

  Scenario Outline: a descriptor cannot take a variable from a secret the platform keeps a certificate in
    Given a descriptor for <service> with a variable taken from the secret that holds <certificate>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the variable and the secret

    Examples:
      | service                                  | certificate                                  |
      | the web-hosted service "web"             | the certificate of "web"                     |
      | the web-hosted service "web"             | the certificate "web" passes mounts on with  |
      | a service "cart" that is not web-hosted  | the certificate of the service "orders"      |

  Scenario Outline: a mount is refused when it is malformed
    Given a descriptor for the web-hosted service "web" with <mounts>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the mount and says <why>

    Examples:
      | mounts                                                  | why                                         |
      | "cart" mounted at "backend/cart"                            | that a path starts with "/"                 |
      | "cart" mounted at "/"                                   | that a mount cannot be every path           |
      | "cart" mounted at "/backend" and "orders" mounted at "/backend" | that the path is mounted more than once     |
      | "cart" mounted at "/backend" and "orders" mounted at "/backend/orders" | that one mount is inside another     |
      | "Cart Service" mounted at "/backend/cart"                   | that "Cart Service" cannot name a service   |
      | "web" mounted at "/backend/web"                             | that a web-hosted service cannot mount itself      |

  Scenario Outline: the services a web-hosted service admits are refused when they are malformed
    Given a descriptor for the web-hosted service "web" that admits <admitted>
    When a member applies the descriptor
    Then the member is refused
    And the refusal says <why>

    Examples:
      | admitted                                   | why                                       |
      | the service "Order Service"                | that "Order Service" cannot name a service |
      | the service "orders" in the project "My Shop" | that "My Shop" cannot name a project   |
      | the service "orders" and the service "orders" | that "orders" is admitted more than once |

  Scenario Outline: a descriptor for a service that is not a web-hosted service is refused for what only a web-hosted service has
    Given a descriptor for a service "cart" that is not a web-hosted service, <with>
    When a member applies the descriptor
    Then the member is refused
    And the refusal says <why>

    Examples:
      | with                                  | why                                         |
      | with "orders" mounted at "/backend/orders" | that only a web-hosted service has mounts          |
      | that admits the service "orders"      | that only a web-hosted service names the services it admits |

  Scenario: every problem with a descriptor is named at once
    Given a descriptor for the web-hosted service "web" that declares a protocol version and has "cart" mounted at "/"
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the protocol version
    And the refusal names the mount
