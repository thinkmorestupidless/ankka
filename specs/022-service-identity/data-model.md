# Data Model: Service-Level Identity

Nothing here is persisted. These are the values the module builds from configuration and from a
token, and the shape the protocol carries.

## 1. Issuer configuration

```text
Issuer
  name        String        the key in ANKKA_AUTH_ISSUERS; [A-Za-z][A-Za-z0-9-]*; unique in a config
  issuer      String        what a token's `iss` must equal exactly; trailing "/" stripped
  jwksUrl     String        where the keys are fetched; http or https
  audience    String        what a token's `aud` must contain
  ca          Option[Path]  a PEM bundle the keys fetch trusts alone; None = the JVM's trust store
  typ         Option[String]  a required `typ` header value; None = not checked
  clockSkew   FiniteDuration  default 60 seconds

OidcConfig
  issuers     Vector[Issuer]   may be empty = "no issuers"
  realm       String           the `realm=` of a challenge; default "ankka"
```

Validation (`OidcConfig.fromEnv`), every problem reported:

| Condition | Problem |
|---|---|
| `ANKKA_AUTH_ISSUERS` absent or blank | none; `OidcConfig.empty` |
| a name not matching the pattern | `ANKKA_AUTH_ISSUERS: '<name>' is not a valid issuer name` |
| a name listed twice | `ANKKA_AUTH_ISSUERS: '<name>' is listed twice` |
| `_ISSUER`, `_JWKS_URL` or `_AUDIENCE` missing for a name | `ANKKA_AUTH_<NAME>_<VAR> is not set` |
| `_CLOCK_SKEW` not a duration | `ANKKA_AUTH_<NAME>_CLOCK_SKEW: '<value>' is not a duration` |
| `_CA` names a file that does not exist | `ANKKA_AUTH_<NAME>_CA: no file at <path>` |

The name's variable segment is the name upper-cased with `-` replaced by `_`: `customers-eu`
reads `ANKKA_AUTH_CUSTOMERS_EU_ISSUER`. Variables under the prefix that belong to no listed name
are ignored, which keeps the control plane's singular `ANKKA_AUTH_ISSUER`, `ANKKA_AUTH_JWKS_URL`
and `ANKKA_AUTH_JWKS_CA` out of the set.

## 2. Verification

```text
Verification
  Verified(claims: JWTClaimsSet, issuer: Issuer)
  Rejected(reason: String)        safe to show; never the token
  Unavailable(reason: String)     keys could not be fetched and none are held for that issuer
```

Order of checks in `verify(token)`:

1. Three dot-separated parts, else `Rejected("not a well-formed token")`.
2. Parse without verifying; read `iss`; no listed issuer equals it → `Rejected("issuer not
   accepted")`, no key lookup.
3. The issuer's processor: signature under an asymmetric algorithm with the issuer's keys
   (`KeySourceException` → `Unavailable`), then `iss`, `aud`, `exp`, `nbf` with skew, `sub`
   present (`BadJOSEException` → `Rejected(reason)`).
4. If the issuer has `typ`: the header's `typ` equals it, else `Rejected("token type '<t>' is not
   accepted")` or `Rejected("token carries no type")`.

## 3. Principal

In `ankka-http`, one field added with a default so every existing construction compiles:

```text
Principal
  subject        String
  name           Option[String]
  email          Option[String]
  emailVerified  Boolean
  roles          Set[String]
  claims         Map[String, String]
  issuer         Option[String]      NEW: the Issuer.name that verified the token; None when another Authenticate built the principal
```

Mapping from verified claims (`Principals.from(claims, issuer)` in the module):

| Field | From |
|---|---|
| subject | `sub` |
| name | `name`, else `preferred_username` |
| email | `email`, trimmed, lowercased, non-empty |
| emailVerified | `email_verified` is true |
| roles | `realm_access.roles` when present, else a top-level `roles` list, else empty |
| claims | every other claim whose value is not null, as its string; `sub`, `name`, `preferred_username`, `email`, `email_verified`, `realm_access`, `roles` excluded |
| issuer | the issuer's name |

## 4. The protocol's principal

```protobuf
message Principal {
  string subject = 1;
  optional string name = 2;
  optional string email = 3;
  bool email_verified = 4;
  repeated string roles = 5;
  map<string, string> claims = 6;   // NEW in 1.4
  optional string issuer = 7;       // NEW in 1.4
}
```

Protocol version `1.4`. The SDK types mirror it: Python `Principal.claims: Mapping[str, str]`,
`issuer: str | None`; TypeScript `claims: Readonly<Record<string, string>>`, `issuer: string | null`;
Rust `claims: BTreeMap<String, String>`, `issuer: Option<String>`.

## 5. A discovery problem

Added to `Discovery.validate`'s vector when `authConfigured` is false:

```text
endpoint '<id>' is AUTHENTICATED but no issuer is configured; set ANKKA_AUTH_ISSUERS
endpoint '<id>': route '<METHOD> <template>' is AUTHENTICATED but no issuer is configured; set ANKKA_AUTH_ISSUERS
```

## 6. The control plane's configuration, mapped

`AuthConfig.toOidc`:

```text
Issuer(name = "ankka", issuer, jwksUrl, audience, ca = jwksCa, typ = Some("Bearer"), clockSkew)
OidcConfig(Vector(that), realm = realmHint)
```

No field of `AuthConfig` changes; no variable of the control plane changes.
