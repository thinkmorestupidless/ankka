package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.EntityId
import org.apache.pekko.cluster.{Cluster, MemberStatus}

import scala.concurrent.duration.DurationInt

/** Two nodes of one service on one machine and one database, with no Kubernetes. */
class PeerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("a peer joins the first node's cluster, and each reaches what the other hosts") {
    val kit = AnkkaTestKit.start(Seq(WalletEntity.descriptor, TransferWorkflow.descriptor))
    try
      val peer = kit.startPeer(Nil)
      try
        kit.eventually("two members up") {
          val members = Cluster(kit.service.system).state.members
          Option.when(members.count(_.status == MemberStatus.Up) == 2)(members)
        }
        val wallet = WalletEntity.deposit
        peer.componentClient.forKeyValueEntity(EntityId("w-peer")).call(wallet).invoke(5)
        val balance =
          kit.componentClient
            .forKeyValueEntity(EntityId("w-peer"))
            .call(WalletEntity.balance)
            .invoke()
        assertEquals(balance, 5)
      finally peer.stop()
    finally kit.stop()
  }
