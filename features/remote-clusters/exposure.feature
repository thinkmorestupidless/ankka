Feature: Exposing a service on a remote cluster
  An exposed service on a remote cluster answers at a hostname under the cluster's domain, through
  that cluster's gateway, with a certificate from that cluster's wildcard. The control plane derives
  the hostname from the cluster's domain and provisions nothing for it; the cluster's address, its
  DNS and its authority are its owner's.

  Background:
    Given an installation whose base domain is "example.com"
    And a remote cluster "pi" with the domain "pi.example.com" that is tethered
    And a project "shop" placed on "pi"

  Scenario: an exposed service on a remote cluster answers at a hostname under the cluster's domain
    Given a deployed service "cart" in "shop" that is not exposed
    When a member exposes "cart"
    Then the member is shown the hostname "cart-shop.pi.example.com"
    And a request to "cart-shop.pi.example.com" that reaches the gateway of "pi" is answered by "cart"
    And the certificate the request is answered with is the wildcard certificate of "pi"

  Scenario: an exposed service on a remote cluster has its route on that cluster's gateway and on no other
    Given a deployed service "cart" in "shop" that is exposed
    Then the route of "cart" is on the gateway of "pi"
    And no route of "cart" is on the gateway of the home cluster
    And a request to "cart-shop.example.com" reaches no instance of "cart"

  Scenario: the control plane of a remote cluster's installation answers under the base domain alone
    When a member calls the control plane at the hostname of the control plane under "example.com"
    Then the control plane answers
    And nothing answers at the hostname the control plane would have under "pi.example.com"

  Scenario: a service on a remote cluster cannot be granted to a project on another cluster
    Given a project "analytics" placed on the home cluster
    When a member of "shop" grants the route "orders" of "cart" to "analytics"
    Then the grant is refused, saying that "shop" and "analytics" are on different clusters
