package ankka

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"iter"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// s3Target is one S3 bucket and how to reach it.
type s3Target struct {
	endpoint       *url.URL
	region         string
	bucket         string
	credentials    *credentials
	publicEndpoint *url.URL
}

// s3Named is a bucket the descriptor names: its exposure, where it is, and for a public one the base
// it is read at.
type s3Named struct {
	name      string
	exposure  Exposure
	target    s3Target
	publicURL string
}

// s3Settings are the S3 store's settings, read from the ANKKA_S3_* variables as the runtime reads
// them.
type s3Settings struct {
	unnamed *s3Target
	named   []s3Named
	timeout time.Duration
	// softDelete is how long the store keeps a deleted object (feature 042), from
	// ANKKA_S3_SOFT_DELETE_DAYS: a whole number of days from 1, or zero for at once.
	softDelete time.Duration
}

// readSoftDelete reads ANKKA_S3_SOFT_DELETE_DAYS: a whole number of days from 1, or unset.
func readSoftDelete(env map[string]string) (time.Duration, error) {
	variable := s3Prefix + "SOFT_DELETE_DAYS"
	text := strings.TrimSpace(env[variable])
	if text == "" {
		return 0, nil
	}
	days, err := strconv.Atoi(text)
	if err != nil || days < 1 || strings.ContainsAny(text, "+-") {
		return 0, fmt.Errorf("%s is '%s'; it is a whole number of days from 1", variable, text)
	}
	return time.Duration(days) * 24 * time.Hour, nil
}

const s3Prefix = "ANKKA_S3_"

func parseStoreURL(name, value string) (*url.URL, error) {
	u, err := url.Parse(value)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, fmt.Errorf("%s is not a URL with a scheme and a host: '%s'", name, value)
	}
	return u, nil
}

// bucketNameOf is the bucket a variable's <NAME> names: lower case, _ as -.
func bucketNameOf(suffix string) string { return strings.ReplaceAll(strings.ToLower(suffix), "_", "-") }

