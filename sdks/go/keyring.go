package ankka

import (
	"container/list"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// The process's keyring (feature 042): the subject keys its codecs seal and open personal fields
// with, asked of the sidecar (Client.GetSubjectKey, protocol 1.23), cached here, and dropped when
// the sidecar says a subject was erased (Client.SubjectNotices). A codec has no context, so the
// keyring is the one piece of process-wide state the SDK keeps, as Scala's Keyring.current is.

// subjectRef is a subject key's name: the project whose key it is, the data subject, and the
// installation whose keyring keeps it. An empty project, in a request only, is the process's own.
// An empty home is this installation's keyring; any other is a replicated entity's home elsewhere
// (FR-032), asked through the sidecar's relay and never made there, whose keys never mix with this
// installation's keys of the same subject.
type subjectRef struct{ project, subject, home string }

// keyAnswer is a key, or that the subject is erased, and the project the answer is for: the
// process's own when the request left it empty, which is how a process learns its project.
type keyAnswer struct {
	key     []byte
	erased  bool
	project string
}

// keySource is where keys come from: the sidecar, or a test's.
type keySource interface {
	fetch(ctx context.Context, ref subjectRef, create bool) (keyAnswer, error)
}

// keyringSettings is how a process holds the keys it fetched: the runtime's ankka.keyring settings,
// by the same names and defaults.
type keyringSettings struct {
	maxKeys     int           // ankka.keyring.cache.max-keys: the least recently used goes first
	ttl         time.Duration // ankka.keyring.cache.ttl: an own project's key, before it is asked again
	foreignTTL  time.Duration // ankka.keyring.cache.foreign-ttl: another project's key, which no notice reaches
	outageBound time.Duration // ankka.keyring.cache.outage-bound: an own key still served after its fetch while the keyring cannot be reached
	noticeBound time.Duration // ankka.keyring.notice-bound: what the outage bound may not exceed
}

var defaultKeyringSettings = keyringSettings{
	maxKeys:     10000,
	ttl:         5 * time.Minute,
	foreignTTL:  30 * time.Second,
	outageBound: 60 * time.Second,
	noticeBound: 60 * time.Second,
}

// check is why s cannot be used, naming the setting, or nil.
func (s keyringSettings) check() error {
	if s.maxKeys < 1 {
		return fmt.Errorf("ankka.keyring.cache.max-keys must be at least 1, was %d", s.maxKeys)
	}
	for _, d := range []struct {
		name string
		v    time.Duration
	}{{"cache.ttl", s.ttl}, {"cache.foreign-ttl", s.foreignTTL}, {"cache.outage-bound", s.outageBound}, {"notice-bound", s.noticeBound}} {
		if d.v <= 0 {
			return fmt.Errorf("ankka.keyring.%s must be positive, was %s", d.name, d.v)
		}
	}
	if s.outageBound > s.noticeBound {
		return fmt.Errorf("ankka.keyring.cache.outage-bound (%s) must not exceed ankka.keyring.notice-bound (%s)", s.outageBound, s.noticeBound)
	}
	return nil
}

const keyFetchBound = 30 * time.Second

// fetchAttempts is how many times a fetch overtaken by a notice is made again before it is
// unavailable.
const fetchAttempts = 3

type cachedKey struct {
	ref     subjectRef
	key     []byte
	at      time.Time
	foreign bool
}

// flight is one fetch out: dropped once a notice (or the end of the stream) dropped its subject
// while it was, so its answer may be the destroyed key and is not believed.
type flight struct {
	subject string
	dropped bool
}

// keyring is a key source with a cache under the runtime's rules: at most maxKeys, the least
// recently used going first; an own project's key fresh for ttl, and served while the keyring
// cannot be reached until outageBound after its fetch; another project's for foreignTTL and no
// longer. Only keys are cached: "erased" on a read may be a subject no key was ever made for, or a
// grant refused, which a later read asks again about. own is the project this process writes
// envelopes under, learned from the first answer to a request for its own project.
type keyring struct {
	source   keySource
	settings keyringSettings
	mu       sync.Mutex
	cache    map[subjectRef]*list.Element // of *cachedKey
	recency  *list.List                   // front: most recently used
	flights  map[*flight]struct{}
	own      string
	now      func() time.Time
}

func newKeyring(source keySource) *keyring {
	return &keyring{source: source, settings: defaultKeyringSettings, cache: map[subjectRef]*list.Element{},
		recency: list.New(), flights: map[*flight]struct{}{}, now: time.Now}
}

// ownProject is the project this process writes envelopes under, once a key answer has said it.
func (k *keyring) ownProject() (string, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.own == "" {
		return "", keyringUnavailable("no key answer has told this process its project yet")
	}
	return k.own, nil
}

// lifetime is how long c is believed without asking; held with k.mu.
func (k *keyring) lifetime(c *cachedKey) time.Duration {
	if c.foreign {
		return k.settings.foreignTTL
	}
	return k.settings.ttl
}

// lookup is the key held for ref while it is fresh, or, with outage, while the outage bound still
// lets it serve; held with k.mu.
func (k *keyring) lookup(ref subjectRef, outage bool) (*cachedKey, bool) {
	e, ok := k.cache[ref]
	if !ok {
		return nil, false
	}
	c := e.Value.(*cachedKey)
	age := k.now().Sub(c.at)
	if age < k.lifetime(c) || (outage && !c.foreign && age < k.settings.outageBound) {
		k.recency.MoveToFront(e)
		return c, true
	}
	if !outage {
		return nil, false
	}
	k.remove(ref)
	return nil, false
}

// remove forgets ref; held with k.mu.
func (k *keyring) remove(ref subjectRef) {
	if e, ok := k.cache[ref]; ok {
		k.recency.Remove(e)
		delete(k.cache, ref)
	}
}

// put holds key for ref, letting the least recently used go when full; held with k.mu.
func (k *keyring) put(ref subjectRef, key []byte) {
	k.remove(ref)
	// Another home's key is held as another project's is: no notice of that keyring reaches this
	// process, so it lives for the foreign TTL and is never served through an outage.
	c := &cachedKey{ref: ref, key: key, at: k.now(), foreign: k.own == "" || ref.project != k.own || ref.home != ""}
	k.cache[ref] = k.recency.PushFront(c)
	for len(k.cache) > k.settings.maxKeys {
		eldest := k.recency.Back()
		k.recency.Remove(eldest)
		delete(k.cache, eldest.Value.(*cachedKey).ref)
	}
}

// prefetch answers every ref, from the cache or the source, before a codec runs. A ref with an
// empty project is the process's own: it is asked with an empty project, and answered, and
// cached, under the project the answer names. Every answer is keyed by its answered project.
func (k *keyring) prefetch(refs []subjectRef, create bool) (map[subjectRef]keyAnswer, error) {
	out := make(map[subjectRef]keyAnswer, len(refs))
	var missing []subjectRef
	k.mu.Lock()
	for _, r := range refs {
		named := r
		if named.project == "" {
			named.project = k.own
		}
		if c, ok := k.lookup(named, false); ok && named.project != "" {
			out[named] = keyAnswer{key: c.key, project: named.project}
		} else {
			missing = append(missing, r)
		}
	}
	k.mu.Unlock()
	if len(missing) == 0 {
		return out, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), keyFetchBound)
	defer cancel()
	for _, r := range missing {
		// Another installation's keyring is read only to this one: a follower never makes a key.
		a, err := k.fetchOne(ctx, r, create && r.home == "")
		if err != nil {
			var refusal *RefusalError
			if !errors.As(err, &refusal) || refusal.Code != Unavailable {
				return nil, err
			}
			// The keyring cannot be reached: a held own key serves within the outage bound.
			k.mu.Lock()
			named := r
			if named.project == "" {
				named.project = k.own
			}
			c, ok := k.lookup(named, true)
			k.mu.Unlock()
			if !ok || named.project == "" {
				return nil, err
			}
			out[named] = keyAnswer{key: c.key, project: named.project}
			continue
		}
		if a.project == "" {
			a.project = r.project
		}
		if a.project == "" {
			// An answer for the own project that names none: nothing to seal under, or cache by.
			continue
		}
		named := subjectRef{project: a.project, subject: r.subject, home: r.home}
		out[named] = a
		k.mu.Lock()
		if r.project == "" && k.own == "" {
			k.own = a.project
		}
		if !a.erased {
			k.put(named, a.key)
		} else {
			k.remove(named)
		}
		k.mu.Unlock()
	}
	return out, nil
}

