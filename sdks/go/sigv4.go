package ankka

// AWS Signature Version 4, as S3 and every store that speaks it reads it: a request signed in its
// headers, and a URL signed in its query string. The runtime's SigV4.scala and the other process
// SDKs make the same choices, and AWS's published vectors hold them all: paths are encoded once,
// segment by segment, as S3 expects; a put's payload is UNSIGNED-PAYLOAD, so a stream is sent once
// and never hashed.

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// credentials are a key pair. Never printed whole: String hides the secret.
type credentials struct {
	accessKey string
	secretKey string
}

func (c credentials) String() string { return "Credentials(" + c.accessKey + ", ***)" }

// GoString hides the secret from %#v too.
func (c credentials) GoString() string { return c.String() }

const (
	sigAlgorithm = "AWS4-HMAC-SHA256"
	// unsignedPayload is the payload hash a put sends so its body is streamed once and never hashed.
	unsignedPayload = "UNSIGNED-PAYLOAD"
)

func sha256Hex(data []byte) string {
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}

// emptyPayload is the hash of an empty payload.
var emptyPayload = sha256Hex(nil)

func hmacSHA256(key []byte, data string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(data))
	return m.Sum(nil)
}

// sigEncode is RFC 3986 encoding as SigV4 states it: unreserved characters kept, everything else
// %XX of its UTF-8 bytes; a slash kept unless encodeSlash.
func sigEncode(text string, encodeSlash bool) string {
	var b strings.Builder
	for i := 0; i < len(text); i++ {
		c := text[i]
		switch {
		case c >= 'A' && c <= 'Z', c >= 'a' && c <= 'z', c >= '0' && c <= '9', c == '-', c == '_', c == '.', c == '~', c == '/' && !encodeSlash:
			b.WriteByte(c)
		default:
			b.WriteString("%" + strings.ToUpper(hex.EncodeToString([]byte{c})))
		}
	}
	return b.String()
}

// signedRequest is one request: the endpoint's scheme and authority, a decoded path and decoded
// query pairs.
type signedRequest struct {
	method   string
	endpoint *url.URL
	path     string
	query    [][2]string
}

// host is the host header's value: the host, with the port when the endpoint names one.
func (r signedRequest) host() string { return r.endpoint.Host }

func (r signedRequest) canonicalPath() string {
	base := r.endpoint.Path
	if base == "/" {
		base = ""
	}
	base = strings.TrimSuffix(base, "/")
	p := r.path
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return base + sigEncode(p, false)
}

func (r signedRequest) canonicalQuery() string {
	pairs := make([][2]string, 0, len(r.query))
	for _, q := range r.query {
		pairs = append(pairs, [2]string{sigEncode(q[0], true), sigEncode(q[1], true)})
	}
	sort.Slice(pairs, func(i, j int) bool {
		if pairs[i][0] != pairs[j][0] {
			return pairs[i][0] < pairs[j][0]
		}
		return pairs[i][1] < pairs[j][1]
	})
	out := make([]string, len(pairs))
	for i, p := range pairs {
		out[i] = p[0] + "=" + p[1]
	}
	return strings.Join(out, "&")
}

// url is where the request is sent: the same encoding as the one signed.
func (r signedRequest) url() string {
	q := r.canonicalQuery()
	out := r.endpoint.Scheme + "://" + r.host() + r.canonicalPath()
	if q != "" {
		out += "?" + q
	}
	return out
}

func (r signedRequest) withQuery(more ...[2]string) signedRequest {
	r.query = append(append([][2]string{}, r.query...), more...)
	return r
}

func amzDate(now time.Time) string { return now.UTC().Format("20060102T150405Z") }

func shortDate(now time.Time) string { return now.UTC().Format("20060102") }

func signingKey(secret string, now time.Time, region, service string) []byte {
	date := hmacSHA256([]byte("AWS4"+secret), shortDate(now))
	return hmacSHA256(hmacSHA256(hmacSHA256(date, region), service), "aws4_request")
}

func stringToSign(stamp, scope, canonicalRequest string) string {
	return sigAlgorithm + "\n" + stamp + "\n" + scope + "\n" + sha256Hex([]byte(canonicalRequest))
}

// sign answers the headers to send with request so the store believes it: x-amz-date and
// Authorization. headers are the ones to sign beside host and x-amz-date, and are sent as given.
func sign(request signedRequest, headers map[string]string, payloadHash string, c credentials, region, service string, now time.Time) (date, authorization string) {
	stamp := amzDate(now)
	signed := map[string]string{}
	for k, v := range headers {
		signed[strings.ToLower(k)] = v
	}
	signed["host"] = request.host()
	signed["x-amz-date"] = stamp
	names := make([]string, 0, len(signed))
	for k := range signed {
		names = append(names, k)
	}
	sort.Strings(names)
	var canonical strings.Builder
	for _, k := range names {
		canonical.WriteString(k + ":" + strings.Join(strings.Fields(strings.TrimSpace(signed[k])), " ") + "\n")
	}
	scope := shortDate(now) + "/" + region + "/" + service + "/aws4_request"
	creq := strings.Join([]string{request.method, request.canonicalPath(), request.canonicalQuery(), canonical.String(), strings.Join(names, ";"), payloadHash}, "\n")
	signature := hex.EncodeToString(hmacSHA256(signingKey(c.secretKey, now, region, service), stringToSign(stamp, scope, creq)))
	return stamp, sigAlgorithm + " Credential=" + c.accessKey + "/" + scope + ", SignedHeaders=" + strings.Join(names, ";") + ", Signature=" + signature
}

// presign is a URL anyone may use, until it expires, for request's method on its path. It signs
// host alone.
func presign(request signedRequest, c credentials, region, service string, expiresSeconds int64, now time.Time) string {
	stamp := amzDate(now)
	scope := shortDate(now) + "/" + region + "/" + service + "/aws4_request"
	withAuth := request.withQuery(
		[2]string{"X-Amz-Algorithm", sigAlgorithm},
		[2]string{"X-Amz-Credential", c.accessKey + "/" + scope},
		[2]string{"X-Amz-Date", stamp},
		[2]string{"X-Amz-Expires", strconv.FormatInt(expiresSeconds, 10)},
		[2]string{"X-Amz-SignedHeaders", "host"},
	)
	creq := strings.Join([]string{request.method, withAuth.canonicalPath(), withAuth.canonicalQuery(), "host:" + request.host() + "\n", "host", unsignedPayload}, "\n")
	signature := hex.EncodeToString(hmacSHA256(signingKey(c.secretKey, now, region, service), stringToSign(stamp, scope, creq)))
	return withAuth.withQuery([2]string{"X-Amz-Signature", signature}).url()
}
