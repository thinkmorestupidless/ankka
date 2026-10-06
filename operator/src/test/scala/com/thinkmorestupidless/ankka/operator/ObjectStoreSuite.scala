package com.thinkmorestupidless.ankka.operator

/** What the object store's values may print (feature 034). */
class ObjectStoreSuite extends munit.FunSuite:

  test("an issued key prints its id and never its secret") {
    val printed = IssuedKey("GK1", "s3cret-value").toString
    assert(printed.contains("GK1"), printed)
    assert(!printed.contains("s3cret-value"), printed)
  }

  test("the store's settings print no token") {
    val printed = ObjectStoreSettings(
      "http://admin",
      "the-admin-token",
      "http://s3",
      "garage",
      ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
    ).toString
    assert(!printed.contains("the-admin-token"), printed)
  }
