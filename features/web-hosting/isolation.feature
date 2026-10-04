Feature: What an instance of a web-hosted service keeps to itself
  The process of a web-hosted service is reached only through the proxy, and the calling address
  only by the process. The proxy holds the certificate, so nothing the process is or does can be
  taken for the web-hosted service by another service.

  Background:
    Given a web-hosted service "web" deployed in the project "shop"
    And a service "orders" deployed in the project "shop"

  Scenario: the process cannot be reached except through the proxy
    When the service "orders" connects to the process of "web" without the proxy
    Then the connection is refused

  Scenario: only the process can use the calling address
    When the service "orders" connects to the calling address of an instance of "web"
    Then the connection is refused

  Scenario: the process is given no certificate
    When an instance of "web" starts
    Then the process holds no certificate
    And the proxy holds the certificate of "web"
