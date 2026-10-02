Feature: Mounting services under a web-hosted service
  A web-hosted service mounts a service of its project under a path. The proxy passes a request under
  that path to the mounted service, which is told that it came from the internet, and passes every
  other request to the process. A browser therefore reaches the services behind an interface at the
  address it was shown the interface from, and none of those services has to be exposed.

  Background:
    Given a service "cart" deployed in the project "shop" whose access rule admits the internet
    And "cart" is not exposed
    And a web-hosted service "web" deployed in the project "shop" with "cart" mounted at "/api/cart"
    And "web" is exposed

  Scenario: a request under a mount reaches the mounted service
    When a browser sends a request for "/api/cart/carts/c1" to "web"
    Then "cart" is given a request for "/carts/c1"
    And "cart" is told that the request came from the internet
    And the browser is given the answer of "cart"

  Scenario Outline: one web-hosted service mounts several services, each under its own path
    Given a service "orders" deployed in the project "shop" whose access rule admits the internet
    And a service "catalogue" deployed in the project "shop" whose access rule admits the internet
    And "web" also has "orders" mounted at "/api/orders" and "catalogue" mounted at "/api/catalogue"
    When a browser sends a request for "<sent>" to "web"
    Then "<service>" is given a request for "<given>"
    And no other mounted service is given a request

    Examples:
      | sent                    | service   | given       |
      | /api/cart/carts/c1      | cart      | /carts/c1   |
      | /api/orders/orders/o1   | orders    | /orders/o1  |
      | /api/catalogue/items/i1 | catalogue | /items/i1   |

  Scenario: a web-hosted service mounted under another is told the address the browser used
    Given a web-hosted service "admin" deployed in the project "shop"
    And "web" also has "admin" mounted at "/admin"
    When a browser sends a request for "/admin/accounts" to the hostname of "web"
    Then the process of "admin" is given a request for "/accounts"
    And the process of "admin" is told that the request came from the internet
    And the process of "admin" is told the hostname of "web" as the address the request was sent to

  Scenario: a request under a mount of a web-hosted service in another project is refused
    Given a web-hosted service "portal" deployed in the project "billing"
    When "portal" passes a request to "cart" as a request under a mount
    Then "portal" is refused
    And "cart" is not told that a request came from the internet

  Scenario: two web-hosted services mount the same service
    Given a web-hosted service "admin" deployed in the project "shop" with "cart" mounted at "/cart"
    And "admin" is exposed
    When a browser sends a request for "/cart/carts/c1" to "admin"
    Then "cart" is given a request for "/carts/c1"
    And a request for "/api/cart/carts/c1" to "web" still reaches "cart"

  Scenario Outline: a request outside every mount reaches the process
    When a browser sends a request for "<path>" to "web"
    Then the process of "web" is given a request for "<path>"
    And no call is sent to "cart"

    Examples:
      | path          |
      | /about        |
      | /api          |
      | /api/cartoons |

  Scenario: a service that admits only the web-hosted service refuses a request under a mount
    Given a service "ledger" deployed in the project "shop" whose access rule admits only "web"
    And "web" also has "ledger" mounted at "/api/ledger"
    When a browser sends a request for "/api/ledger/entries" to "web"
    Then the browser is given the refusal of "ledger"
    And "ledger" answers a call the process of "web" makes at the calling address

  Scenario: a mounted service too old to know a mount refuses a request under one
    Given a service "ledger" deployed in the project "shop", too old to be told that a request came under a mount
    And "web" also has "ledger" mounted at "/api/ledger"
    When a browser sends a request for "/api/ledger/entries" to "web"
    Then the browser is given a refusal
    And "ledger" is not told that a call came from the service "web"

  Scenario Outline: a request under a mount with no service to call is answered by the proxy
    Given "web" also has "ledger" mounted at "/api/ledger"
    And the service "ledger" <state>
    When a browser sends a request for "/api/ledger/entries" to "web"
    Then the proxy answers that "ledger" cannot be reached
    And the process of "web" is given no request

    Examples:
      | state                |
      | does not exist       |
      | serves no requests   |
      | is paused            |

  Scenario: mounts change without another image
    When a member applies the descriptor of "web" with the same image and "cart" mounted at "/cart"
    Then a request for "/cart/carts/c1" to "web" reaches "cart"
    And a request for "/api/cart/carts/c1" to "web" reaches the process
