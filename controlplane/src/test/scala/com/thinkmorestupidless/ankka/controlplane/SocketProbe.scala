package com.thinkmorestupidless.ankka.controlplane

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, WebSocket, WebSocketHandshakeException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.concurrent.duration.*

/**
 * A socket opened from outside the cluster, through the gateway, by the JDK's own client.
 *
 * It runs as a subprocess because the hostname must resolve to the mapped port, and
 * `-Djdk.net.hosts.file` changes name resolution for a whole JVM. It prints one line per event —
 * `opened <subprotocol>`, `refused <status>`, `frame <text>`, `closed <code> <reason>`, `cutoff
 * <error>` — and reads commands from standard input: `send <text>` and `close`.
 */
object SocketProbe:

  def main(args: Array[String]): Unit =
    val Array(url, ca, protocols) = args.padTo(3, "")
    val out                       = PrintStream(System.out, true, StandardCharsets.UTF_8)
    val client = HttpClient.newBuilder().sslContext(trusting(Paths.get(ca))).build()
    val buffer = StringBuilder()
    val listener = new WebSocket.Listener:
      override def onOpen(ws: WebSocket): Unit = ws.request(Long.MaxValue)
      override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
        buffer.append(data)
        if last then
          out.println(s"frame ${buffer.result()}")
          buffer.clear()
        CompletableFuture.completedFuture(null)
      override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
        out.println(s"closed $code $reason")
        CompletableFuture.completedFuture(null)
      override def onError(ws: WebSocket, error: Throwable): Unit =
        out.println(s"cutoff ${error.getClass.getSimpleName}: ${error.getMessage}")
    val builder = client.newWebSocketBuilder()
    protocols.split(',').filter(_.nonEmpty).toList match
      case first :: rest => builder.subprotocols(first, rest*)
      case Nil           => ()
    val ws =
      try builder.buildAsync(URI.create(url), listener).join()
      catch
        case e: CompletionException =>
          e.getCause match
            case refused: WebSocketHandshakeException =>
              out.println(s"refused ${refused.getResponse.statusCode}")
            case other =>
              out.println(s"cutoff ${other.getClass.getSimpleName}: ${other.getMessage}")
          sys.exit(0)
    out.println(s"opened ${Option(ws.getSubprotocol).filter(_.nonEmpty).getOrElse("none")}")
    val in = BufferedReader(InputStreamReader(System.in, StandardCharsets.UTF_8))
    Iterator.continually(in.readLine()).takeWhile(_ != null).foreach {
      case line if line.startsWith("send ") => ws.sendText(line.drop(5), true).join(): Unit
      case "close" => ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join(): Unit
      case _       => ()
    }

  private def trusting(ca: Path): SSLContext =
    val certificate = CertificateFactory
      .getInstance("X.509")
      .generateCertificate(Files.newInputStream(ca))
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, null)
    store.setCertificateEntry("ca", certificate)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trust.init(store)
    val context = SSLContext.getInstance("TLS")
    context.init(null, trust.getTrustManagers, null)
    context

  /** One running probe, as a test drives it. */
  final class Running private[SocketProbe] (process: Process):
    private val events = LinkedBlockingQueue[String]()
    private val input  = PrintStream(process.getOutputStream, true, StandardCharsets.UTF_8)
    private val reader = Thread(() =>
      val lines = BufferedReader(InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      Iterator.continually(lines.readLine()).takeWhile(_ != null).foreach(events.put)
    )
    reader.setDaemon(true)
    reader.start()

    /** The next event the probe printed, failing when there is none within `within`. */
    def next(within: FiniteDuration = 20.seconds): String =
      Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))
        .getOrElse(throw AssertionError(s"the probe printed nothing within $within"))

    /** Whether nothing at all arrives within `within`. */
    def quietFor(within: FiniteDuration): Option[String] =
      Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))

    def send(text: String): Unit = input.println(s"send $text")
    def close(): Unit            = input.println("close")
    def stop(): Unit             = process.destroyForcibly(): Unit

  /** Starts a probe for `url`, with `hostname` resolving to loopback, trusting `ca`. */
  def start(hostname: String, url: String, ca: Path, protocols: Seq[String] = Nil): Running =
    val hosts = Files.createTempFile("ankka-socket-hosts", ".txt")
    Files.writeString(hosts, s"127.0.0.1 $hostname\n")
    val java = Paths.get(sys.props("java.home"), "bin", "java").toString
    val command = Vector(
      java,
      s"-Djdk.net.hosts.file=$hosts",
      "-cp",
      sys.props("java.class.path"),
      "com.thinkmorestupidless.ankka.controlplane.SocketProbe",
      url,
      ca.toString,
      protocols.mkString(",")
    )
    Running(ProcessBuilder(command*).redirectErrorStream(false).start())