// fetchOne asks the source for r, again when a notice dropped its subject while the fetch was out.
func (k *keyring) fetchOne(ctx context.Context, r subjectRef, create bool) (keyAnswer, error) {
	for range fetchAttempts {
		f := &flight{subject: r.subject}
		k.mu.Lock()
		k.flights[f] = struct{}{}
		k.mu.Unlock()
		a, err := k.source.fetch(ctx, r, create)
		k.mu.Lock()
		delete(k.flights, f)
		dropped := f.dropped
		k.mu.Unlock()
		if err != nil || !dropped {
			return a, err
		}
	}
	return keyAnswer{}, keyringUnavailable(fmt.Sprintf("the key of '%s' was dropped by a notice each time it was fetched", r.subject))
}

// dropAll forgets every key: what the process does when its notice stream ends or opens again, since
// the sidecar counts a closed stream as acked and hands a notice sent while none was held never again.
func (k *keyring) dropAll() {
	k.mu.Lock()
	clear(k.cache)
	k.recency.Init()
	for f := range k.flights {
		f.dropped = true
	}
	k.mu.Unlock()
}

// drop forgets the key of (project, subject), from whichever home it was fetched: its subject was
// erased.
func (k *keyring) drop(project, subject string) {
	k.mu.Lock()
	for ref := range k.cache {
		if ref.project == project && ref.subject == subject {
			k.remove(ref)
		}
	}
	for f := range k.flights {
		if f.subject == subject {
			f.dropped = true
		}
	}
	k.mu.Unlock()
}

