package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.{
  ConfigMapVolumeSourceBuilder,
  Container,
  ContainerBuilder,
  EnvFromSourceBuilder,
  SecretEnvSourceBuilder,
  Volume,
  VolumeBuilder,
  VolumeMountBuilder
}

/**
 * The container that stands between "a service's database exists" and "a service's database is
 * usable": it waits for the database to accept connections, applies ankka's schema, and closes the
 * default-open `CONNECT` privilege every other role in the project's cluster would otherwise have.
 *
 * See `specs/002-cnpg-database-provisioning/contracts/schema-init.md` for why each of the three
 * steps exists — step 3 in particular is not obvious, and skipping it ships a database that looks
 * isolated in every test that does not specifically check for it (research R9).
 */
object SchemaInit:

  /**
   * `postgres:17-alpine` — already the image feature 001 uses for the control plane's own
   * wait-for-postgres init container, so this adds no new image to pull.
   */
  val Image: String = "postgres:17-alpine"

  val VolumeName: String = "ankka-schema"
  val MountPath: String  = "/schema"

  def container(serviceName: String): Container =
    new ContainerBuilder()
      .withName("ankka-schema")
      .withImage(Image)
      .withEnvFrom(
        new EnvFromSourceBuilder()
          .withSecretRef(
            new SecretEnvSourceBuilder()
              .withName(CnpgRendering.credentialSecretName(serviceName))
              .build()
          )
          .build()
      )
      .withEnv(
        ZeroTrust.Database.Environment.map((n, v) =>
          new io.fabric8.kubernetes.api.model.EnvVarBuilder().withName(n).withValue(v).build()
        )*
      )
      .withVolumeMounts(
        new VolumeMountBuilder()
          .withName(VolumeName)
          .withMountPath(MountPath)
          .withReadOnly(true)
          .build(),
        // The service's own database identity: schema-init logs in as the service, by certificate.
        new VolumeMountBuilder()
          .withName("ankka-database-tls")
          .withMountPath(ZeroTrust.DatabaseMount)
          .withReadOnly(true)
          .build(),
        new VolumeMountBuilder()
          .withName("ankka-database-ca")
          .withMountPath(ZeroTrust.DatabaseCaMount)
          .withReadOnly(true)
          .build()
      )
      .withCommand("sh", "-c", script)
      .build()

  def volume(): Volume =
    new VolumeBuilder()
      .withName(VolumeName)
      .withConfigMap(
        new ConfigMapVolumeSourceBuilder().withName(CnpgRendering.schemaConfigMapName).build()
      )
      .build()

  /**
   * `psql` reads `PGHOST`/`PGPORT`/`PGUSER`/`PGDATABASE` and the `PGSSL*` settings from the
   * environment, and the `ANKKA_DB_*` keys the credential secret already carries are exactly those,
   * one field short of the standard names — set below rather than duplicating the secret under both
   * sets of key names.
   */
  private def script: String =
    """set -e
      |export PGHOST="$ANKKA_DB_HOST" PGPORT="$ANKKA_DB_PORT" PGUSER="$ANKKA_DB_USER"
      |export PGDATABASE="$ANKKA_DB_NAME"
      |# By certificate (feature 014): verify the server against the project cluster's own authority,
      |# and present the service's client certificate, whose common name is the role.
      |export PGSSLMODE="$ANKKA_DB_SSL_MODE" PGSSLROOTCERT="$ANKKA_DB_SSL_ROOT_CERT"
      |export PGSSLCERT="$ANKKA_DB_SSL_CERT" PGSSLKEY="$ANKKA_DB_SSL_KEY"
      |
      |# 1. Wait. The project's Cluster may still be starting, or the role may not have landed
      |#    yet because CNPG's per-cluster secret RBAC allowlist has not caught up (research R5).
      |until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"; do sleep 1; done
      |until psql -c 'select 1' >/dev/null 2>&1; do sleep 2; done
      |
      |# 2. Apply ankka's schema, then close the database — in ONE psql session, holding an
      |#    advisory lock from before the first file. Every statement is IF NOT EXISTS, so running
      |#    this on every start is safe and self-heals after a restore; but IF NOT EXISTS is not
      |#    safe under concurrency, and since feature 004 several pods of one service start at
      |#    once. The lock is session-scoped and the session ends with psql, so it cannot leak.
      |#    The REVOKE is why: Postgres grants CONNECT to PUBLIC by default, so without it any
      |#    other service's role in this project could connect here and enumerate its name, even
      |#    though its tables stay private either way (feature 002, research R9).
      |psql -v ON_ERROR_STOP=1 \
      |  -c "SELECT pg_advisory_lock(627165225000);" \
      |  $(for f in /schema/*.sql; do printf -- '-f %s ' "$f"; done) \
      |  -c "REVOKE CONNECT ON DATABASE \"$PGDATABASE\" FROM PUBLIC;"
      |""".stripMargin
