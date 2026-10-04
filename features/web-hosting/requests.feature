Feature: Requests to a web-hosted service
  The proxy accepts every request to a web-hosted service and passes it to the process, saying who sent it
  and where it was sent. The process holds no certificate and needs none: what the proxy says is
  read from the certificate of whoever connected, so nothing a request says about itself can
  change it.

  Background:
    Given a web-hosted service "web" deployed in the project "shop"
    And "web" is exposed

  Scenario Outline: the process is told who sent a request
    Given the descriptor of "web" admits <admitted>
    When <sender> sends a request to "web"
    Then the process is told that the request came from <told>

    Examples:
      | admitted                                        | sender                                          | told                                            |
      | no service                                      | a person on the internet                        | the internet                                    |
      | the service "orders"                            | a person on the internet                        | the internet                                    |
      | the service "orders"                            | the service "orders" in the project "shop"      | the service "orders" in the project "shop"      |
      | the service "invoices" in the project "billing" | the service "invoices" in the project "billing" | the service "invoices" in the project "billing" |
      | every service in the project "shop"             | the service "orders" in the project "shop"      | the service "orders" in the project "shop"      |
      | no service                                      | the service "web" in the project "shop"         | the service "web" in the project "shop"         |

  Scenario Outline: a service the descriptor does not admit is refused by the proxy
    Given the descriptor of "web" admits <admitted>
    When <sender> sends a request to "web"
    Then that service is refused
    And the process is given no request

    Examples:
      | admitted                            | sender                                          |
      | no service                          | the service "orders" in the project "shop"      |
      | the service "orders"                | the service "ledger" in the project "shop"      |
      | the service "orders"                | the service "orders" in the project "billing"   |
      | every service in the project "shop" | the service "invoices" in the project "billing" |

  Scenario: a request cannot say that another service sent it
    When a person on the internet sends a request that says the service "orders" sent it
    Then the process is told that the request came from the internet
    And the process is not shown what the request said

  Scenario: the process is told the address a request was sent to
    When a person on the internet sends a request to the hostname of "web"
    Then the process is told the hostname of "web" as the address the request was sent to

  Scenario: a request cannot say that it was sent to another address
    When a person on the internet sends a request to the hostname of "web" that says it was sent to "bank.example"
    Then the process is told the hostname of "web" as the address the request was sent to

  Scenario: an answer reaches the browser as the process makes it
    Given the process answers a request with a stream of 3 parts, one each second
    When a browser sends that request
    Then the browser is shown each part when the process makes it
    And the browser does not wait for the last part to be shown the first

  Scenario: a request the process does not answer in time is answered by the proxy
    Given the process takes a request and never answers it
    When a browser sends that request
    Then the proxy answers that "web" did not answer in time
    And the instance is ready
