package com.thinkmorestupidless.ankka.proxy

/** The proxy's process: `run` returns the exit code, so a test can call it. */
object Main:

  def run(args: Array[String]): Int = 0

  def main(args: Array[String]): Unit = sys.exit(run(args))
