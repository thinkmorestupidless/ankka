package ankka

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// storagePath is where a service serves its directory store's objects.
	storagePath = "/_ankka/storage"
	// defaultSegment is the unnamed bucket's directory and URL segment: no bucket name begins with _.
	defaultSegment = "_default"
	// maxAddressSegments is the deepest object name whose address keeps its slashes. The sidecar
	// matches a process's route by whole segments, so the storage endpoint declares one route per
	// depth up to this; a deeper name's slashes are encoded and it travels as one segment.
	maxAddressSegments = 16
)

// addressOf is an object's name in a directory store's address, as the runtime writes it, while the
// sidecar can route it.
func addressOf(name string) string {
	return sigEncode(name, strings.Count(name, "/")+1 > maxAddressSegments)
}

type dirMeta struct {
	Name        string `json:"name"`
	ContentType string `json:"contentType"`
	Length      int64  `json:"length"`
	LastKept    string `json:"lastKept"`
}

// DirectoryStorage is a service's buckets in a directory on a developer's machine: what a service
// keeps when it runs with no variable of an object store. The runtime's layout exactly, so a Scala
// service and a Go one read each other's: one directory per bucket under the root (_default for the
// unnamed one), an object's name encoded into one file name under objects/ (its hash when too long),
// and beside it under meta/ its name, content type, length and when it was kept. A put writes a
// temporary file and moves it into place, so a body that ends short leaves the old object.
//
// A signed URL and a public address point at the service's own HTTP port, under /_ankka/storage,
// which the SDK serves through the sidecar while this is the service's store.
type DirectoryStorage struct {
	root        string
	declared    *declaredBuckets
	addressBase func() string
	keyOnce     sync.Mutex
	key         []byte
}

func newDirectoryStorage(root string, declared *declaredBuckets, addressBase func() string) *DirectoryStorage {
	abs, err := filepath.Abs(root)
	if err == nil {
		root = abs
	}
	return &DirectoryStorage{root: root, declared: declared, addressBase: addressBase}
}

// NewDirectoryStorage is a directory store under root, with the unnamed bucket and any named one
// asked for, as private, and URLs at address (http://127.0.0.1:9000 when "").
func NewDirectoryStorage(root, address string) *DirectoryStorage {
	if address == "" {
		address = "http://127.0.0.1:9000"
	}
	return newDirectoryStorage(root, nil, func() string { return address })
}

// Root is the directory the store keeps its buckets under.
func (s *DirectoryStorage) Root() string { return s.root }

func (s *DirectoryStorage) Default() (Bucket, error) {
	if s.declared != nil && !s.declared.unnamed {
		return nil, noBucketRefusal()
	}
	return s.bucket("", ExposureSigned), nil
}

func (s *DirectoryStorage) Bucket(name string) (Bucket, error) {
	if s.declared != nil {
		e, ok := s.declared.buckets[name]
		if !ok {
			return nil, unknownBucketRefusal(name, s.declared.names())
		}
		return s.bucket(name, e), nil
	}
	if p := BucketNameProblem(name); p != "" {
		return nil, Refuse(BadRequest, p)
	}
	return s.bucket(name, ExposurePrivate), nil
}

func (s *DirectoryStorage) Buckets() []Bucket {
	if s.declared == nil {
		return nil
	}
	var out []Bucket
	for _, n := range s.declared.names() {
		out = append(out, s.bucket(n, s.declared.buckets[n]))
	}
	return out
}

// bySegment is the bucket a URL's segment names (_default or a bucket's name), or nil.
func (s *DirectoryStorage) bySegment(segment string) *dirBucket {
	var b Bucket
	var err error
	if segment == defaultSegment {
		b, err = s.Default()
	} else {
		b, err = s.Bucket(segment)
	}
	if err != nil {
		return nil
	}
	return b.(*dirBucket)
}

