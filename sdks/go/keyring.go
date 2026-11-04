package ankka

import (
	"context"
	"crypto/rand"
	"encoding/hex"
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

// subjectRef is a subject key's name: the project whose key it is and the data subject. An empty
// project, in a request only, is the process's own.
type subjectRef struct{ project, subject string }

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

const (
	keyCacheMax   = 10000
	keyCacheTTL   = 5 * time.Minute
	keyFetchBound = 30 * time.Second
)

type cachedKey struct {
	key []byte
	at  time.Time
}

// keyring is a key source with a cache. Only keys are cached: "erased" on a read may be a subject
// no key was ever made for, which a later write makes. own is the project this process writes
// envelopes under, learned from the first answer to a request for its own project.
type keyring struct {
	source keySource
	mu     sync.Mutex
	cache  map[subjectRef]cachedKey
	own    string
	now    func() time.Time
}

func newKeyring(source keySource) *keyring {
	return &keyring{source: source, cache: map[subjectRef]cachedKey{}, now: time.Now}
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
		if c, ok := k.cache[named]; ok && named.project != "" && k.now().Sub(c.at) < keyCacheTTL {
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
		a, err := k.source.fetch(ctx, r, create)
		if err != nil {
			return nil, err
		}
		if a.project == "" {
			a.project = r.project
		}
		if a.project == "" {
			// An answer for the own project that names none: nothing to seal under, or cache by.
			continue
		}
		named := subjectRef{project: a.project, subject: r.subject}
		out[named] = a
		k.mu.Lock()
		if r.project == "" && k.own == "" {
			k.own = a.project
		}
		if !a.erased {
			if len(k.cache) >= keyCacheMax {
				clear(k.cache)
			}
			k.cache[named] = cachedKey{key: a.key, at: k.now()}
		}
		k.mu.Unlock()
	}
	return out, nil
}

// drop forgets the key of (project, subject): its subject was erased.
func (k *keyring) drop(project, subject string) {
	k.mu.Lock()
	delete(k.cache, subjectRef{project: project, subject: subject})
	k.mu.Unlock()
}

// cached reports whether the key of (project, subject) is held.
func (k *keyring) cached(project, subject string) bool {
	k.mu.Lock()
	defer k.mu.Unlock()
	_, ok := k.cache[subjectRef{project: project, subject: subject}]
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
	reply, err := rpc.GetSubjectKey(ctx, &pb.SubjectKeyRequest{Subject: ref.subject, Create: create, Project: ref.project})
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