// readS3Settings reads the unnamed family ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,
// PUBLIC_ENDPOINT}; a named bucket the platform made, ANKKA_S3_BUCKET_<NAME>, ANKKA_S3_EXPOSURE_<NAME>
// and, for a public one, ANKKA_S3_PUBLIC_URL_<NAME>, sharing the unnamed family's endpoint, region
// and key; and a supplied bucket, the five ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}_<NAME>
// of its own. An error names the variable of a family that cannot be read.
func readS3Settings(env map[string]string) (s3Settings, error) {
	out := s3Settings{timeout: 30 * time.Second}
	softDelete, err := readSoftDelete(env)
	if err != nil {
		return out, err
	}
	out.softDelete = softDelete
	get := func(name string) string { return env[name] }
	required := func(name string) (string, error) {
		if v := get(name); v != "" {
			return v, nil
		}
		return "", fmt.Errorf("%s is not set", name)
	}
	var unnamed *s3Target
	if endpoint := get(s3Prefix + "ENDPOINT"); endpoint != "" {
		u, err := parseStoreURL(s3Prefix+"ENDPOINT", endpoint)
		if err != nil {
			return out, err
		}
		region, err := required(s3Prefix + "REGION")
		if err != nil {
			return out, err
		}
		t := &s3Target{endpoint: u, region: region, bucket: get(s3Prefix + "BUCKET")}
		if a, s := get(s3Prefix+"ACCESS_KEY"), get(s3Prefix+"SECRET_KEY"); a != "" && s != "" {
			t.credentials = &credentials{accessKey: a, secretKey: s}
		}
		if p := get(s3Prefix + "PUBLIC_ENDPOINT"); p != "" {
			if t.publicEndpoint, err = parseStoreURL(s3Prefix+"PUBLIC_ENDPOINT", p); err != nil {
				return out, err
			}
		}
		unnamed = t
	}
	suffixes := map[string]bool{}
	for key := range env {
		for _, p := range []string{s3Prefix + "BUCKET_", s3Prefix + "ENDPOINT_"} {
			if strings.HasPrefix(key, p) && len(key) > len(p) {
				suffixes[key[len(p):]] = true
			}
		}
	}
	for _, suffix := range sortedKeys(suffixes) {
		name := bucketNameOf(suffix)
		if own := get(s3Prefix + "ENDPOINT_" + suffix); own != "" {
			u, err := parseStoreURL(s3Prefix+"ENDPOINT_"+suffix, own)
			if err != nil {
				return out, err
			}
			var vals [4]string
			for i, v := range []string{"REGION_", "BUCKET_", "ACCESS_KEY_", "SECRET_KEY_"} {
				if vals[i], err = required(s3Prefix + v + suffix); err != nil {
					return out, err
				}
			}
			out.named = append(out.named, s3Named{name: name, exposure: ExposureSupplied,
				target: s3Target{endpoint: u, region: vals[0], bucket: vals[1], credentials: &credentials{accessKey: vals[2], secretKey: vals[3]}}})
			continue
		}
		bucket, err := required(s3Prefix + "BUCKET_" + suffix)
		if err != nil {
			return out, err
		}
		if unnamed == nil {
			return out, fmt.Errorf("bucket '%s' (%sBUCKET_%s) is made by the platform and %sENDPOINT is not set", name, s3Prefix, suffix, s3Prefix)
		}
		word := get(s3Prefix + "EXPOSURE_" + suffix)
		if word == "" {
			word = "private"
		}
		switch Exposure(word) {
		case ExposurePrivate, ExposureSigned, ExposurePublic:
		default:
			return out, fmt.Errorf("%sEXPOSURE_%s is '%s'; it is one of private, signed or public", s3Prefix, suffix, word)
		}
		publicURL := strings.TrimSuffix(get(s3Prefix+"PUBLIC_URL_"+suffix), "/")
		if word == "public" && publicURL == "" {
			return out, fmt.Errorf("bucket '%s' is public and %sPUBLIC_URL_%s is not set", name, s3Prefix, suffix)
		}
		t := *unnamed
		t.bucket = bucket
		out.named = append(out.named, s3Named{name: name, exposure: Exposure(word), target: t, publicURL: publicURL})
	}
	if unnamed != nil && unnamed.bucket != "" {
		out.unnamed = unnamed
	}
	return out, nil
}

// S3Storage is the S3 store's buckets, by name.
type S3Storage struct {
	unnamed *s3Bucket
	named   map[string]*s3Bucket
}

func newS3Storage(s s3Settings) *S3Storage {
	client := &http.Client{Timeout: s.timeout}
	out := &S3Storage{named: map[string]*s3Bucket{}}
	if s.unnamed != nil {
		exposure := ExposurePrivate
		if s.unnamed.publicEndpoint != nil {
			exposure = ExposureSigned
		}
		out.unnamed = &s3Bucket{target: *s.unnamed, exposure: exposure, client: client, timeout: s.timeout, softDelete: s.softDelete}
	}
	for _, n := range s.named {
		out.named[n.name] = &s3Bucket{target: n.target, name: n.name, exposure: n.exposure, publicURL: n.publicURL, client: client, timeout: s.timeout, softDelete: s.softDelete}
	}
	return out
}

func (s *S3Storage) Default() (Bucket, error) {
	if s.unnamed == nil {
		return nil, noBucketRefusal()
	}
	return s.unnamed, nil
}

func (s *S3Storage) Bucket(name string) (Bucket, error) {
	b, ok := s.named[name]
	if !ok {
		return nil, unknownBucketRefusal(name, sortedKeys(s.named))
	}
	return b, nil
}

func (s *S3Storage) Buckets() []Bucket {
	var out []Bucket
	for _, n := range sortedKeys(s.named) {
		out = append(out, s.named[n])
	}
	return out
}

