Feature: Services that are not web-hosted, once the platform has web hosting
  Web hosting is added beside the ways the platform already runs a service. What the platform
  makes for a service that is not web-hosted is what it made before, so nothing about such a
  service changes and none of its instances restarts.

  Scenario Outline: a platform that gains web hosting changes nothing it makes for a service that is not web-hosted
    Given a platform without web hosting, with <service> deployed in the project "shop"
    When the platform gains web hosting
    Then nothing the platform makes for that service has changed
    And no instance of that service restarts

    Examples:
      | service                                                         |
      | a service "cart" with the hosting "embedded"                    |
      | a service "cart" with the hosting "embedded" and a database of its own |
      | a service "cart" with the hosting "process"                     |
      | a service "cart" with the hosting "wasm"                        |
