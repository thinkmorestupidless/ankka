Feature: Starting a web-hosted service from the platform's template
  A developer starts an interface from the template the platform gives, and has a web-hosted service
  that passes its tests, runs on their machine and is deployed, with nothing written by hand. The
  shopping cart sample has an interface made the same way.

  Scenario: a web-hosted service started from the template passes its tests
    Given a developer with nothing written
    When the developer starts a web-hosted service "shop-web" from the template
    Then every test of "shop-web" passes and none is left out
    And "shop-web" has a descriptor with a mount

  Scenario: a web-hosted service started from the template runs beside a service on the same machine
    Given a web-hosted service "shop-web" started from the template
    And a service "backend" running on the developer's machine
    When the developer runs "shop-web" on that machine
    Then a browser is shown the interface of "shop-web"
    And the interface shows what "backend" answered a request under the mount
    And the interface shows what "backend" answered a call the process made at the calling address

  Scenario: a web-hosted service started from the template is deployed to a local platform
    Given a web-hosted service "shop-web" started from the template
    And a local platform
    When the developer applies the descriptor of "shop-web"
    Then "shop-web" is ready

  Scenario: the shopping cart sample has an interface
    Given a local platform with the sample "shopping-cart" deployed
    When a person uses the interface of the sample in a browser
    Then the browser shows what the service "cart" of the sample answered a request under a mount
    And the browser shows what the service "cart" of the sample answered a call the process made
    And the service "cart" of the sample has no hostname
    And the service "cart" of the sample is not exposed
