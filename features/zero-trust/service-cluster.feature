Feature: A service cluster that nothing else can join
  The instances of a deployed service join their service cluster over proven connections, each
  showing the certificate the platform issued the service. An instance refuses anything that cannot
  show that certificate, and nothing outside the service's project can connect to where its
  instances talk to each other. The certificate is renewed every few hours without restarting
  anything.

  Scenario: the instances of a service join one service cluster over proven connections
    Given a service "cart" deployed with "3" instances
    When the instances of "cart" start
    Then they form one service cluster
    And every connection between them is proven by the certificate of "cart" at both ends

  Scenario: a workload outside a service's project cannot connect to where its instances talk to each other
    Given a deployed service "cart" in the project "shop"
    And a workload in the project "finance"
    When the workload connects to an instance of "cart" where its service cluster talks
    Then the connection is refused before anything is exchanged

  Scenario Outline: a workload that cannot show the service's certificate cannot join its service cluster
    Given a deployed service "cart" in the project "shop"
    And a workload in the project "shop" that shows <certificate>
    When the workload tries to join the service cluster of "cart"
    Then the workload is refused
    And the service cluster of "cart" is unchanged

    Examples:
      | certificate                          |
      | no certificate                       |
      | the certificate of the service "orders" |

  Scenario: renewing a service's certificate restarts nothing and refuses nothing
    Given a deployed service "cart" with "3" instances
    When the platform renews the certificate of "cart"
    Then no instance of "cart" is restarted
    And no instance of "cart" leaves its service cluster
    And every request sent to "cart" is answered

  Scenario: an instance of a new version joins the service cluster by showing the service's certificate
    Given a deployed service "cart" with "3" instances
    When a member applies the descriptor of "cart" with a new image
    Then each new instance joins the service cluster of "cart" over connections proven by the certificate of "cart"
    And every request sent to "cart" is answered

  Scenario: instances that cannot show a certificate are stopped before the new ones start, and the status says so
    Given a deployed service "cart" whose instances run an image that cannot show a certificate
    When a member applies the descriptor of "cart" with an image that can
    Then every instance of "cart" is ready with the new image
    And no instance of the old image ran beside an instance of the new one
    And the status of "cart" says that its instances were stopped before the new ones started