// s3Bucket is one bucket in an S3 store: path-style requests, a put streamed once with an unsigned
// payload and its length declared, a listing paged as it is walked.
type s3Bucket struct {
	target    s3Target
	name      string
	exposure  Exposure
	publicURL string
	client    *http.Client
	timeout   time.Duration
	// softDelete is the store's soft-delete window; zero for none.
	softDelete time.Duration
}

func (b *s3Bucket) Name() string       { return b.name }
func (b *s3Bucket) Exposure() Exposure { return b.exposure }

func (b *s3Bucket) endpoint() string { return b.target.endpoint.String() }

func (b *s3Bucket) objectPath(name string) string { return "/" + b.target.bucket + "/" + name }

func (b *s3Bucket) creds() (credentials, error) {
	if b.target.credentials == nil {
		return credentials{}, noCredentialRefusal()
	}
	return *b.target.credentials, nil
}

// countingReader counts what is read through it.
type countingReader struct {
	r io.Reader
	n int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}

func (b *s3Bucket) send(ctx context.Context, method, path string, query [][2]string, headers map[string]string, payload string, body io.Reader, length int64) (*http.Response, error) {
	c, err := b.creds()
	if err != nil {
		return nil, err
	}
	if headers == nil {
		headers = map[string]string{"x-amz-content-sha256": emptyPayload}
	}
	if payload == "" {
		payload = emptyPayload
	}
	r := signedRequest{method: method, endpoint: b.target.endpoint, path: path, query: query}
	date, auth := sign(r, headers, payload, c, b.target.region, "s3", time.Now())
	req, err := http.NewRequestWithContext(ctx, method, r.url(), body)
	if err != nil {
		return nil, unreachableRefusal(b.endpoint(), err.Error())
	}
	// The URL is sent as signed: the path already encoded, and not encoded again.
	req.URL.Opaque = r.canonicalPath()
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	req.Header.Set("x-amz-date", date)
	req.Header.Set("Authorization", auth)
	if body != nil {
		req.ContentLength = length
	}
	res, err := b.client.Do(req)
	if err != nil {
		var r *RefusalError
		if errors.As(err, &r) {
			return nil, r
		}
		cause := err.Error()
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			cause = fmt.Sprintf("no answer within %d seconds", int(b.timeout/time.Second))
		}
		return nil, unreachableRefusal(b.endpoint(), cause)
	}
	return res, nil
}

var codePattern = regexp.MustCompile(`<Code>([^<]*)</Code>`)

func (b *s3Bucket) refused(res *http.Response) error {
	text, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
	res.Body.Close()
	detail := ""
	if m := codePattern.FindSubmatch(text); m != nil {
		detail = string(m[1])
	}
	return refusedByStoreRefusal(b.endpoint(), res.StatusCode, detail)
}

func lastModifiedOf(res *http.Response) time.Time {
	if t, err := http.ParseTime(res.Header.Get("Last-Modified")); err == nil {
		return t.UTC()
	}
	return time.Unix(0, 0).UTC()
}

func contentTypeOf(res *http.Response) string {
	if c := res.Header.Get("Content-Type"); c != "" {
		return c
	}
	return "application/octet-stream"
}

func (b *s3Bucket) Put(ctx context.Context, name string, body io.Reader, length int64, contentType string) error {
	if err := checkName(name); err != nil {
		return err
	}
	if length < 0 {
		return negativeLengthRefusal(length)
	}
	if body == nil {
		body = strings.NewReader("")
	}
	counted := &countingReader{r: io.LimitReader(body, length)}
	headers := map[string]string{"Content-Type": contentType, "x-amz-content-sha256": unsignedPayload}
	res, err := b.send(ctx, http.MethodPut, b.objectPath(name), nil, headers, unsignedPayload, counted, length)
	if counted.n < length {
		if res != nil {
			res.Body.Close()
		}
		return shortBodyRefusal(length, counted.n)
	}
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		return b.refused(res)
	}
	_, _ = io.Copy(io.Discard, res.Body)
	return res.Body.Close()
}

