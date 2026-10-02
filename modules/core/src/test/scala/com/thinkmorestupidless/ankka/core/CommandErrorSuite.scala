package com.thinkmorestupidless.ankka.core

class CommandErrorSuite extends munit.FunSuite:

  private val gone = CommandError("gone", ErrorCode.NotFound)

  test("a rejection is itself") {
    assertEquals(CommandError.from(gone), Some(gone))
  }

  test("a rejection carried as a cause is found") {
    assertEquals(CommandError.from(RuntimeException("transport", gone)), Some(gone))
  }

  test("a rejection two causes deep is not looked for") {
    assertEquals(CommandError.from(RuntimeException("a", RuntimeException("b", gone))), None)
  }

  test("an exception with no cause, and one with an unrelated cause, carry none") {
    assertEquals(CommandError.from(RuntimeException("x")), None)
    assertEquals(CommandError.from(RuntimeException("x", IllegalStateException("y"))), None)
  }
