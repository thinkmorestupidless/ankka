package nakka.operator

class MembershipSuite extends munit.FunSuite:

  test("views that share a member are one cluster") {
    assertEquals(Membership.disjointClusters(Seq(Set("a", "b"), Set("b", "c"))), 1)
  }

  test("views that share nothing are separate clusters — the split") {
    assertEquals(Membership.disjointClusters(Seq(Set("a"), Set("b"))), 2)
  }

  test("an empty view is a node waiting, not a cluster") {
    assertEquals(Membership.disjointClusters(Seq(Set("a"), Set.empty)), 1)
    assertEquals(Membership.disjointClusters(Seq(Set.empty, Set.empty)), 0)
  }

  test("a chain of overlaps is one cluster even when the ends share nothing") {
    assertEquals(Membership.disjointClusters(Seq(Set("a", "b"), Set("c", "d"), Set("b", "c"))), 1)
  }

  test("three views of one settled cluster") {
    val all = Set("a", "b", "c")
    assertEquals(Membership.disjointClusters(Seq(all, all, all)), 1)
  }
