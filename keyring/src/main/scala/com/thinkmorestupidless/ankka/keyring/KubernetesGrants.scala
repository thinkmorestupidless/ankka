package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.Codecs
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.KeyStore as JKeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * Every project's grants from the cluster (feature 042): each project namespace's `ankka-project`
 * ConfigMap, listed across namespaces with the keyring's service account, which may list those and
 * nothing else. Read again at most once per `interval`; a list that fails keeps the grants last
 * read, as a service's `GrantsFile` does.
 */
final class KubernetesGrants(
    list: () => String,
    interval: FiniteDuration = 30.seconds,
    clock: () => Long = () => System.nanoTime()
) extends ProjectGrants:

  private val log  = LoggerFactory.getLogger(classOf[KubernetesGrants])
  private val held = AtomicReference[(Long, Map[String, Vector[ProjectGrants.Grant]])](
    (Long.MinValue, Map.empty)
  )

  def of(project: String): Vector[ProjectGrants.Grant] =
    val (at, grants) = held.get
    val now          = clock()
    if at == Long.MinValue || now - at >= interval.toNanos then
      try held.set(now -> KubernetesGrants.parse(list()))
      catch
        case NonFatal(e) =>
          log.warn(
            s"the projects' grants could not be listed; keeping those last read: ${e.getMessage}"
          )
          held.set(now -> grants)
    held.get._2.getOrElse(project, Vector.empty)

object KubernetesGrants:

  private final case class Meta(namespace: String = "", name: String = "")
  private final case class ConfigMap(metadata: Meta = Meta(), data: Map[String, String] = Map.empty)
  private final case class ConfigMapList(items: Vector[ConfigMap] = Vector.empty)
  private given JsonValueCodec[ConfigMapList] = Codecs.make[ConfigMapList]

  private final case class Rendered(project: String = "")
  private given JsonValueCodec[Rendered] = Codecs.make[Rendered]

  /**
   * A `ConfigMapList`'s grants, by the project each file names. A file that cannot be read is
   * skipped.
   */
  def parse(json: String): Map[String, Vector[ProjectGrants.Grant]] =
    readFromString[ConfigMapList](json).items.flatMap { cm =>
      cm.data.get("grants.json").flatMap { text =>
        try
          val project = readFromString[Rendered](text).project
          Option.when(project.nonEmpty)(project -> ProjectGrants.parse(text.getBytes(UTF_8)))
        catch case NonFatal(_) => None
      }
    }.toMap

  private val ServiceAccount = Path.of("/var/run/secrets/kubernetes.io/serviceaccount")

  /** The cluster's API server, as the pod's own service account. */
  def inCluster(interval: FiniteDuration = 30.seconds): KubernetesGrants =
    val host = sys.env.getOrElse("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc")
    val port = sys.env.getOrElse("KUBERNETES_SERVICE_PORT", "443")
    val ca   = CertificateFactory
      .getInstance("X.509")
      .generateCertificate(
        ByteArrayInputStream(Files.readAllBytes(ServiceAccount.resolve("ca.crt")))
      )
    val trust = JKeyStore.getInstance(JKeyStore.getDefaultType)
    trust.load(null, null)
    trust.setCertificateEntry("cluster", ca)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trust)
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(null, tmf.getTrustManagers, null)
    val http =
      HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build()
    val url =
      s"https://$host:$port/api/v1/configmaps?fieldSelector=metadata.name%3Dankka-project"
    KubernetesGrants(
      () =>
        // Read on every list: the kubelet rotates the projected token.
        val token    = Files.readString(ServiceAccount.resolve("token")).trim
        val response = http.send(
          HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", s"Bearer $token")
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        if response.statusCode != 200 then
          throw IllegalStateException(s"listing the projects' ConfigMaps: ${response.statusCode}")
        response.body
      ,
      interval
    )
