package ankka

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

func mustURL(t *testing.T, s string) *url.URL {
	t.Helper()
	u, err := url.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

func refusalOf(t *testing.T, err error) *RefusalError {
	t.Helper()
	r, ok := AsRefusal(err)
	if !ok {
		t.Fatalf("not a refusal: %v", err)
	}
	return r
}

// ---- the signer, against AWS's vectors ----

func TestSignerMatchesGetVanilla(t *testing.T) {
	date, auth := sign(signedRequest{method: "GET", endpoint: mustURL(t, "https://example.amazonaws.com"), path: "/"}, nil, emptyPayload,
		credentials{"AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"}, "us-east-1", "service", time.Date(2015, 8, 30, 12, 36, 0, 0, time.UTC))
	if date != "20150830T123600Z" {
		t.Fatalf("date %s", date)
	}
	want := "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, " +
		"Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
	if auth != want {
		t.Fatalf("authorization\n got %s\nwant %s", auth, want)
	}
}

var s3Creds = credentials{"AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"}
var s3Day = time.Date(2013, 5, 24, 0, 0, 0, 0, time.UTC)

func TestSignerMatchesTheS3SignedGet(t *testing.T) {
	_, auth := sign(signedRequest{method: "GET", endpoint: mustURL(t, "https://examplebucket.s3.amazonaws.com"), path: "/test.txt"},
		map[string]string{"Range": "bytes=0-9", "x-amz-content-sha256": emptyPayload}, emptyPayload, s3Creds, "us-east-1", "s3", s3Day)
	want := "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
		"SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
		"Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
	if auth != want {
		t.Fatalf("authorization\n got %s\nwant %s", auth, want)
	}
}

func TestSignerMatchesTheS3PresignedGet(t *testing.T) {
	signed := presign(signedRequest{method: "GET", endpoint: mustURL(t, "https://examplebucket.s3.amazonaws.com"), path: "/test.txt"}, s3Creds, "us-east-1", "s3", 86400, s3Day)
	u := mustURL(t, signed)
	raw := map[string]string{}
	for _, p := range strings.Split(u.RawQuery, "&") {
		k, v, _ := strings.Cut(p, "=")
		raw[k] = v
	}
	for k, want := range map[string]string{
		"X-Amz-Signature":  "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
		"X-Amz-Credential": "AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request",
		"X-Amz-Expires":    "86400",
	} {
		if raw[k] != want {
			t.Errorf("%s: got %s, want %s", k, raw[k], want)
		}
	}
	if u.Host != "examplebucket.s3.amazonaws.com" || u.Path != "/test.txt" {
		t.Fatalf("url %s", signed)
	}
}

func TestSignerEncodesANameOnce(t *testing.T) {
	r := signedRequest{method: "GET", endpoint: mustURL(t, "http://localhost:3900"), path: "/shop.listings/listings/42/front photo+1.jpg", query: [][2]string{{"prefix", "a/b c"}}}
	if got := r.canonicalPath(); got != "/shop.listings/listings/42/front%20photo%2B1.jpg" {
		t.Errorf("path %s", got)
	}
	if got := r.canonicalQuery(); got != "prefix=a%2Fb%20c" {
		t.Errorf("query %s", got)
	}
	if r.host() != "localhost:3900" {
		t.Errorf("host %s", r.host())
	}
	if got := r.url(); got != "http://localhost:3900/shop.listings/listings/42/front%20photo%2B1.jpg?prefix=a%2Fb%20c" {
		t.Errorf("url %s", got)
	}
	if got := sigEncode("café", true); got != "caf%C3%A9" {
		t.Errorf("utf-8 %s", got)
	}
}

func TestCredentialsNeverPrintTheirSecret(t *testing.T) {
	for _, s := range []string{fmt.Sprint(s3Creds), fmt.Sprintf("%v %+v %#v", s3Creds, s3Creds, s3Creds), fmt.Sprintf("%v", &s3Target{credentials: &s3Creds})} {
		if strings.Contains(s, "wJalr") {
			t.Fatalf("printed the secret: %s", s)
		}
	}
}

// ---- the rules, as the storage fixture states them ----

type ruleRow struct {
	Unit     string `json:"unit"`
	Repeat   int    `json:"repeat"`
	Accepted bool   `json:"accepted"`
	Says     string `json:"says"`
	Why      string `json:"why"`
}

func TestStorageRulesMatchTheFixture(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("protocol/fixtures", "storage", "rules.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rules struct {
		ObjectNames []ruleRow `json:"objectNames"`
		Prefixes    []ruleRow `json:"prefixes"`
		BucketNames []ruleRow `json:"bucketNames"`
		SignedURL   struct {
			MaxSeconds     int64 `json:"maxSeconds"`
			DefaultSeconds int64 `json:"defaultSeconds"`
		} `json:"signedUrl"`
	}
	if err := json.Unmarshal(data, &rules); err != nil {
		t.Fatal(err)
	}
	for name, family := range map[string]struct {
		rows  []ruleRow
		check func(string) string
	}{"objectNames": {rules.ObjectNames, ObjectNameProblem}, "prefixes": {rules.Prefixes, PrefixProblem}, "bucketNames": {rules.BucketNames, BucketNameProblem}} {
		if len(family.rows) == 0 {
			t.Fatalf("%s: no rows", name)
		}
		for _, r := range family.rows {
			text := strings.Repeat(r.Unit, r.Repeat)
			p := family.check(text)
			if r.Accepted && p != "" {
				t.Errorf("%s %q (%s): refused: %s", name, shown(text), r.Why, p)
			}
			if !r.Accepted && !strings.Contains(p, r.Says) {
				t.Errorf("%s %q (%s): said %q, want it to say %q", name, shown(text), r.Why, p, r.Says)
			}
		}
	}
	if int64(MaxSignedURLSpan/time.Second) != rules.SignedURL.MaxSeconds || int64(DefaultSignedURLSpan/time.Second) != rules.SignedURL.DefaultSeconds {
		t.Fatalf("spans %v %v, fixture %+v", MaxSignedURLSpan, DefaultSignedURLSpan, rules.SignedURL)
	}
	if !strings.Contains(SpanProblem(0), "positive") || !strings.Contains(SpanProblem(MaxSignedURLSpan+time.Second), "at most 7 days") || SpanProblem(MaxSignedURLSpan) != "" {
		t.Fatal("span rules")
	}
}

// ---- the contract every store keeps ----

func storeContract(t *testing.T, make func(t *testing.T) Storage) {
	ctx := context.Background()
	t.Run("put get stat delete", func(t *testing.T) {
		b, err := make(t).Default()
		if err != nil {
			t.Fatal(err)
		}
		if err := b.Put(ctx, "listings/42/front photo.jpg", strings.NewReader("hello"), 5, "image/jpeg"); err != nil {
			t.Fatal(err)
		}
		o, err := b.Get(ctx, "listings/42/front photo.jpg")
		if err != nil || o == nil {
			t.Fatalf("get %v %v", o, err)
		}
		data, _ := o.Bytes()
		if string(data) != "hello" || o.ContentType != "image/jpeg" || o.Length != 5 || time.Since(o.LastKept) > time.Minute {
			t.Fatalf("got %q %+v", data, o)
		}
		info, err := b.Stat(ctx, "listings/42/front photo.jpg")
		if err != nil || info == nil || info.Length != 5 || info.Name != "listings/42/front photo.jpg" {
			t.Fatalf("stat %+v %v", info, err)
		}
		if err := b.Delete(ctx, "listings/42/front photo.jpg"); err != nil {
			t.Fatal(err)
		}
		if err := b.Delete(ctx, "listings/42/front photo.jpg"); err != nil {
			t.Fatalf("a second delete: %v", err)
		}
		if o, err := b.Get(ctx, "listings/42/front photo.jpg"); o != nil || err != nil {
			t.Fatalf("after delete %v %v", o, err)
		}
		if i, err := b.Stat(ctx, "nothing"); i != nil || err != nil {
			t.Fatalf("stat of nothing %v %v", i, err)
		}
	})
	t.Run("a put replaces, and a short body keeps the old object", func(t *testing.T) {
		b, _ := make(t).Default()
		_ = b.Put(ctx, "doc", strings.NewReader("one"), 3, "text/plain")
		if err := b.Put(ctx, "doc", strings.NewReader("two!"), 4, "text/markdown"); err != nil {
			t.Fatal(err)
		}
		err := b.Put(ctx, "doc", strings.NewReader("ab"), 10, "text/plain")
		if r := refusalOf(t, err); r.Code != BadRequest || !strings.Contains(r.Message, "ended after 2 of its declared 10 bytes") {
			t.Fatalf("short body: %v", err)
		}
		o, _ := b.Get(ctx, "doc")
		data, _ := o.Bytes()
		if string(data) != "two!" || o.ContentType != "text/markdown" {
			t.Fatalf("kept %q %s", data, o.ContentType)
		}
		if err := b.Put(ctx, "doc", strings.NewReader("x"), -1, "text/plain"); refusalOf(t, err).Code != BadRequest {
			t.Fatal("a negative length")
		}
	})
	t.Run("a listing is by prefix, in name order", func(t *testing.T) {
		b, _ := make(t).Default()
		for _, n := range []string{"paged/c", "paged/a", "paged/e&f", "paged/b", "other/x"} {
			if err := b.Put(ctx, n, strings.NewReader("x"), 1, "text/plain"); err != nil {
				t.Fatal(err)
			}
		}
		var names []string
		for e, err := range b.List(ctx, "paged/") {
			if err != nil {
				t.Fatal(err)
			}
			names = append(names, e.Name)
		}
		if strings.Join(names, ",") != "paged/a,paged/b,paged/c,paged/e&f" {
			t.Fatalf("listed %v", names)
		}
		var all int
		for _, err := range b.List(ctx, "") {
			if err != nil {
				t.Fatal(err)
			}
			all++
		}
		if all != 5 {
			t.Fatalf("listed %d of everything", all)
		}
	})
	t.Run("names and prefixes are refused in the rules' words", func(t *testing.T) {
		b, _ := make(t).Default()
		for _, call := range []func() error{
			func() error { return b.Put(ctx, "a//b", strings.NewReader(""), 0, "text/plain") },
			func() error { _, err := b.Get(ctx, "/a"); return err },
			func() error { _, err := b.Stat(ctx, ".."); return err },
			func() error { return b.Delete(ctx, "") },
			func() error { _, err := b.SignedURL("a\nb", AccessRead, 0); return err },
			func() error {
				for _, err := range b.List(ctx, "../") {
					return err
				}
				return nil
			},
		} {
			if err := call(); refusalOf(t, err).Code != BadRequest {
				t.Fatalf("refused %v", err)
			}
		}
	})
	t.Run("a signed URL's span is bounded", func(t *testing.T) {
		b, _ := make(t).Default()
		if _, err := b.SignedURL("a", AccessRead, 8*24*time.Hour); !strings.Contains(refusalOf(t, err).Message, "at most 7 days") {
			t.Fatal(err)
		}
		u, err := b.SignedURL("a b.jpg", AccessKeep, 0)
		if err != nil || !strings.Contains(u, "a%20b.jpg") || !strings.Contains(u, "access=keep") {
			t.Fatalf("signed %s %v", u, err)
		}
	})
	t.Run("an unknown bucket names the ones there are", func(t *testing.T) {
		_, err := make(t).Bucket("nope")
		if r := refusalOf(t, err); r.Code != Unavailable || !strings.Contains(r.Message, "no bucket named 'nope'") || !strings.Contains(r.Message, "'photos'") {
			t.Fatalf("%v", err)
		}
	})
	t.Run("a public bucket has an address; the unnamed bucket has none", func(t *testing.T) {
		s := make(t)
		photos, err := s.Bucket("photos")
		if err != nil {
			t.Fatal(err)
		}
		if photos.Exposure() != ExposurePublic || photos.Name() != "photos" {
			t.Fatalf("%s %s", photos.Name(), photos.Exposure())
		}
		if u, err := photos.PublicURL("listings/42/front.jpg"); err != nil || !strings.HasSuffix(u, "/listings/42/front.jpg") {
			t.Fatalf("public %s %v", u, err)
		}
		if _, err := photos.SignedURL("a", AccessRead, 0); !strings.Contains(refusalOf(t, err).Message, "bucket 'photos' is public") {
			t.Fatal(err)
		}
		d, _ := s.Default()
		if _, err := d.PublicURL("a"); !strings.Contains(refusalOf(t, err).Message, "the service's unnamed bucket is") {
			t.Fatal(err)
		}
		if names := s.Buckets(); len(names) != 1 || names[0].Name() != "photos" {
			t.Fatalf("buckets %v", names)
		}
	})
}

func TestTheContractOverMemory(t *testing.T) {
	storeContract(t, func(t *testing.T) Storage {
		m := NewMemoryStorage()
		if err := m.Declare("photos", ExposurePublic); err != nil {
			t.Fatal(err)
		}
		return m
	})
}

func TestTheContractOverADirectory(t *testing.T) {
	storeContract(t, func(t *testing.T) Storage {
		return newDirectoryStorage(t.TempDir(), &declaredBuckets{unnamed: true, buckets: map[string]Exposure{"photos": ExposurePublic}}, func() string { return "http://127.0.0.1:9000" })
	})
}

func TestNoStorageRefusesNamingProvisionObjectStorage(t *testing.T) {
	_, err := NoStorage.Default()
	if r := refusalOf(t, err); r.Code != Unavailable || !strings.Contains(r.Message, "provisionObjectStorage") {
		t.Fatal(err)
	}
	m := NewMemoryStorage(MemoryUnnamed(false))
	if _, err := m.Default(); !strings.Contains(err.Error(), "provisionObjectStorage") {
		t.Fatal(err)
	}
}

func TestTheDirectoryStoreKeepsTheRuntimesLayout(t *testing.T) {
	root := t.TempDir()
	s := newDirectoryStorage(root, nil, func() string { return "http://127.0.0.1:9000" })
	b, _ := s.Default()
	ctx := context.Background()
	if err := b.Put(ctx, "a b/c.txt", strings.NewReader("hi"), 2, "text/plain"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "_default", "objects", "a%20b%2Fc.txt")); err != nil {
		t.Fatal(err)
	}
	meta, err := os.ReadFile(filepath.Join(root, "_default", "meta", "a%20b%2Fc.txt.json"))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	_ = json.Unmarshal(meta, &m)
	if m["name"] != "a b/c.txt" || m["contentType"] != "text/plain" || m["length"] != 2.0 || m["lastKept"] == nil {
		t.Fatalf("meta %s", meta)
	}
	long := strings.Repeat("x", 201)
	_ = b.Put(ctx, long, strings.NewReader(""), 0, "text/plain")
	if _, err := os.Stat(filepath.Join(root, "_default", "objects", "h-"+sha256Hex([]byte(long)))); err != nil {
		t.Fatal(err)
	}
	u, err := b.SignedURL("a b/c.txt", AccessRead, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(u, "http://127.0.0.1:9000/_ankka/storage/_default/a%20b/c.txt?access=read&expires=") {
		t.Fatalf("url %s", u)
	}
	key, err := os.Stat(filepath.Join(root, ".key"))
	if err != nil || key.Mode().Perm() != 0o600 || key.Size() != 32 {
		t.Fatalf("key %v %v", key, err)
	}
	// A restart reads the same key, so the URL it signed is still good.
	again := newDirectoryStorage(root, nil, func() string { return "" })
	q := mustURL(t, u).Query()
	expires, _ := strconv.ParseInt(q.Get("expires"), 10, 64)
	if !again.admits("_default", "a b/c.txt", "read", expires, q.Get("token")) {
		t.Fatal("a restarted store refused its own URL")
	}
	if again.admits("_default", "a b/c.txt", "keep", expires, q.Get("token")) || again.admits("_default", "a b/c.txt", "read", time.Now().Add(-time.Second).Unix(), q.Get("token")) {
		t.Fatal("a token admitted another access or an expired one")
	}
	deep := strings.Repeat("d/", 16) + "e"
	if a := addressOf(deep); strings.Contains(a, "/") {
		t.Fatalf("a name deeper than 16 segments keeps its slashes: %s", a)
	}
	if a := addressOf("d/e"); a != "d/e" {
		t.Fatal(a)
	}
}

// ---- the S3 store, against a fake on loopback ----

type seenRequest struct {
	method, url string
	headers     http.Header
	body        []byte
	complete    bool
}

type fakeS3 struct {
	mu         sync.Mutex
	objects    map[string]storedFake
	seen       []seenRequest
	refuseWith int
}

type storedFake struct {
	contentType string
	body        []byte
}

func (f *fakeS3) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seen = append(f.seen, seenRequest{method: r.Method, url: r.RequestURI, headers: r.Header.Clone(), body: body, complete: err == nil})
	if err != nil {
		return
	}
	if f.refuseWith != 0 {
		w.WriteHeader(f.refuseWith)
		_, _ = io.WriteString(w, "<Error><Code>AccessDenied</Code></Error>")
		return
	}
	key := r.URL.EscapedPath()
	switch {
	case r.Method == http.MethodGet && r.URL.Query().Get("list-type") == "2":
		prefix := r.URL.Query().Get("prefix")
		bucket := key + "/"
		var names []string
		for k := range f.objects {
			name, _ := url.PathUnescape(strings.TrimPrefix(k, bucket))
			if strings.HasPrefix(k, bucket) && strings.HasPrefix(name, prefix) {
				names = append(names, name)
			}
		}
		sort.Strings(names)
		start, _ := strconv.Atoi(r.URL.Query().Get("continuation-token"))
		end := min(start+2, len(names))
		var b strings.Builder
		b.WriteString("<ListBucketResult>")
		fmt.Fprintf(&b, "<IsTruncated>%t</IsTruncated>", end < len(names))
		if end < len(names) {
			fmt.Fprintf(&b, "<NextContinuationToken>%d</NextContinuationToken>", end)
		}
		for _, n := range names[start:end] {
			b.WriteString("<Contents><Key>" + strings.ReplaceAll(n, "&", "&amp;") + "</Key><Size>1</Size><LastModified>2026-10-19T10:00:00.000Z</LastModified></Contents>")
		}
		b.WriteString("</ListBucketResult>")
		_, _ = io.WriteString(w, b.String())
	case r.Method == http.MethodPut:
		f.objects[key] = storedFake{contentType: r.Header.Get("Content-Type"), body: body}
	case r.Method == http.MethodGet || r.Method == http.MethodHead:
		o, ok := f.objects[key]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", o.contentType)
		w.Header().Set("Content-Length", strconv.Itoa(len(o.body)))
		w.Header().Set("Last-Modified", "Mon, 19 Oct 2026 10:00:00 GMT")
		_, _ = w.Write(o.body)
	case r.Method == http.MethodDelete:
		delete(f.objects, key)
		w.WriteHeader(http.StatusNoContent)
	}
}