// token is HMAC-SHA256(key, "<segment>/<name>|<access>|<expires>") in hex, over the key kept in the
// root.
func (s *DirectoryStorage) token(segment, name, access string, expires int64) (string, error) {
	key, err := s.signingKey()
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(hmacSHA256(key, segment+"/"+name+"|"+access+"|"+strconv.FormatInt(expires, 10))), nil
}

// admits is whether token signs this access until expires, and expires is still to come.
func (s *DirectoryStorage) admits(segment, name, access string, expires int64, token string) bool {
	if expires < time.Now().Unix() {
		return false
	}
	want, err := s.token(segment, name, access, expires)
	return err == nil && hmac.Equal([]byte(want), []byte(token))
}

// signingKey is the key the store's URLs are signed with, made once and kept in the root so a
// restart honours them.
func (s *DirectoryStorage) signingKey() ([]byte, error) {
	s.keyOnce.Lock()
	defer s.keyOnce.Unlock()
	if s.key != nil {
		return s.key, nil
	}
	file := filepath.Join(s.root, ".key")
	if _, err := os.Stat(file); errors.Is(err, os.ErrNotExist) {
		if err := os.MkdirAll(s.root, 0o755); err != nil {
			return nil, s.failed(err)
		}
		key := make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return nil, s.failed(err)
		}
		part := filepath.Join(s.root, ".key-"+newID())
		if err := os.WriteFile(part, key, 0o600); err != nil {
			return nil, s.failed(err)
		}
		if _, err := os.Stat(file); err == nil {
			_ = os.Remove(part)
		} else if err := os.Rename(part, file); err != nil {
			return nil, s.failed(err)
		}
	}
	key, err := os.ReadFile(file)
	if err != nil {
		return nil, s.failed(err)
	}
	s.key = key
	return key, nil
}

func (s *DirectoryStorage) failed(err error) error {
	var r *RefusalError
	if errors.As(err, &r) {
		return err
	}
	return Refusef(Unavailable, "the directory store at %s cannot be used: %v", s.root, err)
}

func (s *DirectoryStorage) bucket(name string, exposure Exposure) *dirBucket {
	segment := name
	if segment == "" {
		segment = defaultSegment
	}
	return &dirBucket{store: s, name: name, exposure: exposure, segment: segment,
		objects: filepath.Join(s.root, segment, "objects"), metas: filepath.Join(s.root, segment, "meta")}
}

type dirBucket struct {
	store    *DirectoryStorage
	name     string
	exposure Exposure
	segment  string
	objects  string
	metas    string
}

func (b *dirBucket) Name() string       { return b.name }
func (b *dirBucket) Exposure() Exposure { return b.exposure }

func fileOf(name string) string {
	encoded := sigEncode(name, true)
	if len(encoded) <= 200 {
		return encoded
	}
	return "h-" + sha256Hex([]byte(name))
}

func (b *dirBucket) meta(name string) (*dirMeta, error) {
	data, err := os.ReadFile(filepath.Join(b.metas, fileOf(name)+".json"))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, b.store.failed(err)
	}
	var m dirMeta
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, b.store.failed(err)
	}
	return &m, nil
}

func parseKept(s string) time.Time {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Unix(0, 0).UTC()
	}
	return t
}

func (b *dirBucket) Put(_ context.Context, name string, body io.Reader, length int64, contentType string) error {
	if err := checkName(name); err != nil {
		return err
	}
	if length < 0 {
		return negativeLengthRefusal(length)
	}
	scratch := filepath.Join(b.store.root, ".tmp")
	for _, d := range []string{b.objects, b.metas, scratch} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return b.store.failed(err)
		}
	}
	part := filepath.Join(scratch, newID()+".part")
	defer os.Remove(part)
	f, err := os.Create(part)
	if err != nil {
		return b.store.failed(err)
	}
	written, err := copyBody(f, body, length)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return b.store.failed(err)
	}
	if written < length {
		return shortBodyRefusal(length, written)
	}
	m, _ := json.Marshal(dirMeta{Name: name, ContentType: contentType, Length: length, LastKept: time.Now().UTC().Format(time.RFC3339Nano)})
	metaPart := filepath.Join(scratch, newID()+".meta")
	defer os.Remove(metaPart)
	if err := os.WriteFile(metaPart, m, 0o644); err != nil {
		return b.store.failed(err)
	}
	if err := os.Rename(part, filepath.Join(b.objects, fileOf(name))); err != nil {
		return b.store.failed(err)
	}
	if err := os.Rename(metaPart, filepath.Join(b.metas, fileOf(name)+".json")); err != nil {
		return b.store.failed(err)
	}
	return nil
}

