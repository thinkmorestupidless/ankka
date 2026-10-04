Feature: Running a web-hosted service on a developer's machine
  A developer runs the process of a web-hosted service on their own machine beside the services it uses,
  with the proxy running there too. The process calls services and the browser reaches mounts at
  the same paths as when the web-hosted service is deployed, so nothing in the interface is written
  for one place and not the other.

  Background:
    Given a developer running the process of the web-hosted service "web" on their own machine
    And the proxy running on that machine with the descriptor of "web"

  Scenario: the process calls a service running on the same machine by name
    Given the service "cart" running on that machine
    When the process calls "cart" at the calling address
    Then "cart" is given the call

  Scenario: mounts answer at the same paths on a developer's machine
    Given the service "cart" running on that machine
    And the descriptor of "web" has "cart" mounted at "/backend/cart"
    When a browser sends a request for "/backend/cart/carts/c1" to the proxy
    Then "cart" is given a request for "/carts/c1"

  Scenario: a request outside every mount reaches the developer's process
    Given the descriptor of "web" has "cart" mounted at "/backend/cart"
    When a browser sends a request for "/about" to the proxy
    Then the process is given a request for "/about"

  Scenario: a service that is not running is named in the answer
    Given no service "cart" running on that machine
    When the process calls "cart" at the calling address
    Then the process is told that there is no service "cart" running

  Scenario: on a developer's machine every request came from that machine
    When a browser sends a request to the proxy
    Then the process is told that the request came from the machine it runs on