func (f *fakeS3) last() seenRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.seen[len(f.seen)-1]
}

func (f *fakeS3) reset() {
	f.mu.Lock()
	f.seen = nil
	f.mu.Unlock()
}

func newFakeS3(t *testing.T) (*fakeS3, *httptest.Server) {
	f := &fakeS3{objects: map[string]storedFake{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	return f, srv
}

func s3Env(endpoint string) map[string]string {
	return map[string]string{
		"ANKKA_S3_ENDPOINT": endpoint, "ANKKA_S3_REGION": "garage", "ANKKA_S3_BUCKET": "shop-default",
		"ANKKA_S3_ACCESS_KEY": "GKtest", "ANKKA_S3_SECRET_KEY": "s3cr3t", "ANKKA_S3_PUBLIC_ENDPOINT": "https://objects.example.com",
		"ANKKA_S3_BUCKET_PHOTOS": "shop-photos", "ANKKA_S3_EXPOSURE_PHOTOS": "public", "ANKKA_S3_PUBLIC_URL_PHOTOS": "https://photos.example.com/",
	}
}

func s3StoreOf(t *testing.T, env map[string]string) *S3Storage {
	t.Helper()
	settings, err := readS3Settings(env)
	if err != nil {
		t.Fatal(err)
	}
	return newS3Storage(settings)
}

var authPattern = regexp.MustCompile(`^AWS4-HMAC-SHA256 Credential=GKtest/\d{8}/garage/s3/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}$`)

func TestTheS3Store(t *testing.T) {
	f, srv := newFakeS3(t)
	store := s3StoreOf(t, s3Env(srv.URL))
	b, err := store.Default()
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()

	t.Run("a put is path-style, unsigned-payload, and carries its length", func(t *testing.T) {
		if err := b.Put(ctx, "listings/42/front photo.jpg", io.MultiReader(strings.NewReader("hel"), strings.NewReader("lo")), 5, "image/jpeg"); err != nil {
			t.Fatal(err)
		}
		put := f.last()
		if put.method != "PUT" || put.url != "/shop-default/listings/42/front%20photo.jpg" {
			t.Fatalf("%s %s", put.method, put.url)
		}
		if put.headers.Get("Content-Length") != "5" || len(put.headers.Values("Transfer-Encoding")) != 0 || put.headers.Get("X-Amz-Content-Sha256") != "UNSIGNED-PAYLOAD" || put.headers.Get("Content-Type") != "image/jpeg" {
			t.Fatalf("headers %v", put.headers)
		}
		if !authPattern.MatchString(put.headers.Get("Authorization")) {
			t.Fatalf("authorization %s", put.headers.Get("Authorization"))
		}
		if string(put.body) != "hello" {
			t.Fatalf("body %q", put.body)
		}
		o, err := b.Get(ctx, "listings/42/front photo.jpg")
		if err != nil || o == nil {
			t.Fatal(err)
		}
		data, _ := o.Bytes()
		if string(data) != "hello" || o.ContentType != "image/jpeg" || o.Length != 5 || !o.LastKept.Equal(time.Date(2026, 10, 19, 10, 0, 0, 0, time.UTC)) {
			t.Fatalf("got %q %+v", data, o)
		}
	})

	t.Run("a body that ends short is abandoned, and the store keeps nothing", func(t *testing.T) {
		f.reset()
		err := b.Put(ctx, "short", strings.NewReader("ab"), 10, "text/plain")
		if !strings.Contains(refusalOf(t, err).Message, "ended after 2 of its declared 10 bytes") {
			t.Fatal(err)
		}
		time.Sleep(50 * time.Millisecond)
		f.mu.Lock()
		defer f.mu.Unlock()
		if _, kept := f.objects["/shop-default/short"]; kept {
			t.Fatal("the store kept a short object")
		}
		for _, s := range f.seen {
			if s.complete {
				t.Fatal("the store saw a complete request")
			}
		}
	})

	t.Run("stat, delete and nothing", func(t *testing.T) {
		_ = b.Put(ctx, "stat/one", strings.NewReader("four"), 4, "image/png")
		info, err := b.Stat(ctx, "stat/one")
		if err != nil || info.ContentType != "image/png" || info.Length != 4 {
			t.Fatalf("%+v %v", info, err)
		}
		_ = b.Delete(ctx, "stat/one")
		if err := b.Delete(ctx, "stat/one"); err != nil {
			t.Fatal(err)
		}
		if i, err := b.Stat(ctx, "stat/one"); i != nil || err != nil {
			t.Fatal(i, err)
		}
		if o, err := b.Get(ctx, "stat/one"); o != nil || err != nil {
			t.Fatal(o, err)
		}
	})

	t.Run("a listing is read page by page", func(t *testing.T) {
		for _, n := range []string{"paged/c", "paged/a", "paged/e&f", "paged/b", "other/x"} {
			_ = b.Put(ctx, n, strings.NewReader("x"), 1, "text/plain")
		}
		f.reset()
		var names []string
		for e, err := range b.List(ctx, "paged/") {
			if err != nil {
				t.Fatal(err)
			}
			names = append(names, e.Name)
		}
		if strings.Join(names, ",") != "paged/a,paged/b,paged/c,paged/e&f" {
			t.Fatalf("listed %v", names)
		}
		var listings []string
		for _, s := range f.seen {
			if strings.Contains(s.url, "list-type=2") {
				listings = append(listings, s.url)
			}
		}
		if len(listings) != 2 || !strings.Contains(listings[1], "continuation-token=2") {
			t.Fatalf("listings %v", listings)
		}
	})

	t.Run("a refusal from the store is unavailable, naming its code", func(t *testing.T) {
		f.mu.Lock()
		f.refuseWith = 403
		f.mu.Unlock()
		defer func() { f.mu.Lock(); f.refuseWith = 0; f.mu.Unlock() }()
		_, err := b.Get(ctx, "anything")
		if r := refusalOf(t, err); r.Code != Unavailable || !strings.Contains(r.Message, "refused the request (403): AccessDenied") {
			t.Fatal(err)
		}
	})

	t.Run("a signed URL is presigned at the public endpoint; a public bucket's address is its base", func(t *testing.T) {
		u, err := b.SignedURL("a b.jpg", AccessKeep, time.Minute)
		if err != nil {
			t.Fatal(err)
		}
		p := mustURL(t, u)
		if p.Scheme+"://"+p.Host != "https://objects.example.com" || p.EscapedPath() != "/shop-default/a%20b.jpg" || p.Query().Get("X-Amz-Expires") != "60" || p.Query().Get("X-Amz-Signature") == "" {
			t.Fatalf("signed %s", u)
		}
		photos, _ := store.Bucket("photos")
		if a, err := photos.PublicURL("listings/42/front.jpg"); a != "https://photos.example.com/listings/42/front.jpg" || err != nil {
			t.Fatal(a, err)
		}
		if photos.Exposure() != ExposurePublic {
			t.Fatal(photos.Exposure())
		}
	})
}

func TestAnS3StoreThatCannotBeReachedIsUnavailable(t *testing.T) {
	store := s3StoreOf(t, map[string]string{"ANKKA_S3_ENDPOINT": "http://127.0.0.1:1", "ANKKA_S3_REGION": "r", "ANKKA_S3_BUCKET": "b", "ANKKA_S3_ACCESS_KEY": "a", "ANKKA_S3_SECRET_KEY": "b"})
	b, _ := store.Default()
	_, err := b.Get(context.Background(), "x")
	if r := refusalOf(t, err); r.Code != Unavailable || !strings.Contains(r.Message, "cannot be reached") {
		t.Fatal(err)
	}
}

func TestAnS3StoreWithNoCredentialNamesObjectStorageCredential(t *testing.T) {
	store := s3StoreOf(t, map[string]string{"ANKKA_S3_ENDPOINT": "http://127.0.0.1:1", "ANKKA_S3_REGION": "r", "ANKKA_S3_BUCKET": "b"})
	b, _ := store.Default()
	_, err := b.Get(context.Background(), "x")
	if r := refusalOf(t, err); r.Code != Unavailable || !strings.Contains(r.Message, "objectStorageCredential") {
		t.Fatal(err)
	}
}

func TestAListingsXMLIsReadWithItsEntities(t *testing.T) {
	entries, next, err := parseListing([]byte("<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>a&amp;b&#x2F;c</Key><Size>3</Size></Contents></ListBucketResult>"))
	if err != nil || len(entries) != 1 || entries[0].Name != "a&b/c" || entries[0].Length != 3 || next != "" {
		t.Fatalf("%+v %q %v", entries, next, err)
	}
}

// ---- the store a process is given ----

func TestTheVariablesFamilies(t *testing.T) {
	base := map[string]string{"ANKKA_S3_ENDPOINT": "http://garage:3900", "ANKKA_S3_REGION": "garage", "ANKKA_S3_ACCESS_KEY": "a", "ANKKA_S3_SECRET_KEY": "b"}
	env := map[string]string{"ANKKA_S3_BUCKET": "u", "ANKKA_S3_BUCKET_OLD_PHOTOS": "made", "ANKKA_S3_EXPOSURE_OLD_PHOTOS": "signed",
		"ANKKA_S3_ENDPOINT_LEGACY": "https://s3.example.com", "ANKKA_S3_REGION_LEGACY": "eu", "ANKKA_S3_BUCKET_LEGACY": "theirs", "ANKKA_S3_ACCESS_KEY_LEGACY": "c", "ANKKA_S3_SECRET_KEY_LEGACY": "d"}
	for k, v := range base {
		env[k] = v
	}
	s, err := readS3Settings(env)
	if err != nil {
		t.Fatal(err)
	}
	if s.unnamed == nil || s.unnamed.bucket != "u" || len(s.named) != 2 {
		t.Fatalf("%+v", s)
	}
	byName := map[string]s3Named{}
	for _, n := range s.named {
		byName[n.name] = n
	}
	if n := byName["old-photos"]; n.exposure != ExposureSigned || n.target.bucket != "made" || n.target.endpoint.Host != "garage:3900" {
		t.Fatalf("made %+v", n)
	}
	if n := byName["legacy"]; n.exposure != ExposureSupplied || n.target.bucket != "theirs" || n.target.region != "eu" || n.target.credentials.accessKey != "c" {
		t.Fatalf("supplied %+v", n)
	}
	for vars, says := range map[string]string{
		"ANKKA_S3_ENDPOINT=nope": "ANKKA_S3_ENDPOINT is not a URL",
		"ANKKA_S3_BUCKET_X=b":    "ANKKA_S3_ENDPOINT is not set",
		"ANKKA_S3_ENDPOINT=http://g ANKKA_S3_REGION=r ANKKA_S3_BUCKET_X=b ANKKA_S3_EXPOSURE_X=open":   "ANKKA_S3_EXPOSURE_X is 'open'",
		"ANKKA_S3_ENDPOINT=http://g ANKKA_S3_REGION=r ANKKA_S3_BUCKET_X=b ANKKA_S3_EXPOSURE_X=public": "ANKKA_S3_PUBLIC_URL_X is not set",
		"ANKKA_S3_ENDPOINT_Y=http://g": "ANKKA_S3_REGION_Y is not set",
	} {
		e := map[string]string{}
		for _, kv := range strings.Fields(vars) {
			k, v, _ := strings.Cut(kv, "=")
			e[k] = v
		}
		if _, err := readS3Settings(e); err == nil || !strings.Contains(err.Error(), says) {
			t.Errorf("%s: %v, want %q", vars, err, says)
		}
	}
}

func TestTheChoiceOfStore(t *testing.T) {
	dir := t.TempDir()
	for _, c := range []struct {
		env  map[string]string
		want StorageBackend
	}{
		{map[string]string{}, BackendDirectory},
		{map[string]string{"KUBERNETES_SERVICE_HOST": "10.0.0.1"}, BackendUnavailable},
		{map[string]string{"ANKKA_S3_ENDPOINT": "http://g", "ANKKA_S3_REGION": "r", "ANKKA_S3_BUCKET": "b"}, BackendS3},
		{map[string]string{"ANKKA_STORAGE_BACKEND": "memory"}, BackendMemory},
		{map[string]string{"ANKKA_STORAGE_BACKEND": "directory", "KUBERNETES_SERVICE_HOST": "x"}, BackendDirectory},
	} {
		got, store, err := chooseStorage(c.env, dir)
		if err != nil || got != c.want || store == nil {
			t.Errorf("%v: %s %v, want %s", c.env, got, err, c.want)
		}
	}
	_, store, _ := chooseStorage(map[string]string{}, dir)
	if d := store.(*DirectoryStorage); d.Root() != filepath.Join(dir, ".ankka/storage") {
		t.Fatalf("root %s", d.Root())
	}
	_, store, _ = chooseStorage(map[string]string{"ANKKA_STORAGE_DIR": "/var/x"}, dir)
	if d := store.(*DirectoryStorage); d.Root() != "/var/x" {
		t.Fatalf("root %s", d.Root())
	}
	for env, says := range map[string]string{"s3": "ANKKA_S3_ENDPOINT is not set", "disk": "it is one of s3, directory or memory"} {
		if _, _, err := chooseStorage(map[string]string{"ANKKA_STORAGE_BACKEND": env}, dir); err == nil || !strings.Contains(err.Error(), says) {
			t.Errorf("%s: %v", env, err)
		}
	}
	descriptor := `{"service":{"provisionObjectStorage":false,"buckets":[{"name":"photos","exposure":"public"},{"name":"legacy","supplied":{"endpoint":"x"}}]}}`
	if err := os.WriteFile(filepath.Join(dir, "service.json"), []byte(descriptor), 0o644); err != nil {
		t.Fatal(err)
	}
	_, store, err := chooseStorage(map[string]string{"ANKKA_STORAGE_BACKEND": "memory"}, dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Default(); !strings.Contains(err.Error(), "provisionObjectStorage") {
		t.Fatal(err)
	}
	if b, err := store.Bucket("legacy"); err != nil || b.Exposure() != ExposureSupplied {
		t.Fatal(b, err)
	}
	if _, err := store.Bucket("other"); !strings.Contains(err.Error(), "names 'legacy', 'photos'") {
		t.Fatal(err)
	}
}

func TestStorageIsWithheldWhereAHandlerMayNotWait(t *testing.T) {
	m := NewMemoryStorage()
	ctx := WithStorage(context.Background(), m)
	if StorageFrom(ctx) != Storage(m) {
		t.Fatal("the given store")
	}
	if StorageFrom(context.WithValue(ctx, withoutSecretsKey, "an entity")) != nil {
		t.Fatal("an entity was given a store")
	}
}

// ---- the directory store's endpoint ----

func storageRequest(method, route string, args []string, query string, body []byte) *pb.HttpRequest {
	in := &pb.HttpRequest{EndpointId: storageEndpointID, RouteId: method + " " + route, PathArgs: args, Body: body, ContentType: "image/png"}
	q, _ := url.ParseQuery(query)
	for k, vs := range q {
		for _, v := range vs {
			in.Query = append(in.Query, &pb.HttpRequest_Pair{Name: k, Value: v})
		}
	}
	return in
}

func TestTheDirectoryStoresEndpoint(t *testing.T) {
	root := t.TempDir()
	store := newDirectoryStorage(root, &declaredBuckets{unnamed: true, buckets: map[string]Exposure{"photos": ExposurePublic}}, func() string { return "http://127.0.0.1:9000" })
	d := gather(nil)
	storageEndpoint(store).declare(d)
	if len(d.problems) != 0 {
		t.Fatalf("problems %v", d.problems)
	}
	spec := d.spec()
	if len(spec.Endpoints) != 1 || spec.Endpoints[0].GetId() != "ankka-storage" || spec.Endpoints[0].GetPrefix() != "/_ankka/storage" || len(spec.Endpoints[0].Routes) != 32 {
		t.Fatalf("declared %v", spec.Endpoints)
	}
	if got := withoutStorageEndpoint(spec); len(got.Endpoints) != 0 || len(spec.Endpoints) != 1 {
		t.Fatal("stripping the endpoint")
	}
	ctx := context.Background()
	b, _ := store.Default()
	keepURL, _ := b.SignedURL("a b/c.png", AccessKeep, time.Minute)
	readURL, _ := b.SignedURL("a b/c.png", AccessRead, time.Minute)
	params := []string{"_default", "a%20b", "c.png"}
	route := "/{bucket}/{n1}/{n2}"
	answer := func(in *pb.HttpRequest) *pb.HttpResponse {
		t.Helper()
		res, err := d.handleHTTP(ctx, in)
		if err != nil {
			t.Fatal(err)
		}
		return res
	}
	if res := answer(storageRequest("PUT", route, params, mustURL(t, readURL).RawQuery, []byte("png!"))); res.Status != 403 {
		t.Fatalf("a read token kept: %d", res.Status)
	}
	if res := answer(storageRequest("PUT", route, params, mustURL(t, keepURL).RawQuery, []byte("png!"))); res.Status != 200 {
		t.Fatalf("keep: %d %s", res.Status, res.Body)
	}
	if res := answer(storageRequest("GET", route, params, "", nil)); res.Status != 403 {
		t.Fatalf("an unsigned read of a signed bucket: %d", res.Status)
	}
	res := answer(storageRequest("GET", route, params, mustURL(t, readURL).RawQuery, nil))
	if res.Status != 200 || string(res.Body) != "png!" || res.ContentType != "image/png" {
		t.Fatalf("read: %d %s %s", res.Status, res.ContentType, res.Body)
	}
	if res := answer(storageRequest("GET", "/{bucket}/{n1}", []string{"nope", "x"}, "", nil)); res.Status != 404 {
		t.Fatalf("an unknown bucket: %d", res.Status)
	}
	photos, _ := store.Bucket("photos")
	_ = photos.Put(ctx, "front.jpg", bytes.NewReader([]byte("jpg")), 3, "image/jpeg")
	if res := answer(storageRequest("GET", "/{bucket}/{n1}", []string{"photos", "front.jpg"}, "", nil)); res.Status != 200 || string(res.Body) != "jpg" {
		t.Fatalf("a public read: %d", res.Status)
	}
	if res := answer(storageRequest("GET", "/{bucket}/{n1}", []string{"photos", "back.jpg"}, "", nil)); res.Status != 404 {
		t.Fatalf("an absent object: %d", res.Status)
	}
}

func TestAServiceEndpointStillMayNotSitUnderAnkka(t *testing.T) {
	d := gather([]Declaration{NewEndpoint("/_ankka/storage", AllowAll(), Get("/x", func(context.Context, *Request) (string, error) { return "", nil }))})
	if len(d.problems) == 0 || !strings.Contains(fmt.Sprint(d.problems), "/_ankka is the platform's") {
		t.Fatalf("problems %v", d.problems)
	}
}
