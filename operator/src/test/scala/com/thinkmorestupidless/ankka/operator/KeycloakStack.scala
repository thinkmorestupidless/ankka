package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The identity provider a suite needs to prove authentication end to end (feature 008): the
 * Keycloak operator from the same pinned manifests `kustomization/components/keycloak-operator`
 * references, then the platform's `keycloak` component with the base domain filled in, then the
 * realm imported from the one `realm-import.json`, with the suite's namespace in place of the
 * component's. A manifest this passes with is the manifest that ships.
 *
 * Shared by the control plane's suites through `test->test`, like `GatewayStack`.
 */
object KeycloakStack:

  private def version: String =
    KeycloakAdmin.image.substring(KeycloakAdmin.image.lastIndexOf(':') + 1)

  private def manifest(name: String): String =
    s"https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/$version/kubernetes/$name"

  /** The Keycloak resource's Service, created by the operator as `<name>-service`. */
  val ServiceName = "ankka-keycloak-service"

  /**
   * The realm itself, as JSON, out of the shipped `KeycloakRealmImport` — `.spec.realm` of
   * `kustomization/components/keycloak/realm-import.json`, which is the one copy and is JSON for
   * exactly this reason. What docker-compose does with jq, for a suite that starts Keycloak on its
   * own or renders the import into a namespace of its choosing.
   */
  def realm(repoRoot: Path): String =
    val file  = repoRoot.resolve("kustomization/components/keycloak/realm-import.json")
    val tree  = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile)
    val realm = tree.path("spec").path("realm")
    require(realm.isObject, s"$file carries no spec.realm")
    realm.toPrettyString
  val Namespace = "ankka-auth"

  /**
   * The operator's image, which the operator manifests name; the instance runs
   * `KeycloakAdmin.image`.
   */
  def operatorImage: String = s"quay.io/keycloak/keycloak-operator:$version"

  def install(
      k3s: K3sContainer,
      k8s: KubernetesClient,
      repoRoot: Path,
      baseDomain: String,
      httpsPort: Int
  ): Unit =
    // Both images from the local Docker daemon when they are there, exactly as the ankka images
    // are: a cold k3s node pulling two Quarkus images from quay.io took longer than any sensible
    // rollout wait. When they are not local, the node pulls them itself and the waits below allow
    // for it.
    for image <- Vector(operatorImage, KeycloakAdmin.image) do
      try ClusterImages.importInto(k3s, image)
      catch case _: IllegalStateException => ()

    val operatorComponent = repoRoot.resolve("kustomization/components/keycloak-operator")
    applyOnNode(
      k3s,
      Files.readString(operatorComponent.resolve("namespace.yaml")),
      "keycloak-namespace"
    )
    // All four CRDs: the operator will not start while any kind it has a controller for is missing.
    for crd <- Vector(
        "keycloaks.k8s.keycloak.org-v1.yml",
        "keycloakrealmimports.k8s.keycloak.org-v1.yml",
        "keycloakoidcclients.k8s.keycloak.org-v1.yml",
        "keycloaksamlclients.k8s.keycloak.org-v1.yml"
      )
    do exec(k3s, "kubectl", "apply", "--server-side", "--force-conflicts", "-f", manifest(crd))
    exec(
      k3s,
      "kubectl",
      "apply",
      "--server-side",
      "--force-conflicts",
      "-n",
      Namespace,
      "-f",
      manifest("kubernetes.yml")
    )
    // The one thing the component's namespace transformer has to patch by hand upstream too.
    exec(
      k3s,
      "kubectl",
      "patch",
      "clusterrolebinding",
      "keycloak-operator-clusterrole-binding",
      "--type=json",
      "-p",
      s"""[{"op":"replace","path":"/subjects/0/namespace","value":"$Namespace"}]"""
    )
    waitForRollout(k8s, Namespace, "keycloak-operator")

    val component = repoRoot.resolve("kustomization/components/keycloak")
    // The base domain and the port clients reach the gateway on, filled in as the overlay would:
    // the route forwards the port so Keycloak's issuer names it (research R3).
    val combined = Vector("postgres.yaml", "admin-secret.yaml", "keycloak.yaml", "httproute.yaml")
      .map(file =>
        Files
          .readString(component.resolve(file))
          .replace("BASE_DOMAIN", baseDomain)
          .replace("value: \"8443\"", s"value: \"$httpsPort\"")
      )
      .mkString("\n---\n")
    applyOnNode(k3s, combined, "keycloak")

    // The same waits deploy-local.sh does, with the same tool: kubectl on the node.
    waitFor(300.seconds, "Keycloak's database is ready") {
      nodeJsonPath(
        k3s,
        "-n",
        Namespace,
        "cluster",
        "ankka-keycloak-db",
        "{.status.readyInstances}"
      ) == "1"
    }
    waitFor(420.seconds, "the Keycloak instance is Ready") {
      nodeJsonPath(
        k3s,
        "-n",
        Namespace,
        "keycloak",
        "ankka-keycloak",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }

    val realm = KeycloakStack.realm(repoRoot)
    val realmImport =
      s"""apiVersion: k8s.keycloak.org/v2alpha1
         |kind: KeycloakRealmImport
         |metadata:
         |  name: ankka-realm
         |  namespace: $Namespace
         |spec:
         |  keycloakCRName: ankka-keycloak
         |  realm:
         |""".stripMargin + realm.linesIterator.map(line => "    " + line).mkString("\n") + "\n"
    applyOnNode(k3s, realmImport, "realm-import")
    waitFor(300.seconds, "the realm import is Done") {
      nodeJsonPath(
        k3s,
        "-n",
        Namespace,
        "keycloakrealmimport",
        "ankka-realm",
        """{.status.conditions[?(@.type=="Done")].status}"""
      ) == "True"
    }

  /**
   * A confidential client with a service account, through `kcadm.sh` inside the pod — exactly the
   * commands deploy-local.sh and docker-compose's init run, so the three cannot drift.
   */
  def createServiceClient(
      k3s: K3sContainer,
      clientId: String,
      secret: String,
      platformAdmin: Boolean
  ): Unit =
    val kcadm = Vector(
      "kubectl",
      "-n",
      Namespace,
      "exec",
      "statefulset/ankka-keycloak",
      "--",
      "/opt/keycloak/bin/kcadm.sh"
    )
    exec(
      k3s,
      (kcadm ++ Vector(
        "config",
        "credentials",
        "--server",
        "http://localhost:8080",
        "--realm",
        "master",
        "--user",
        "admin",
        "--password",
        "admin"
      ))*
    )
    exec(
      k3s,
      (kcadm ++ Vector(
        "create",
        "clients",
        "-r",
        "ankka",
        "-s",
        s"clientId=$clientId",
        "-s",
        s"secret=$secret",
        "-s",
        "publicClient=false",
        "-s",
        "standardFlowEnabled=false",
        "-s",
        "serviceAccountsEnabled=true",
        "-s",
        """defaultClientScopes=["basic","profile","email","roles","ankka-controlplane"]"""
      ))*
    )
    if platformAdmin then
      exec(
        k3s,
        (kcadm ++ Vector(
          "add-roles",
          "-r",
          "ankka",
          "--uusername",
          s"service-account-$clientId",
          "--rolename",
          "platform-admin"
        ))*
      )

  /**
   * Keycloak's plain HTTP port on the host, the way the control plane reaches it inside a cluster:
   * the service address, no gateway, no TLS. A port-forward through the API server stands in for
   * the cluster network, which is fine here because what it carries is a public key set.
   */
  def forwardService(k8s: KubernetesClient): io.fabric8.kubernetes.client.LocalPortForward =
    k8s.services().inNamespace(Namespace).withName(ServiceName).portForward(8080)

  /**
   * A client-credentials token, obtained the way a CI job would: through the gateway, from the
   * host, with the certificate verified against the exported root and `--resolve` standing in for
   * DNS. The token's `iss` is therefore exactly what a real client sees — including the port —
   * which is the thing the control plane's derived issuer has to agree with.
   */
  def mintToken(
      ca: Path,
      baseDomain: String,
      httpsPort: Int,
      clientId: String,
      secret: String
  ): String =
    val host = s"auth.$baseDomain"
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$host:$httpsPort:127.0.0.1",
      "-m",
      "20",
      "-d",
      "grant_type=client_credentials",
      "-d",
      s"client_id=$clientId",
      "-d",
      s"client_secret=$secret",
      s"https://$host:$httpsPort/realms/ankka/protocol/openid-connect/token"
    )
    val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    if process.waitFor() != 0 then throw new AssertionError(s"minting a token failed: $output")
    val token = "\"access_token\":\"([^\"]+)\"".r.findFirstMatchIn(output).map(_.group(1))
    token.getOrElse(throw new AssertionError(s"no access token in: $output"))

  /**
   * The realm's discovery document as a client outside the cluster sees it, through the gateway.
   */
  def discovery(ca: Path, baseDomain: String, httpsPort: Int): String =
    val host = s"auth.$baseDomain"
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$host:$httpsPort:127.0.0.1",
      "-m",
      "20",
      s"https://$host:$httpsPort/realms/ankka/.well-known/openid-configuration"
    )
    val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    if process.waitFor() != 0 then throw new AssertionError(s"discovery failed: $output")
    output

  private def applyOnNode(k3s: K3sContainer, yaml: String, name: String): Unit =
    val path = s"/tmp/ankka-$name.yaml"
    k3s.copyFileToContainer(Transferable.of(yaml.getBytes(StandardCharsets.UTF_8)), path)
    exec(k3s, "kubectl", "apply", "--server-side", "--force-conflicts", "-f", path)

  private def exec(k3s: K3sContainer, args: String*): String =
    val result = k3s.execInContainer(args*)
    if result.getExitCode != 0 then
      throw new AssertionError(
        s"${args.mkString(" ")} failed: ${result.getStderr}\n${result.getStdout}"
      )
    result.getStdout

  private def nodeJsonPath(k3s: K3sContainer, args: String*): String =
    val result = k3s.execInContainer(
      (Vector("kubectl", "get") ++ args.dropRight(1) ++ Vector("-o", s"jsonpath=${args.last}"))*
    )
    result.getStdout.trim

  private def waitForRollout(k8s: KubernetesClient, namespace: String, name: String): Unit =
    try
      waitFor(420.seconds, s"$namespace/$name rolls out") {
        Option(k8s.apps().deployments().inNamespace(namespace).withName(name).get())
          .flatMap(d => Option(d.getStatus))
          .flatMap(s => Option(s.getReadyReplicas))
          .exists(_ > 0)
      }
    catch
      case failure: AssertionError =>
        // Say what the pods were doing, not just that they were not ready: an image that will not
        // pull and a container that crashes look identical from a replica count.
        val pods = k8s.pods().inNamespace(namespace).list().getItems
        val report = pods.asScala
          .map { pod =>
            val phase = Option(pod.getStatus).map(_.getPhase).getOrElse("?")
            val states = Option(pod.getStatus)
              .map(
                _.getContainerStatuses.asScala
                  .map(c =>
                    s"${c.getName}: ready=${c.getReady} restarts=${c.getRestartCount} state=${c.getState}"
                  )
                  .mkString("; ")
              )
              .getOrElse("")
            s"${pod.getMetadata.getName} $phase $states"
          }
          .mkString("\n  ")
        throw new AssertionError(s"${failure.getMessage}\n  $report")

  /** Never swallows the check's exception into "it never happened" (CLAUDE.md's `waitFor` trap). */
  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline                = System.nanoTime() + timeout.toNanos
    var passed                  = false
    var last: Option[Throwable] = None
    while !passed && System.nanoTime() < deadline do
      passed =
        try
          val ok = check
          last = None
          ok
        catch
          case failure: Throwable =>
            last = Some(failure)
            false
      if !passed then Thread.sleep(2000)
    if !passed then
      throw new AssertionError(
        s"$what did not happen within $timeout" + last.fold("")(f => s" (last check failed: $f)")
      )
