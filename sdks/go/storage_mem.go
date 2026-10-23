package ankka

import (
	"bytes"
	"context"
	"io"
	"iter"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type kept struct {
	contentType string
	lastKept    time.Time
	bytes       []byte
}

// MemoryStorage is a service's buckets in memory: the test kits' store, so a test of a handler that
// keeps or reads an object needs no object store and no Docker. It applies every rule the other
// stores apply, in the same words. A signed URL is computed (http://memory/...) and reaches nothing.
type MemoryStorage struct {
	mu              sync.Mutex
	exposures       map[string]Exposure
	objects         map[string]map[string]kept
	unnamed         bool
	unnamedExposure Exposure
	publicBase      func(bucket string) string
}

// MemoryOption is an option of NewMemoryStorage.
type MemoryOption func(*MemoryStorage)

// MemoryUnnamed says whether the service has the unnamed bucket; it has by default.
func MemoryUnnamed(has bool) MemoryOption { return func(m *MemoryStorage) { m.unnamed = has } }

// MemoryUnnamedExposure is the unnamed bucket's exposure: signed by default, so a test may sign a URL
// for it.
func MemoryUnnamedExposure(e Exposure) MemoryOption {
	return func(m *MemoryStorage) { m.unnamedExposure = e }
}

// MemoryPublicBase is where a public bucket's objects are read: http://memory/public/<bucket> by
// default, reaching nothing.
func MemoryPublicBase(f func(bucket string) string) MemoryOption {
	return func(m *MemoryStorage) { m.publicBase = f }
}

// NewMemoryStorage is an empty memory store with the unnamed bucket and no named one; Declare adds
// them.
func NewMemoryStorage(opts ...MemoryOption) *MemoryStorage {
	m := &MemoryStorage{
		exposures:       map[string]Exposure{},
		objects:         map[string]map[string]kept{},
		unnamed:         true,
		unnamedExposure: ExposureSigned,
		publicBase:      func(bucket string) string { return "http://memory/public/" + bucket },
	}
	for _, o := range opts {
		o(m)
	}
	return m
}

// Declare declares a named bucket, before or after the service starts.
func (m *MemoryStorage) Declare(name string, exposure Exposure) error {
	if p := BucketNameProblem(name); p != "" {
		return Refuse(BadRequest, p)
	}
	m.mu.Lock()
	m.exposures[name] = exposure
	m.mu.Unlock()
	return nil
}

// Clear forgets every object; the declared buckets stay.
func (m *MemoryStorage) Clear() {
	m.mu.Lock()
	m.objects = map[string]map[string]kept{}
	m.mu.Unlock()
}

func (m *MemoryStorage) Default() (Bucket, error) {
	if !m.unnamed {
		return nil, noBucketRefusal()
	}
	return &memBucket{store: m, exposure: m.unnamedExposure}, nil
}

func (m *MemoryStorage) Bucket(name string) (Bucket, error) {
	m.mu.Lock()
	e, ok := m.exposures[name]
	known := sortedKeys(m.exposures)
	m.mu.Unlock()
	if !ok {
		return nil, unknownBucketRefusal(name, known)
	}
	return &memBucket{store: m, name: name, exposure: e}, nil
}

func (m *MemoryStorage) Buckets() []Bucket {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Bucket
	for _, n := range sortedKeys(m.exposures) {
		out = append(out, &memBucket{store: m, name: n, exposure: m.exposures[n]})
	}
	return out
}

type memBucket struct {
	store    *MemoryStorage
	name     string
	exposure Exposure
}

func (b *memBucket) Name() string       { return b.name }
func (b *memBucket) Exposure() Exposure { return b.exposure }

func (b *memBucket) objects() map[string]kept {
	o, ok := b.store.objects[b.name]
	if !ok {
		o = map[string]kept{}
		b.store.objects[b.name] = o
	}
	return o
}

func (b *memBucket) Put(_ context.Context, name string, body io.Reader, length int64, contentType string) error {
	if err := checkName(name); err != nil {
		return err
	}
	data, err := readBody(body, length)
	if err != nil {
		return err
	}
	b.store.mu.Lock()
	b.objects()[name] = kept{contentType: contentType, lastKept: time.Now().UTC(), bytes: data}
	b.store.mu.Unlock()
	return nil
}

func (b *memBucket) Get(_ context.Context, name string) (*StoredObject, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	b.store.mu.Lock()
	k, ok := b.objects()[name]
	b.store.mu.Unlock()
	if !ok {
		return nil, nil
	}
	return &StoredObject{ContentType: k.contentType, Length: int64(len(k.bytes)), LastKept: k.lastKept, Body: io.NopCloser(bytes.NewReader(k.bytes))}, nil
}

func (b *memBucket) Stat(_ context.Context, name string) (*ObjectInfo, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	b.store.mu.Lock()
	k, ok := b.objects()[name]
	b.store.mu.Unlock()
	if !ok {
		return nil, nil
	}
	return &ObjectInfo{Name: name, ContentType: k.contentType, Length: int64(len(k.bytes)), LastKept: k.lastKept}, nil
}

func (b *memBucket) Delete(_ context.Context, name string) error {
	if err := checkName(name); err != nil {
		return err
	}
	b.store.mu.Lock()
	delete(b.objects(), name)
	b.store.mu.Unlock()
	return nil
}

func (b *memBucket) List(_ context.Context, prefix string) iter.Seq2[ObjectEntry, error] {
	if err := checkPrefix(prefix); err != nil {
		return func(yield func(ObjectEntry, error) bool) { yield(ObjectEntry{}, err) }
	}
	b.store.mu.Lock()
	var entries []ObjectEntry
	for n, k := range b.objects() {
		if strings.HasPrefix(n, prefix) {
			entries = append(entries, ObjectEntry{Name: n, Length: int64(len(k.bytes)), LastKept: k.lastKept})
		}
	}
	b.store.mu.Unlock()
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name < entries[j].Name })
	return func(yield func(ObjectEntry, error) bool) {
		for _, e := range entries {
			if !yield(e, nil) {
				return
			}
		}
	}
}

func (b *memBucket) SignedURL(name string, access SignedAccess, span time.Duration) (string, error) {
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
	segment := b.name
	if segment == "" {
		segment = "default"
	}
	expires := time.Now().Add(span).Unix()
	return "http://memory/" + segment + "/" + sigEncode(name, false) + "?access=" + string(access) + "&expires=" + strconv.FormatInt(expires, 10), nil
}

func (b *memBucket) PublicURL(name string) (string, error) {
	if err := checkName(name); err != nil {
		return "", err
	}
	if b.name == "" || b.exposure != ExposurePublic {
		return "", notPublicRefusal(b.name, b.exposure)
	}
	return strings.TrimSuffix(b.store.publicBase(b.name), "/") + "/" + sigEncode(name, false), nil
}
