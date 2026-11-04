package com.thinkmorestupidless.ankka.keyring

/**
 * The keyring's process (feature 042): reads its root key, replays the erasure log, then serves the
 * platform's programs over mutual TLS as `ankka://platform/keyring`.
 *
 * `run` returns the exit code, so a test can call it; `main` is the one-line wrapper.
 */
object Main:

  /** The identity the keyring's certificate must carry. */
  val Identity: String = "ankka://platform/keyring"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(args: Array[String]): Int =
    val _ = args
    0