func (b *s3Bucket) Get(ctx context.Context, name string) (*StoredObject, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	res, err := b.send(ctx, http.MethodGet, b.objectPath(name), nil, nil, "", nil, 0)
	if err != nil {
		return nil, err
	}
	switch res.StatusCode {
	case http.StatusOK:
		return &StoredObject{ContentType: contentTypeOf(res), Length: res.ContentLength, LastKept: lastModifiedOf(res), Body: res.Body}, nil
	case http.StatusNotFound:
		res.Body.Close()
		return nil, nil
	}
	return nil, b.refused(res)
}

func (b *s3Bucket) Stat(ctx context.Context, name string) (*ObjectInfo, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	res, err := b.send(ctx, http.MethodHead, b.objectPath(name), nil, nil, "", nil, 0)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	switch res.StatusCode {
	case http.StatusOK:
		length := res.ContentLength
		if length < 0 {
			length = 0
		}
		return &ObjectInfo{Name: name, ContentType: contentTypeOf(res), Length: length, LastKept: lastModifiedOf(res)}, nil
	case http.StatusNotFound:
		return nil, nil
	}
	return nil, refusedByStoreRefusal(b.endpoint(), res.StatusCode, "")
}

func (b *s3Bucket) Delete(ctx context.Context, name string) error {
	if err := checkName(name); err != nil {
		return err
	}
	res, err := b.send(ctx, http.MethodDelete, b.objectPath(name), nil, nil, "", nil, 0)
	if err != nil {
		return err
	}
	switch res.StatusCode {
	case http.StatusOK, http.StatusNoContent, http.StatusNotFound:
		_, _ = io.Copy(io.Discard, res.Body)
		return res.Body.Close()
	}
	return b.refused(res)
}

type listing struct {
	IsTruncated           bool   `xml:"IsTruncated"`
	NextContinuationToken string `xml:"NextContinuationToken"`
	Contents              []struct {
		Key          *string `xml:"Key"`
		Size         string  `xml:"Size"`
		LastModified string  `xml:"LastModified"`
	} `xml:"Contents"`
}

// parseListing reads a ListObjectsV2 answer: its entries, and the token for the next page while it
// is truncated.
func parseListing(data []byte) ([]ObjectEntry, string, error) {
	var l listing
	if err := xml.Unmarshal(data, &l); err != nil {
		return nil, "", err
	}
	var out []ObjectEntry
	for _, c := range l.Contents {
		if c.Key == nil {
			return nil, "", errors.New("a listing's entry has no Key")
		}
		var length int64
		if s := strings.TrimSpace(c.Size); s != "" {
			n, err := strconv.ParseInt(s, 10, 64)
			if err != nil {
				return nil, "", fmt.Errorf("a listing's Size is not a number: %s", c.Size)
			}
			length = n
		}
		kept := time.Unix(0, 0).UTC()
		if t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(c.LastModified)); err == nil {
			kept = t.UTC()
		}
		out = append(out, ObjectEntry{Name: *c.Key, Length: length, LastKept: kept})
	}
	next := ""
	if l.IsTruncated {
		next = l.NextContinuationToken
	}
	return out, next, nil
}

