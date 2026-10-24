package ankka

// A service's buckets, reached by name: StorageFrom(ctx).Default() is the bucket
// provisionObjectStorage asks for, and Bucket("photos") one the descriptor's buckets list names.
// Offered where a wait belongs, as the secret store is (endpoints, workflow steps, consumers, timed
// actions, agents), and never to an entity, a view or a workflow's command. An entity keeps an
// object's name; the bucket keeps the bytes.
//
// A process speaks S3 itself: nothing of an object crosses the sidecar. The stores are the
// runtime's, said again in Go with the same rules and the same words: the S3 store, from the
// ANKKA_S3_* variables; a directory on a developer's machine, whose signed URLs the service serves
// itself at /_ankka/storage through its sidecar; and the memory store the test kits use.
//
// Every call refuses with a *RefusalError: BadRequest for a name, a prefix, a length or a span that
// breaks a rule, and for a signed URL or an address the bucket's exposure does not allow;
// Unavailable when the store cannot be reached, when the service has no such bucket, or when it has
// no storage credential; Internal for an answer the client cannot read.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

// Exposure is how a bucket is reached from outside the installation.
type Exposure string

const (
	ExposurePrivate  Exposure = "private"
	ExposureSigned   Exposure = "signed"
	ExposurePublic   Exposure = "public"
	ExposureSupplied Exposure = "supplied"
)

// SignedAccess is what a signed URL lets its holder do with one object.
type SignedAccess string

const (
	AccessRead SignedAccess = "read"
	AccessKeep SignedAccess = "keep"
)

// StoredObject is an object read from a bucket. Read its Body once and close it.
type StoredObject struct {
	ContentType string
	Length      int64
	LastKept    time.Time
	Body        io.ReadCloser
}

// Bytes reads the whole body and closes it.
func (o *StoredObject) Bytes() ([]byte, error) {
	defer o.Body.Close()
	return io.ReadAll(o.Body)
}

// ObjectInfo is what a bucket keeps under a name, without the bytes.
type ObjectInfo struct {
	Name        string
	ContentType string
	Length      int64
	LastKept    time.Time
}

// ObjectEntry is one object in a listing.
type ObjectEntry struct {
	Name     string
	Length   int64
	LastKept time.Time
}

// Bucket is one bucket. Its objects are named with / between segments, so a listing by prefix reads
// like a directory; it is not one. A put replaces the whole object or leaves the old one.
type Bucket interface {
	// Name is the descriptor's name for it; "" for the unnamed bucket.
	Name() string
	Exposure() Exposure
	// Put keeps length bytes of body under name, as contentType, replacing what was there. A body
	// that ends before length fails and keeps nothing, and the old object, if any, stays.
	Put(ctx context.Context, name string, body io.Reader, length int64, contentType string) error
	// Get is the object kept under name, or nil when there is none.
	Get(ctx context.Context, name string) (*StoredObject, error)
	// Stat is what is kept under name, without its bytes, or nil.
	Stat(ctx context.Context, name string) (*ObjectInfo, error)
	// List is every object whose name begins with prefix, in name order; pages are read as it is
	// walked.
	List(ctx context.Context, prefix string) iter.Seq2[ObjectEntry, error]
	// Delete removes what is kept under name. Removing nothing is not an error.
	Delete(ctx context.Context, name string) error
	// SignedURL is a URL a browser may use, until span has passed (15 minutes when 0, at most seven
	// days), to read or keep the object under name. Computed: no request reaches the store. A signed
	// bucket's, or the unnamed bucket's when the service sets exposeObjectStorage.
	SignedURL(name string, access SignedAccess, span time.Duration) (string, error)
	// PublicURL is the address a browser reads the object under name at, with no signature: a public
	// bucket's only.
	PublicURL(name string) (string, error)
}

// Storage is a service's buckets: the unnamed one, and the named ones by name.
type Storage interface {
	// Default is the unnamed bucket, refused, naming provisionObjectStorage, when there is none.
	Default() (Bucket, error)
	// Bucket is a bucket the descriptor's buckets list names, refused, naming the ones it does name,
	// otherwise.
	Bucket(name string) (Bucket, error)
	// Buckets are every named bucket, in name order.
	Buckets() []Bucket
}

// ---- the rules and the words ----

const (
	// MaxObjectNameBytes is the longest object name, in bytes of UTF-8.
	MaxObjectNameBytes = 1024
	// MaxSignedURLSpan is the longest a signed URL is made for: seven days.
	MaxSignedURLSpan = 7 * 24 * time.Hour
	// DefaultSignedURLSpan is a signed URL's span when none is given: fifteen minutes.
	DefaultSignedURLSpan = 15 * time.Minute
	bucketNameRule       = "lower-case letters, digits and '-', 1 to 32 characters"
)

