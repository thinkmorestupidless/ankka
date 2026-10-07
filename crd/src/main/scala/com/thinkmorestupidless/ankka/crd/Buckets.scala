package com.thinkmorestupidless.ankka.crd

/**
 * A service's bucket in the installation's object store, the Secret its storage credential is in,
 * and its address on the internet (feature 034).
 *
 * Here for the reason `Hostnames` is: the control plane refuses a name and shows it, the operator
 * makes the bucket and its route, and the resource carries only two booleans — so no writer of the
 * resource can point a service at a bucket that is not its own.
 *
 * A bucket's name is global to the store, so it carries the project, where a database's need not.
 * The two parts are joined by a dot, which neither a project id nor a service name can contain, so
 * no two pairs share a name; a hyphen would repeat the hostname's collision (`a-b` in `c`, `a` in
 * `b-c`), and here a collision would hand one service another's objects.
 */
object Buckets:

  /** An S3 bucket's name is at most this long. */
  val MaxName: Int = 63

  /**
   * Every storage credential's Secret ends so; no project secret and no descriptor may name one.
   */
  val SecretSuffix: String = "-storage"

  def name(projectId: String, serviceName: String): String = s"$projectId.$serviceName"

  def secret(serviceName: String): String = s"$serviceName$SecretSuffix"

  /** Why this pair cannot have a bucket, if it cannot. Empty means it can. */
  def problems(projectId: String, serviceName: String): Vector[String] =
    val n = name(projectId, serviceName)
    if n.length > MaxName then
      Vector(
        s"bucket name '$n' is ${n.length} characters, over the $MaxName character limit for a " +
          "bucket's name; a shorter service name or project id is the only fix"
      )
    else Vector.empty

  /**
   * The store's address on the internet: the scheme, its hostname and the port unless it is 443.
   */
  def publicEndpoint(baseDomain: String, httpsPort: Int): String =
    val port = if httpsPort == 443 then "" else s":$httpsPort"
    s"https://${Hostnames.storage(baseDomain)}$port"

  def publicAddress(
      projectId: String,
      serviceName: String,
      baseDomain: String,
      httpsPort: Int
  ): String =
    s"${publicEndpoint(baseDomain, httpsPort)}/${name(projectId, serviceName)}"