func (b *s3Bucket) List(ctx context.Context, prefix string) iter.Seq2[ObjectEntry, error] {
	return func(yield func(ObjectEntry, error) bool) {
		if err := checkPrefix(prefix); err != nil {
			yield(ObjectEntry{}, err)
			return
		}
		token := ""
		for {
			query := [][2]string{{"list-type", "2"}, {"prefix", prefix}}
			if token != "" {
				query = append(query, [2]string{"continuation-token", token})
			}
			res, err := b.send(ctx, http.MethodGet, "/"+b.target.bucket, query, nil, "", nil, 0)
			if err != nil {
				yield(ObjectEntry{}, err)
				return
			}
			if res.StatusCode != http.StatusOK {
				yield(ObjectEntry{}, b.refused(res))
				return
			}
			data, err := io.ReadAll(io.LimitReader(res.Body, 64<<20))
			res.Body.Close()
			if err != nil {
				yield(ObjectEntry{}, unreachableRefusal(b.endpoint(), err.Error()))
				return
			}
			entries, next, err := parseListing(data)
			if err != nil {
				yield(ObjectEntry{}, unreadableRefusal(b.endpoint(), err.Error()))
				return
			}
			sort.SliceStable(entries, func(i, j int) bool { return entries[i].Name < entries[j].Name })
			for _, e := range entries {
				if !yield(e, nil) {
					return
				}
			}
			if next == "" {
				return
			}
			token = next
		}
	}
}

// versionsPage is one page of a ListObjectVersions answer.
type versionsPage struct {
	// versions is each version and delete marker as its key and version id, the id empty for the
	// store's "null".
	versions    [][2]string
	truncated   bool
	nextKey     string
	nextVersion string
	// generations is true when the answer named versions as Google Cloud Storage does, Generation
	// and NextGenerationMarker; its next page is asked for by generation-marker.
	generations bool
}

type versionListing struct {
	IsTruncated         bool    `xml:"IsTruncated"`
	NextKeyMarker       string  `xml:"NextKeyMarker"`
	NextVersionIDMarker string  `xml:"NextVersionIdMarker"`
	NextGeneration      *string `xml:"NextGenerationMarker"`
	Entries             []struct {
		XMLName    xml.Name
		Key        *string `xml:"Key"`
		VersionID  *string `xml:"VersionId"`
		Generation *string `xml:"Generation"`
	} `xml:",any"`
}

// parseVersions reads a ListObjectVersions answer: its versions and delete markers, in order, and
// where the next page starts while it is truncated.
func parseVersions(data []byte) (versionsPage, error) {
	var l versionListing
	if err := xml.Unmarshal(data, &l); err != nil {
		return versionsPage{}, err
	}
	page := versionsPage{truncated: l.IsTruncated, nextKey: l.NextKeyMarker, nextVersion: l.NextVersionIDMarker}
	if l.NextGeneration != nil {
		page.nextVersion, page.generations = *l.NextGeneration, true
	}
	for _, e := range l.Entries {
		if e.XMLName.Local != "Version" && e.XMLName.Local != "DeleteMarker" {
			continue
		}
		if e.Key == nil {
			return versionsPage{}, fmt.Errorf("a version listing's %s has no Key", e.XMLName.Local)
		}
		// A version written before versioning was on is listed as "null", and deleted by that id: a
		// plain delete would only add a marker above it.
		id := ""
		switch {
		case e.VersionID != nil:
			id = strings.TrimSpace(*e.VersionID)
		case e.Generation != nil:
			id, page.generations = strings.TrimSpace(*e.Generation), true
		}
		page.versions = append(page.versions, [2]string{*e.Key, id})
	}
	return page, nil
}

// Erase lists every version and delete marker under the subject's prefix (ListObjectVersions) and
// deletes each by its version id; the count is the objects with a version. A store that lists no
// versions (Garage answers NotImplemented) is listed plainly and each object deleted. The finality is
// ANKKA_S3_SOFT_DELETE_DAYS's, or Immediate.
func (b *s3Bucket) Erase(ctx context.Context, subject DataSubject) (ErasedObjects, error) {
	finality := Immediate
	if b.softDelete > 0 {
		finality = After(b.softDelete)
	}
	if subject.IsZero() {
		return eraseListed(ctx, b, subject, finality)
	}
	versions, listed, err := b.versionsUnder(ctx, SubjectPrefix(subject))
	if err != nil {
		return ErasedObjects{}, err
	}
	if !listed {
		return eraseListed(ctx, b, subject, finality)
	}
	objects := map[string]bool{}
	for _, v := range versions {
		if err := b.deleteVersion(ctx, v[0], v[1]); err != nil {
			return ErasedObjects{}, err
		}
		if v[1] != "" {
			objects[v[0]] = true
		}
	}
	return ErasedObjects{Count: int64(len(objects)), Finality: finality}, nil
}