func (b *dirBucket) Get(_ context.Context, name string) (*StoredObject, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	m, err := b.meta(name)
	if err != nil || m == nil {
		return nil, err
	}
	f, err := os.Open(filepath.Join(b.objects, fileOf(name)))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, b.store.failed(err)
	}
	return &StoredObject{ContentType: m.ContentType, Length: m.Length, LastKept: parseKept(m.LastKept), Body: f}, nil
}

func (b *dirBucket) Stat(_ context.Context, name string) (*ObjectInfo, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	m, err := b.meta(name)
	if err != nil || m == nil {
		return nil, err
	}
	return &ObjectInfo{Name: m.Name, ContentType: m.ContentType, Length: m.Length, LastKept: parseKept(m.LastKept)}, nil
}

func (b *dirBucket) Delete(_ context.Context, name string) error {
	if err := checkName(name); err != nil {
		return err
	}
	for _, f := range []string{filepath.Join(b.metas, fileOf(name)+".json"), filepath.Join(b.objects, fileOf(name))} {
		if err := os.Remove(f); err != nil && !errors.Is(err, os.ErrNotExist) {
			return b.store.failed(err)
		}
	}
	return nil
}

func (b *dirBucket) List(_ context.Context, prefix string) iter.Seq2[ObjectEntry, error] {
	return func(yield func(ObjectEntry, error) bool) {
		if err := checkPrefix(prefix); err != nil {
			yield(ObjectEntry{}, err)
			return
		}
		found, err := os.ReadDir(b.metas)
		if errors.Is(err, os.ErrNotExist) {
			return
		}
		if err != nil {
			yield(ObjectEntry{}, b.store.failed(err))
			return
		}
		var entries []ObjectEntry
		for _, f := range found {
			data, err := os.ReadFile(filepath.Join(b.metas, f.Name()))
			if err != nil {
				continue
			}
			var m dirMeta
			// A meta file being written, or not one, is skipped, as the runtime skips it.
			if json.Unmarshal(data, &m) != nil || m.Name == "" {
				continue
			}
			if strings.HasPrefix(m.Name, prefix) {
				entries = append(entries, ObjectEntry{Name: m.Name, Length: m.Length, LastKept: parseKept(m.LastKept)})
			}
		}
		sort.Slice(entries, func(i, j int) bool { return entries[i].Name < entries[j].Name })
		for _, e := range entries {
			if !yield(e, nil) {
				return
			}
		}
	}
}

func (b *dirBucket) address(name string) string {
	return strings.TrimSuffix(b.store.addressBase(), "/") + storagePath + "/" + b.segment + "/" + addressOf(name)
}

func (b *dirBucket) SignedURL(name string, access SignedAccess, span time.Duration) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	if err := checkSigned(b.name, b.exposure); err != nil {
		return "", err
	}
	span, err := checkSpan(span)
	if err != nil {
		return "", err
	}
	expires := time.Now().Add(span).Unix()
	token, err := b.store.token(b.segment, name, string(access), expires)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%s?access=%s&expires=%d&token=%s", b.address(name), access, expires, token), nil
}

func (b *dirBucket) PublicURL(name string) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	if b.exposure != ExposurePublic || b.name == "" {
		return "", notPublicRefusal(b.name, b.exposure)
	}
	return b.address(name), nil
}