// cached reports whether the key of (project, subject) is held and fresh.
func (k *keyring) cached(project, subject string) bool {
	k.mu.Lock()
	defer k.mu.Unlock()
	_, ok := k.lookup(subjectRef{project: project, subject: subject}, false)
	return ok
}

// noKeyring is the keyring of a process serving nothing: every key is unavailable.
type noKeyring struct{}

func (noKeyring) fetch(context.Context, subjectRef, bool) (keyAnswer, error) {
	return keyAnswer{}, keyringUnavailable("this process has no sidecar to ask; a codec reaches the keyring once the service is serving, or in a test kit")
}

var (
	processKeyring atomic.Pointer[keyring]
	idleKeyring    = newKeyring(noKeyring{})
)

func currentKeyring() *keyring {
	if k := processKeyring.Load(); k != nil {
		return k
	}
	return idleKeyring
}

// useKeyring makes k the process's keyring, and answers how to put the one before it back.
func useKeyring(k *keyring) (restore func()) {
	before := processKeyring.Swap(k)
	return func() { processKeyring.CompareAndSwap(k, before) }
}

// ── the sidecar's keys ─────────────────────────────────────────────────────────

// sidecarKeys asks the sidecar beside this process for keys (protocol 1.23). Each answer names the
// project it is for, the service's own when the request left it empty.
type sidecarKeys struct {
	client *Client
}

func (s *sidecarKeys) fetch(ctx context.Context, ref subjectRef, create bool) (keyAnswer, error) {
	rpc, err := s.client.stub()
	if err != nil {
		return keyAnswer{}, keyringUnavailable(err.Error())
	}
	reply, err := rpc.GetSubjectKey(ctx, &pb.SubjectKeyRequest{Subject: ref.subject, Create: create, Project: ref.project, Home: ref.home})
	if err != nil {
		if st, ok := status.FromError(err); ok && st.Code() == codes.Unimplemented {
			return keyAnswer{}, keyringUnavailable("the sidecar beside this process speaks a protocol before 1.23, which has no keys")
		}
		return keyAnswer{}, keyringUnavailable(err.Error())
	}
	switch r := reply.GetReply().(type) {
	case *pb.SubjectKeyReply_Key:
		if len(r.Key) != 32 {
			return keyAnswer{}, keyringUnavailable(fmt.Sprintf("the sidecar answered a key of %d bytes for '%s', not 32", len(r.Key), ref.subject))
		}
		return keyAnswer{key: r.Key, project: reply.GetProject()}, nil
	case *pb.SubjectKeyReply_Erased:
		return keyAnswer{erased: true, project: reply.GetProject()}, nil
	case *pb.SubjectKeyReply_Error:
		refusal := errorFromPb(r.Error)
		if refusal.Code == Unavailable {
			return keyAnswer{}, keyringUnavailable(refusal.Message)
		}
		return keyAnswer{}, refusal
	}
	return keyAnswer{}, keyringUnavailable(fmt.Sprintf("the sidecar answered nothing for '%s'", ref.subject))
}

// instanceName names this process to the sidecar's notices: its host and a random part, so two
// processes on one host are two instances.
func instanceName() string {
	host, _ := os.Hostname()
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return host + "-" + hex.EncodeToString(b)
}

// followNotices holds Client.SubjectNotices open until ctx ends, dropping each erased subject's
// key and then acking the notice: the runtime waits for the ack before it tells the keyring. A
// sidecar before 1.23 has no notices, and the loop ends.
func followNotices(ctx context.Context, client *Client, k *keyring, instance string, log *slog.Logger) {
	backoff := 200 * time.Millisecond
	for ctx.Err() == nil {
		err := noticesOnce(ctx, client, k, instance)
		if ctx.Err() != nil {
			return
		}
		if st, ok := status.FromError(err); ok && st.Code() == codes.Unimplemented {
			log.Info("ankka: the sidecar has no subject notices (protocol before 1.23)")
			return
		}
		log.Debug("ankka: subject notices ended; opening them again", "error", err, "after", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 5*time.Second)
	}
}

func noticesOnce(ctx context.Context, client *Client, k *keyring, instance string) error {
	rpc, err := client.stub()
	if err != nil {
		return err
	}
	stream, err := rpc.SubjectNotices(ctx, &pb.SubjectNoticesRequest{Instance: instance})
	if err != nil {
		return err
	}
	// A key fetched while no stream was held may have been erased since, and so may one held when
	// this stream ends: either way the sidecar hands that notice never again.
	k.dropAll()
	defer k.dropAll()
	for {
		n, err := stream.Recv()
		if err != nil {
			return err
		}
		k.drop(n.GetProject(), n.GetSubject())
		reply, err := rpc.AckSubjectNotice(ctx, &pb.SubjectNoticeAck{Seq: n.GetSeq(), Instance: instance})
		if err != nil {
			return err
		}
		if e := reply.GetError(); e != nil {
			return errorFromPb(e)
		}
	}
}