// versionsUnder is every version and delete marker under prefix, and false when the store lists none.
func (b *s3Bucket) versionsUnder(ctx context.Context, prefix string) ([][2]string, bool, error) {
	var found [][2]string
	keyMarker, versionMarker := "", ""
	generations := false
	for {
		query := [][2]string{{"prefix", prefix}, {"versions", ""}}
		if keyMarker != "" {
			query = append(query, [2]string{"key-marker", keyMarker})
		}
		if versionMarker != "" {
			// Google pages by generation and names the next one generation-marker.
			name := "version-id-marker"
			if generations {
				name = "generation-marker"
			}
			query = append(query, [2]string{name, versionMarker})
		}
		res, err := b.send(ctx, http.MethodGet, "/"+b.target.bucket, query, nil, "", nil, 0)
		if err != nil {
			return nil, false, err
		}
		data, err := io.ReadAll(io.LimitReader(res.Body, 64<<20))
		res.Body.Close()
		if err != nil {
			return nil, false, unreachableRefusal(b.endpoint(), err.Error())
		}
		if res.StatusCode == http.StatusNotImplemented || bytes.Contains(data, []byte("<Code>NotImplemented</Code>")) {
			return nil, false, nil
		}
		if res.StatusCode != http.StatusOK {
			detail := ""
			if m := codePattern.FindSubmatch(data); m != nil {
				detail = string(m[1])
			}
			return nil, false, refusedByStoreRefusal(b.endpoint(), res.StatusCode, detail)
		}
		page, err := parseVersions(data)
		if err != nil {
			return nil, false, unreadableRefusal(b.endpoint(), err.Error())
		}
		found = append(found, page.versions...)
		if !page.truncated {
			return found, true, nil
		}
		keyMarker, versionMarker, generations = page.nextKey, page.nextVersion, page.generations
	}
}

// deleteVersion deletes one version, or a delete marker; version empty is the plain delete.
func (b *s3Bucket) deleteVersion(ctx context.Context, name, version string) error {
	var query [][2]string
	if version != "" {
		query = [][2]string{{"versionId", version}}
	}
	res, err := b.send(ctx, http.MethodDelete, b.objectPath(name), query, nil, "", nil, 0)
	if err != nil {
		return err
	}
	switch res.StatusCode {
	case http.StatusOK, http.StatusNoContent, http.StatusNotFound:
		_, _ = io.Copy(io.Discard, res.Body)
		return res.Body.Close()
	}
	return b.refused(res)
}

func (b *s3Bucket) SignedURL(name string, access SignedAccess, span time.Duration) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	var base *url.URL
	switch {
	case b.exposure == ExposureSupplied:
		base = b.target.endpoint
	case b.name == "" || b.exposure == ExposureSigned:
		if b.target.publicEndpoint == nil {
			return "", notExposedRefusal()
		}
		base = b.target.publicEndpoint
	default:
		return "", notSignedRefusal(b.name, b.exposure)
	}
	span, err := checkSpan(span)
	if err != nil {
		return "", err
	}
	c, err := b.creds()
	if err != nil {
		return "", err
	}
	method := http.MethodGet
	if access == AccessKeep {
		method = http.MethodPut
	}
	return presign(signedRequest{method: method, endpoint: base, path: b.objectPath(name)}, c, b.target.region, "s3", int64(span/time.Second), time.Now()), nil
}

func (b *s3Bucket) PublicURL(name string) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	if b.exposure != ExposurePublic || b.publicURL == "" {
		return "", notPublicRefusal(b.name, b.exposure)
	}
	return strings.TrimSuffix(b.publicURL, "/") + "/" + sigEncode(name, false), nil
}
