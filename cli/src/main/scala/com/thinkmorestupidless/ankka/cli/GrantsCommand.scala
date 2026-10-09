package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.{
  GrantRequest,
  GrantRules,
  GrantState,
  GrantTarget
}

/**
 * `projects grants` and `organizations grants` (feature 040): a grant read from the words a person
 * types, checked by the rules the server applies, before anything is sent.
 */
object GrantsCommand:

  /**
   * `<grantee> <target words…>`: `service:payments/merchant route wallet POST /v1/…`. `--decrypt`
   * is the same as a trailing `decrypt` on a topic target.
   */
  def request(grantee: String, words: List[String], decrypt: Boolean): GrantRequest =
    val all =
      words.toVector ++ Option.when(decrypt && !words.lastOption.contains("decrypt"))("decrypt")
    val target   = GrantTarget.parse(all).fold(why => throw ApiError(0, why), identity)
    val request  = GrantRequest(grantee, target)
    val problems = GrantRules.problems(request)
    if problems.nonEmpty then throw ApiError(0, problems.mkString("; "))
    request

  /**
   * `withdraw` and `revoke` are one route on the server, which ends a grant whichever state it is
   * in; the CLI holds the person to the word they chose, so `withdraw` never revokes.
   */
  def end(client: ControlPlaneClient, projectId: String, grantId: String, verb: String): String =
    val grant = client
      .listGrants(projectId)
      .find(_.id == grantId)
      .getOrElse(throw ApiError(404, s"project '$projectId' has no grant '$grantId'"))
    val (wanted, applies) =
      if verb == "withdraw" then (GrantState.Pending, "a pending")
      else (GrantState.Accepted, "an accepted")
    if grant.state != wanted then
      throw ApiError(409, GrantRules.cannot(grantId, grant.state, verb, applies))
    client.endGrant(projectId, grantId)
    val done = if verb == "withdraw" then "withdrew" else "revoked"
    s"$done grant $grantId: ${grant.grantee.text} ${grant.target.text}"