func controlChar(r rune) bool { return r < ' ' || r == 0x7f }

func shown(text string) string {
	var b strings.Builder
	for _, r := range text {
		if controlChar(r) {
			r = '?'
		}
		b.WriteRune(r)
	}
	// As the runtime counts: UTF-16 units.
	units := utf16.Encode([]rune(b.String()))
	if len(units) > 60 {
		return string(utf16.Decode(units[:60])) + "…"
	}
	return b.String()
}

func nameRuleProblem(subject, text string, trailingSlash bool) string {
	body := text
	if trailingSlash {
		body = strings.TrimSuffix(text, "/")
	}
	segments := strings.Split(body, "/")
	var found string
	switch {
	case text == "":
		found = subject + " must not be empty"
	case len(text) > MaxObjectNameBytes:
		found = fmt.Sprintf("%s is at most %d bytes as UTF-8; this one is %d", subject, MaxObjectNameBytes, len(text))
	case strings.HasPrefix(text, "/"):
		found = subject + " must not begin with '/'"
	case strings.IndexFunc(text, controlChar) >= 0:
		found = subject + " must not hold a control character"
	case slices.Contains(segments, ""):
		found = subject + " must not have an empty segment"
	case slices.Contains(segments, ".") || slices.Contains(segments, ".."):
		found = subject + " must not have a segment '.' or '..'"
	default:
		return ""
	}
	return found + " (\"" + shown(text) + "\")"
}

// ObjectNameProblem is what is wrong with an object's name, or "".
func ObjectNameProblem(name string) string {
	if !utf8.ValidString(name) {
		return "an object's name is UTF-8 (\"" + shown(name) + "\")"
	}
	return nameRuleProblem("an object's name", name, false)
}

// PrefixProblem is what is wrong with a listing's prefix, or "". Empty is everything.
func PrefixProblem(prefix string) string {
	if prefix == "" {
		return ""
	}
	return nameRuleProblem("a prefix", prefix, true)
}

var bucketNamePattern = regexp.MustCompile(`^[a-z0-9-]{1,32}$`)

// BucketNameProblem is what is wrong with a bucket's name, or "", in the descriptor's words.
func BucketNameProblem(name string) string {
	if bucketNamePattern.MatchString(name) {
		return ""
	}
	return fmt.Sprintf("'%s' is not a bucket name: %s", shown(name), bucketNameRule)
}

// SpanProblem is what is wrong with a signed URL's span, or "".
func SpanProblem(span time.Duration) string {
	seconds := int64(span / time.Second)
	switch {
	case span <= 0 || seconds <= 0:
		return fmt.Sprintf("a signed URL's span is positive; this one is %d seconds", seconds)
	case span > MaxSignedURLSpan:
		return fmt.Sprintf("a signed URL is made for at most 7 days (%d seconds); this one asks for %d", int64(MaxSignedURLSpan/time.Second), seconds)
	}
	return ""
}

func checkName(name string) error {
	if p := ObjectNameProblem(name); p != "" {
		return Refuse(BadRequest, p)
	}
	return nil
}

func checkPrefix(prefix string) error {
	if p := PrefixProblem(prefix); p != "" {
		return Refuse(BadRequest, p)
	}
	return nil
}

func checkSpan(span time.Duration) (time.Duration, error) {
	if span == 0 {
		span = DefaultSignedURLSpan
	}
	if p := SpanProblem(span); p != "" {
		return 0, Refuse(BadRequest, p)
	}
	return span, nil
}

// The storage client's refusals, in the runtime's words.

func noBucketRefusal() error {
	return Refuse(Unavailable, "this service has no bucket: it asks for one with provisionObjectStorage, or names one in buckets")
}

func unknownBucketRefusal(name string, known []string) error {
	known = append([]string(nil), known...)
	sort.Strings(known)
	named := "names none"
	if len(known) > 0 {
		quoted := make([]string, len(known))
		for i, k := range known {
			quoted[i] = "'" + k + "'"
		}
		named = "names " + strings.Join(quoted, ", ")
	}
	return Refusef(Unavailable, "this service has no bucket named '%s'; its descriptor %s", name, named)
}

func noCredentialRefusal() error {
	return Refuse(Unavailable, "this service has a bucket and no storage credential to sign with: it sets objectStorageCredential to false, "+
		"so it reaches its bucket as its workload identity, with its store's own client")
}

func notExposedRefusal() error {
	return Refuse(BadRequest, "the service's bucket is not reachable from the internet, so it signs no URL: the descriptor sets exposeObjectStorage, or names a signed bucket")
}

