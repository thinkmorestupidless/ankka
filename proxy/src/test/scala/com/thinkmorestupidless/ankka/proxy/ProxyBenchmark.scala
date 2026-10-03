package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{ProxySettings, StandInProcess}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{ServerSocket, URI}
import java.nio.file.{Files, Path}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What the proxy costs a request, and what it holds in memory (research R19, SC-007): the real
 * `Main` in a JVM of its own, started with the image's flags and a 192Mi ceiling, in front of a
 * stand-in process on loopback. 10,000 requests through the proxy, over mutual TLS as the gateway,
 * against 10,000 straight to the process; the medians, their difference, and the proxy's resident
 * memory after the run. It asserts the criterion: under 5 ms added, and resident memory within the
 * allotment.
 *
 * sbt -Dankka.benchmarks=on 'proxy/testOnly *ProxyBenchmark'
 */
class ProxyBenchmark extends munit.FunSuite:

  override def munitIgnore: Boolean   = !sys.props.get("ankka.benchmarks").contains("on")
  override val munitTimeout: Duration = 10.minutes

  private val Requests  = 10_000
  private val Warmup    = 2_000
  private val Allotment = 192L * 1024 * 1024
  private val Allowed   = 5.millis
  private val authority = TestPki.root("proxy-benchmark")

  test("a request through the proxy costs under 5 ms, and the proxy fits its allotment") {
    val process = StandInProcess().start()
    val dir = authority
      .issue(uris = Seq("ankka://bench/web"), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("proxy-benchmark"))
    val port  = freePort()
    val proxy = forkProxy(dir, port, process.port)
    try
      val viaProxy = HttpClient
        .newBuilder()
        .sslContext(presenting(Seq("ankka://gateway")))
        .version(HttpClient.Version.HTTP_1_1)
        .build()
      val direct       = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
      val throughProxy = URI.create(s"https://localhost:$port/bench")
      val straight     = URI.create(s"http://127.0.0.1:${process.port}/bench")
      awaitServing(viaProxy, throughProxy, proxy)

      def time(client: HttpClient, uri: URI, n: Int): Vector[Long] =
        Vector.fill(n) {
          val started = System.nanoTime()
          val response =
            client.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofByteArray())
          val took = System.nanoTime() - started
          assertEquals(response.statusCode(), 200)
          took
        }

      time(direct, straight, Warmup): Unit
      time(viaProxy, throughProxy, Warmup): Unit
      val directTimes = time(direct, straight, Requests)
      val proxyTimes  = time(viaProxy, throughProxy, Requests)
      val resident    = residentBytes(proxy.pid())

      val directMedian            = median(directTimes)
      val proxyMedian             = median(proxyTimes)
      val added                   = proxyMedian - directMedian
      def ms(nanos: Long): String = f"${nanos / 1e6}%.3f ms"
      println(
        s"""ProxyBenchmark: $Requests requests each
           |  direct             ${ms(directMedian)} (p99 ${ms(percentile(directTimes, 0.99))})
           |  through the proxy  ${ms(proxyMedian)} (p99 ${ms(percentile(proxyTimes, 0.99))})
           |  added              ${ms(added)}
           |  proxy resident     ${resident / (1024 * 1024)} MiB of ${Allotment / (1024 * 1024)} MiB""".stripMargin
      )
      assert(added < Allowed.toNanos, s"the proxy added ${ms(added)} to the median")
      assert(resident < Allotment, s"the proxy holds ${resident / (1024 * 1024)} MiB")
    finally
      proxy.destroy()
      proxy.waitFor(): Unit
      process.stop()
  }

  /** The proxy's `Main` as the image runs it: the image's JVM flags, the pod's memory as RAM. */
  private def forkProxy(serviceDirectory: Path, port: Int, processPort: Int): Process =
    val java = Path.of(sys.props("java.home"), "bin", "java").toString
    val command = Vector(
      java,
      "-XX:+UseSerialGC",
      "-XX:TieredStopAtLevel=1",
      "-XX:ActiveProcessorCount=1",
      "-XX:MaxRAM=192m",
      "-Djdk.httpclient.allowRestrictedHeaders=host",
      s"-D${Main.ServiceDirectoryProperty}=$serviceDirectory",
      "-cp",
      sys.props("java.class.path"),
      "com.thinkmorestupidless.ankka.proxy.Main"
    )
    val builder = new ProcessBuilder(command.asJava).inheritIO()
    val env     = builder.environment()
    env.put(ProxySettings.Variables.Project, "bench"): Unit
    env.put(ProxySettings.Variables.Service, "web"): Unit
    env.put(ProxySettings.Variables.Port, port.toString): Unit
    env.put(ProxySettings.Variables.ProcessPort, processPort.toString): Unit
    builder.start()

  private def awaitServing(client: HttpClient, uri: URI, proxy: Process): Unit =
    val deadline = System.nanoTime() + 60.seconds.toNanos
    var serving  = false
    while !serving && System.nanoTime() < deadline do
      assert(proxy.isAlive, s"the proxy exited ${Try(proxy.exitValue()).getOrElse(-1)}")
      serving =
        Try(client.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.discarding())).toOption
          .exists(_.statusCode() == 200)
      if !serving then Thread.sleep(200)
    assert(serving, "the proxy did not serve within 60 seconds")

  /** The process's resident set, from `ps`, which reports it in KiB on macOS and Linux alike. */
  private def residentBytes(pid: Long): Long =
    val ps   = new ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString).start()
    val text = String(ps.getInputStream.readAllBytes()).trim
    ps.waitFor(): Unit
    text.toLong * 1024

  private def presenting(uris: Seq[String]): SSLContext =
    val dir = authority
      .issue(uris = uris)
      .writeTo(Files.createTempDirectory("proxy-benchmark-client"))
    Files.writeString(dir.resolve("ca.crt"), authority.pem): Unit
    RotatingTls(dir, 1.minute).sslContext

  private def freePort(): Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private def median(times: Vector[Long]): Long = percentile(times, 0.5)

  private def percentile(times: Vector[Long], p: Double): Long =
    val sorted = times.sorted
    sorted(((sorted.size - 1) * p).round.toInt)
