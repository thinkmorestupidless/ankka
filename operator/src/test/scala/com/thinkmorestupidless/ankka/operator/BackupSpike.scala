package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder, SecretBuilder}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The facts feature 041's rendering rests on, shown against a real CNPG and a real Barman Cloud
 * plugin before anything is built on them (research S1, S2, S3). Each finding is printed as
 * `FINDING …` and written into research.md's *Verified during implementation* by hand.
 *
 *   - S1: a running project database gains the plugin and `archive_timeout`, and whether its pod
 *     rolls; a base backup through the plugin completes.
 *   - S2: `exec` of `psql` in the database's own pod answers; `env` beats `envFrom` for one key.
 *   - S3: a cluster of two databases restored to a moment, with `database: postgres`, holds both
 *     databases and both roles, the earlier rows and not the later, and no `app`.
 *
 * sbt -Dankka.spikes=on 'operator/testOnly *BackupSpike'
 */
class BackupSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")
  override val munitTimeout         = 30.minutes

  private val Project   = "spike"
  private val Namespace = s"ankka-$Project"
  private val Bucket    = s"platform.backups.$Project"

  private var k3s: K3sContainer                 = null
  private var client: KubernetesClient          = null
  private var store: ObjectStoreStack.Installed = null
  private var startedAt: String                 = ""
  private var noted: Instant                    = Instant.EPOCH

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.35.1-k3s1"))
      k3s.start()
      client = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      PkiStack.install(k3s, client)
      BackupStack.install(k3s, client)
      store = ObjectStoreStack.install(k3s, client, PkiStack.repoRoot)
      client
        .namespaces()
        .resource(
          new NamespaceBuilder()
            .withMetadata(
              new ObjectMetaBuilder()
                .withName(Namespace)
                .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
                .build()
            )
            .build()
        )
        .serverSideApply(): Unit
      CnpgRendering.projectAuthority(Namespace).foreach(r => client.resource(r).serverSideApply())
      client
        .resource(CnpgRendering.projectCluster(Project, Settings.default))
        .serverSideApply(): Unit
      awaitHealthy("ankka-db", 10.minutes)
      startedAt = podStartTime("ankka-db-1")

  override def afterAll(): Unit =
    if store != null then store.close()
    if client != null then client.close()
    if k3s != null then k3s.stop()

  test("S1: the plugin and archive_timeout on a running cluster, and whether its pod rolls") {
    val garage = new GarageStore(store.settings.adminUrl, store.settings.adminToken)
    val bucket = garage.createBucket(Bucket)
    val key    = garage.createKey(Bucket)
    garage.allow(bucket.id, key.accessKeyId)
    client
      .secrets()
      .inNamespace(Namespace)
      .resource(
        new SecretBuilder()
          .withMetadata(
            new ObjectMetaBuilder().withName("ankka-db-backups").withNamespace(Namespace).build()
          )
          .withStringData(
            Map(
              "ACCESS_KEY_ID"     -> key.accessKeyId,
              "ACCESS_SECRET_KEY" -> key.secretAccessKey,
              "REGION"            -> "garage"
            ).asJava
          )
          .build()
      )
      .create(): Unit
    apply(s"""apiVersion: barmancloud.cnpg.io/v1
             |kind: ObjectStore
             |metadata: { name: ankka-backups, namespace: $Namespace }
             |spec:
             |  retentionPolicy: "30d"
             |  configuration:
             |    destinationPath: s3://$Bucket/
             |    endpointURL: ${ObjectStoreStack.InClusterEndpoint}
             |    s3Credentials:
             |      accessKeyId: { name: ankka-db-backups, key: ACCESS_KEY_ID }
             |      secretAccessKey: { name: ankka-db-backups, key: ACCESS_SECRET_KEY }
             |      region: { name: ankka-db-backups, key: REGION }
             |    wal: { compression: lz4, maxParallel: 2 }
             |    data: { compression: lz4, jobs: 2 }
             |""".stripMargin)
    apply(
      s"""apiVersion: postgresql.cnpg.io/v1
             |kind: Cluster
             |metadata: { name: ankka-db, namespace: $Namespace }
             |spec:
             |  plugins:
             |    - name: barman-cloud.cloudnative-pg.io
             |      isWALArchiver: true
             |      parameters: { barmanObjectName: ankka-backups, serverName: ankka-db }
             |  postgresql:
             |    parameters: { archive_timeout: "60s" }
             |""".stripMargin,
      manager = "spike"
    )
    Thread.sleep(20_000)
    awaitHealthy("ankka-db", 10.minutes)
    val after = podStartTime("ankka-db-1")
    println(
      s"FINDING S1: pod startTime before=$startedAt after=$after rolled=${after != startedAt}"
    )
    println(
      s"FINDING S1: containers now ${PkiStack.jsonPath(k3s, "-n", Namespace, "pod", "ankka-db-1", "{.spec.containers[*].name}")}"
    )
    waitFor(5.minutes, "ContinuousArchiving is True") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        Namespace,
        "cluster",
        "ankka-db",
        """{.status.conditions[?(@.type=="ContinuousArchiving")].status}"""
      ) == "True"
    }
    println(
      s"FINDING S1: ContinuousArchiving condition ${PkiStack.jsonPath(k3s, "-n", Namespace, "cluster", "ankka-db", """{.status.conditions[?(@.type=="ContinuousArchiving")]}""")}"
    )
    apply(s"""apiVersion: postgresql.cnpg.io/v1
             |kind: Backup
             |metadata: { name: spike-base, namespace: $Namespace }
             |spec:
             |  cluster: { name: ankka-db }
             |  method: plugin
             |  pluginConfiguration: { name: barman-cloud.cloudnative-pg.io }
             |""".stripMargin)
    waitFor(10.minutes, "the base backup completes") {
      PkiStack.jsonPath(
        k3s,
        "-n",
        Namespace,
        "backup",
        "spike-base",
        "{.status.phase}"
      ) == "completed"
    }
    val window = PkiStack.jsonPath(
      k3s,
      "-n",
      Namespace,
      "objectstore",
      "ankka-backups",
      "{.status.serverRecoveryWindow}"
    )
    println(s"FINDING S1: serverRecoveryWindow $window")
    assert(window.contains("ankka-db"), window)
    // Found on the first run: the plugin's sidecar is injected into the instance pods, so adding it
    // restarts each instance once (about 20 seconds for one). Research R7 is amended to say so; the
    // spike records it rather than failing on it.
    println(s"FINDING S1: adding the plugin restarted the database pod: ${after != startedAt}")
  }

  test("S2: psql by exec in the database's pod, and env beats envFrom") {
    assertEquals(psql("postgres", "select 1").trim, "1")
    client
      .secrets()
      .inNamespace(Namespace)
      .resource(
        new SecretBuilder()
          .withMetadata(
            new ObjectMetaBuilder().withName("spike-db").withNamespace(Namespace).build()
          )
          .withStringData(Map("ANKKA_DB_HOST" -> "from-secret").asJava)
          .build()
      )
      .create(): Unit
    apply(s"""apiVersion: v1
             |kind: Pod
             |metadata: { name: spike-env, namespace: $Namespace }
             |spec:
             |  restartPolicy: Never
             |  containers:
             |    - name: c
             |      image: busybox:1.36
             |      command: ["sh", "-c", "echo $$ANKKA_DB_HOST; sleep 600"]
             |      envFrom: [{ secretRef: { name: spike-db } }]
             |      env: [{ name: ANKKA_DB_HOST, value: literal }]
             |""".stripMargin)
    waitFor(3.minutes, "the env pod runs") {
      PkiStack.jsonPath(k3s, "-n", Namespace, "pod", "spike-env", "{.status.phase}") == "Running"
    }
    val seen = exec("spike-env", "c", "printenv", "ANKKA_DB_HOST").trim
    println(s"FINDING S2: env over envFrom gives '$seen'")
    assertEquals(seen, "literal")
  }

  test("S3: a two-database cluster restored to a moment") {
    psql("postgres", "create role ra login; create role rb login")
    // CREATE DATABASE cannot run inside the transaction one -c of several statements is.
    psql("postgres", "create database a owner ra")
    psql("postgres", "create database b owner rb")
    psql("a", "create table t(x int); insert into t values (1)")
    psql("b", "create table t(x int); insert into t values (1)")
    Thread.sleep(2_000)
    noted = Instant.parse(
      psql(
        "postgres",
        "select to_char(now() at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"
      ).trim
    )
    Thread.sleep(2_000)
    psql("a", "insert into t values (2)")
    psql("b", "insert into t values (2)")
    psql("postgres", "select pg_switch_wal()")
    // The archive must reach past the target, or recovery has nothing after it to stop at.
    Thread.sleep(90_000)
    val started = System.nanoTime()
    apply(s"""apiVersion: postgresql.cnpg.io/v1
             |kind: Cluster
             |metadata: { name: ankka-db-rspike, namespace: $Namespace }
             |spec:
             |  instances: 1
             |  storage: { size: 1Gi }
             |  certificates: { clientCASecret: ${CnpgRendering.clientCaName}, replicationTLSSecret: ${CnpgRendering.replicationName} }
             |  postgresql: { pg_hba: ["${CnpgRendering.CertificateRule}"] }
             |  managed: { roles: [{ name: ${CnpgRendering.TlsGroup}, login: false, ensure: present }] }
             |  bootstrap:
             |    recovery:
             |      source: line
             |      database: postgres
             |      owner: postgres
             |      recoveryTarget: { targetTime: "$noted" }
             |  externalClusters:
             |    - name: line
             |      plugin:
             |        name: barman-cloud.cloudnative-pg.io
             |        parameters: { barmanObjectName: ankka-backups, serverName: ankka-db }
             |""".stripMargin)
    awaitHealthy("ankka-db-rspike", 15.minutes)
    println(
      s"FINDING S3: restore took ${(System.nanoTime() - started) / 1_000_000_000}s, target $noted"
    )
    val databases =
      psql("postgres", "select datname from pg_database order by 1", pod = "ankka-db-rspike-1")
    val roles =
      psql("postgres", "select rolname from pg_roles order by 1", pod = "ankka-db-rspike-1")
    println(
      s"FINDING S3: databases ${databases.linesIterator.mkString(",")}; roles ${roles.linesIterator.filterNot(_.startsWith("pg_")).mkString(",")}"
    )
    assert(
      databases.linesIterator.contains("a") && databases.linesIterator.contains("b"),
      databases
    )
    // Exactly the source's databases: `app` is there only because a project database without a
    // bootstrap is given CNPG's default one, and recovery with `database: postgres` adds nothing.
    assertEquals(databases, psql("postgres", "select datname from pg_database order by 1"))
    assert(
      roles.linesIterator.contains("ra") && roles.linesIterator.contains(CnpgRendering.TlsGroup),
      roles
    )
    assertEquals(
      psql(
        "a",
        "select string_agg(x::text, ',' order by x) from t",
        pod = "ankka-db-rspike-1"
      ).trim,
      "1"
    )
    assertEquals(
      psql(
        "b",
        "select string_agg(x::text, ',' order by x) from t",
        pod = "ankka-db-rspike-1"
      ).trim,
      "1"
    )
    assertEquals(psql("a", "select string_agg(x::text, ',' order by x) from t").trim, "1,2")
  }

  test("S3: a moment after the archive's last commit fails the restore") {
    val later = Instant.now().plusSeconds(3600)
    apply(s"""apiVersion: postgresql.cnpg.io/v1
             |kind: Cluster
             |metadata: { name: ankka-db-rlate, namespace: $Namespace }
             |spec:
             |  instances: 1
             |  storage: { size: 1Gi }
             |  bootstrap:
             |    recovery:
             |      source: line
             |      database: postgres
             |      owner: postgres
             |      recoveryTarget: { targetTime: "$later" }
             |  externalClusters:
             |    - name: line
             |      plugin:
             |        name: barman-cloud.cloudnative-pg.io
             |        parameters: { barmanObjectName: ankka-backups, serverName: ankka-db }
             |""".stripMargin)
    Thread.sleep(240_000)
    val phase =
      PkiStack.jsonPath(k3s, "-n", Namespace, "cluster", "ankka-db-rlate", "{.status.phase}")
    val reason =
      PkiStack.jsonPath(k3s, "-n", Namespace, "cluster", "ankka-db-rlate", "{.status.phaseReason}")
    println(s"FINDING S3: a target after the archive leaves phase='$phase' reason='$reason'")
    assertNotEquals(phase, "Cluster in healthy state")
  }

  private def apply(yaml: String, manager: String = "ankka-operator"): Unit =
    k3s.copyFileToContainer(
      Transferable.of(yaml.getBytes(StandardCharsets.UTF_8)),
      "/tmp/spike.yaml"
    )
    PkiStack.kubectl(
      k3s,
      "apply",
      "--server-side",
      "--force-conflicts",
      s"--field-manager=$manager",
      "-f",
      "/tmp/spike.yaml"
    ): Unit

  private def psql(database: String, sql: String, pod: String = "ankka-db-1"): String =
    exec(
      pod,
      "postgres",
      "psql",
      "-U",
      "postgres",
      "-d",
      database,
      "-tA",
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      sql
    )

  private def exec(pod: String, container: String, command: String*): String =
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val watch = client
      .pods()
      .inNamespace(Namespace)
      .withName(pod)
      .inContainer(container)
      .writingOutput(out)
      .writingError(err)
      .exec(command*)
    try
      val code = watch.exitCode().get(120, TimeUnit.SECONDS)
      if code != 0 then
        throw new AssertionError(
          s"${command.mkString(" ")} exited $code: ${err.toString(StandardCharsets.UTF_8)}"
        )
      out.toString(StandardCharsets.UTF_8)
    finally watch.close()

  private def podStartTime(pod: String): String =
    PkiStack.jsonPath(k3s, "-n", Namespace, "pod", pod, "{.status.startTime}")

  private def awaitHealthy(cluster: String, timeout: FiniteDuration): Unit =
    waitFor(timeout, s"$cluster is healthy") {
      PkiStack.jsonPath(k3s, "-n", Namespace, "cluster", cluster, "{.status.phase}") ==
        "Cluster in healthy state"
    }

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Exception => false
      if !passed then Thread.sleep(2000)
    if !passed then throw new AssertionError(s"$what did not happen within $timeout")