func notSignedRefusal(bucket string, exposure Exposure) error {
	return Refusef(BadRequest, "bucket '%s' is %s; a signed URL is made for a signed bucket", bucket, exposure)
}

func notPublicRefusal(bucket string, exposure Exposure) error {
	label := "the service's unnamed bucket"
	if bucket != "" {
		label = "bucket '" + bucket + "'"
	}
	return Refusef(BadRequest, "%s is %s; an address with no signature is a public bucket's", label, exposure)
}

func unreachableRefusal(endpoint, cause string) error {
	return Refusef(Unavailable, "the object store at %s cannot be reached: %s", endpoint, cause)
}

func refusedByStoreRefusal(endpoint string, status int, detail string) error {
	if detail != "" {
		detail = ": " + detail
	}
	return Refusef(Unavailable, "the object store at %s refused the request (%d)%s", endpoint, status, detail)
}

func unreadableRefusal(endpoint, cause string) error {
	return Refusef(Internal, "the object store at %s answered with something unreadable: %s", endpoint, cause)
}

func shortBodyRefusal(declared, read int64) error {
	return Refusef(BadRequest, "the stream ended after %d of its declared %d bytes; nothing was kept", read, declared)
}

func negativeLengthRefusal(length int64) error {
	return Refusef(BadRequest, "an object's length is not negative; this one is %d", length)
}

func checkSigned(bucket string, exposure Exposure) error {
	if exposure == ExposureSigned || exposure == ExposureSupplied {
		return nil
	}
	if bucket == "" {
		return notExposedRefusal()
	}
	return notSignedRefusal(bucket, exposure)
}

// readBody reads exactly length bytes of body, refusing a body that ends before them; it never reads
// past length.
func readBody(body io.Reader, length int64) ([]byte, error) {
	if length < 0 {
		return nil, negativeLengthRefusal(length)
	}
	if body == nil {
		body = strings.NewReader("")
	}
	buf, err := io.ReadAll(io.LimitReader(body, length))
	if err != nil {
		return nil, err
	}
	if int64(len(buf)) < length {
		return nil, shortBodyRefusal(length, int64(len(buf)))
	}
	return buf, nil
}

// copyBody writes exactly length bytes of body to w, answering how many it wrote.
func copyBody(w io.Writer, body io.Reader, length int64) (int64, error) {
	if body == nil {
		body = strings.NewReader("")
	}
	return io.Copy(w, io.LimitReader(body, length))
}

// ---- no store at all ----

type noStorage struct{}

func (noStorage) Default() (Bucket, error)           { return nil, noBucketRefusal() }
func (noStorage) Bucket(name string) (Bucket, error) { return nil, unknownBucketRefusal(name, nil) }
func (noStorage) Buckets() []Bucket                  { return nil }

// NoStorage is the store of a service with no bucket: every call refuses, naming
// provisionObjectStorage.
var NoStorage Storage = noStorage{}

// ---- what service.json declares ----

// declaredBuckets is what a descriptor declares: whether the unnamed bucket is asked for, and each
// named one.
type declaredBuckets struct {
	unnamed bool
	buckets map[string]Exposure
}

func (d *declaredBuckets) names() []string {
	out := make([]string, 0, len(d.buckets))
	for n := range d.buckets {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// readDeclaredBuckets reads the buckets service.json in dir declares, so the directory and memory
// stores refuse the names a deployed service would; nil when there is no such file.
func readDeclaredBuckets(dir string) (*declaredBuckets, error) {
	file := filepath.Join(dir, "service.json")
	data, err := os.ReadFile(file)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("%s cannot be read: %w", file, err)
	}
	var parsed struct {
		Service struct {
			ProvisionObjectStorage any `json:"provisionObjectStorage"`
			Buckets                []struct {
				Name     any `json:"name"`
				Exposure any `json:"exposure"`
				Supplied any `json:"supplied"`
			} `json:"buckets"`
		} `json:"service"`
	}
	if err := json.Unmarshal(data, &parsed); err != nil {
		return nil, fmt.Errorf("%s cannot be read: %w", file, err)
	}
	out := &declaredBuckets{unnamed: parsed.Service.ProvisionObjectStorage == true, buckets: map[string]Exposure{}}
	for _, b := range parsed.Service.Buckets {
		name := fmt.Sprint(b.Name)
		if b.Name == nil {
			name = ""
		}
		if b.Supplied != nil {
			out.buckets[name] = ExposureSupplied
			continue
		}
		word, _ := b.Exposure.(string)
		if b.Exposure == nil {
			word = "private"
		}
		switch Exposure(word) {
		case ExposurePrivate, ExposureSigned, ExposurePublic:
			out.buckets[name] = Exposure(word)
		default:
			return nil, fmt.Errorf("buckets: '%s' has exposure '%s'; it is one of private, signed or public", name, word)
		}
	}
	return out, nil
}

// ---- the store a process is given ----

// StorageBackend is what stands behind every bucket of a service.
type StorageBackend string

const (
	BackendS3          StorageBackend = "s3"
	BackendDirectory   StorageBackend = "directory"
	BackendMemory      StorageBackend = "memory"
	BackendUnavailable StorageBackend = "unavailable"
)

// chooseStorage is the one place a process's store is chosen, as the runtime chooses its own:
// ANKKA_STORAGE_BACKEND when set (s3, directory or memory); otherwise S3 when any ANKKA_S3_* bucket
// is given, the directory store (ANKKA_STORAGE_DIR, default .ankka/storage) on a developer's machine
// (anywhere Kubernetes did not start the process) and no store at all on the platform. An error
// names the variable when the settings cannot be read: a service that cannot reach its buckets does
// not start.
func chooseStorage(env map[string]string, serviceDir string) (StorageBackend, Storage, error) {
	word := strings.TrimSpace(env["ANKKA_STORAGE_BACKEND"])
	settings, err := readS3Settings(env)
	if err != nil {
		return "", nil, err
	}
	anyS3 := settings.unnamed != nil || len(settings.named) > 0
	_, onKubernetes := env["KUBERNETES_SERVICE_HOST"]
	var backend StorageBackend
	switch word {
	case "":
		switch {
		case anyS3:
			backend = BackendS3
		case !onKubernetes:
			backend = BackendDirectory
		default:
			backend = BackendUnavailable
		}
	case "s3":
		if !anyS3 {
			return "", nil, errors.New("ANKKA_STORAGE_BACKEND is s3 and ANKKA_S3_ENDPOINT is not set: the s3 store needs an endpoint")
		}
		backend = BackendS3
	case "directory", "memory":
		backend = StorageBackend(word)
	default:
		return "", nil, fmt.Errorf("ANKKA_STORAGE_BACKEND is '%s'; it is one of s3, directory or memory", word)
	}
	switch backend {
	case BackendS3:
		return backend, newS3Storage(settings), nil
	case BackendDirectory, BackendMemory:
		declared, err := readDeclaredBuckets(serviceDir)
		if err != nil {
			return "", nil, err
		}
		if backend == BackendMemory {
			if declared == nil {
				return backend, NewMemoryStorage(), nil
			}
			m := NewMemoryStorage(MemoryUnnamed(declared.unnamed))
			for n, e := range declared.buckets {
				if err := m.Declare(n, e); err != nil {
					return "", nil, err
				}
			}
			return backend, m, nil
		}
		dir := env["ANKKA_STORAGE_DIR"]
		if dir == "" {
			dir = ".ankka/storage"
		}
		if !filepath.IsAbs(dir) {
			dir = filepath.Join(serviceDir, dir)
		}
		return backend, newDirectoryStorage(dir, declared, func() string { return localAddress(env) }), nil
	}
	return backend, NoStorage, nil
}

// localAddress is where a local service's HTTP port answers, as the sidecar beside it binds it.
func localAddress(env map[string]string) string {
	host := env["ANKKA_HTTP_INTERFACE"]
	if host == "" || host == "0.0.0.0" {
		host = "127.0.0.1"
	}
	port := env["ANKKA_HTTP_PORT"]
	if port == "" {
		port = "9000"
	}
	return "http://" + host + ":" + port
}

var (
	processStoreMu sync.RWMutex
	processStore   Storage = NoStorage
)

func setProcessStorage(s Storage) {
	processStoreMu.Lock()
	processStore = s
	processStoreMu.Unlock()
}

type storageKeyType struct{}

// WithStorage is ctx whose handlers are given s in place of the process's store, as a test kit
// gives a handler its own.
func WithStorage(ctx context.Context, s Storage) context.Context {
	return context.WithValue(ctx, storageKeyType{}, s)
}

// StorageFrom is the service's buckets, as the handler ctx belongs to; nil for an entity, a view or
// a workflow's command, which may not wait on a store.
func StorageFrom(ctx context.Context) Storage {
	if _, withheld := ctx.Value(withoutSecretsKey).(string); withheld {
		return nil
	}
	if s, ok := ctx.Value(storageKeyType{}).(Storage); ok {
		return s
	}
	processStoreMu.RLock()
	defer processStoreMu.RUnlock()
	return processStore
}
