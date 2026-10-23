package ankka

import (
	"context"
	"errors"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// ClientKeyStore issues, lists and revokes the service's client keys for its MCP endpoints (1.20).
type ClientKeyStore struct{ ctx context.Context }

// ClientKeys is the client key store, as the handler ctx belongs to.
func ClientKeys(ctx context.Context) *ClientKeyStore { return &ClientKeyStore{ctx: ctx} }

// ClientKeyRequest is what a key is issued for. Exactly one of ExpiresAfter, ExpiresAt and Never.
type ClientKeyRequest struct {
	Subject      string
	ExpiresAfter time.Duration
	ExpiresAt    time.Time
	Never        bool
	// Endpoint is the MCP endpoint's path; required when the service has several.
	Endpoint string
	// Scope names the tools the key may call; empty is every tool.
	Scope []string
	// Attributes become the principal's claims.
	Attributes map[string]string
	Label      string
}

// IssuedClientKey is a key as issued: the key itself is here and nowhere else.
type IssuedClientKey struct {
	ID        string
	ClientKey string
}

// ClientKey is a key as listed: never the key, nor anything it could be derived from.
type ClientKey struct {
	ID         string
	Subject    string
	Attributes map[string]string
	Label      string
	Endpoint   string
	Scope      []string
	IssuedAt   time.Time
	ExpiresAt  *time.Time
	LastUsed   *time.Time
	RevokedAt  *time.Time
}

func (s *ClientKeyStore) stub() (pb.ClientClient, error) {
	_, rpc, err := clientOf(s.ctx)
	return rpc, err
}

// Issue issues a key for req.
func (s *ClientKeyStore) Issue(req ClientKeyRequest) (IssuedClientKey, error) {
	set := 0
	out := &pb.IssueClientKeyRequest{Subject: req.Subject, Scope: req.Scope, Attributes: req.Attributes, Metadata: metadataToPb(MetadataFrom(s.ctx))}
	if req.ExpiresAfter > 0 {
		set++
		out.Expiry = &pb.IssueClientKeyRequest_AfterMillis{AfterMillis: req.ExpiresAfter.Milliseconds()}
	}
	if !req.ExpiresAt.IsZero() {
		set++
		out.Expiry = &pb.IssueClientKeyRequest_AtEpochMillis{AtEpochMillis: req.ExpiresAt.UnixMilli()}
	}
	if req.Never {
		set++
		out.Expiry = &pb.IssueClientKeyRequest_Never{Never: true}
	}
	if set != 1 {
		return IssuedClientKey{}, Refuse(BadRequest, "a client key has exactly one expiry: ExpiresAfter, ExpiresAt or Never")
	}
	if req.Endpoint != "" {
		out.Endpoint = &req.Endpoint
	}
	if req.Label != "" {
		out.Label = &req.Label
	}
	rpc, err := s.stub()
	if err != nil {
		return IssuedClientKey{}, err
	}
	reply, err := rpc.IssueClientKey(s.ctx, out)
	if err != nil {
		return IssuedClientKey{}, tooOld("client keys", "1.20", err)
	}
	switch r := reply.GetReply().(type) {
	case *pb.IssueClientKeyReply_Issued_:
		return IssuedClientKey{ID: r.Issued.GetId(), ClientKey: r.Issued.GetClientKey()}, nil
	case *pb.IssueClientKeyReply_Error:
		return IssuedClientKey{}, errorFromPb(r.Error)
	}
	return IssuedClientKey{}, errors.New("the sidecar answered nothing")
}

// List is every key the service has issued.
func (s *ClientKeyStore) List() ([]ClientKey, error) {
	rpc, err := s.stub()
	if err != nil {
		return nil, err
	}
	reply, err := rpc.ListClientKeys(s.ctx, &pb.ListClientKeysRequest{Metadata: metadataToPb(MetadataFrom(s.ctx))})
	if err != nil {
		return nil, tooOld("client keys", "1.20", err)
	}
	if reply.GetError() != nil {
		return nil, errorFromPb(reply.GetError())
	}
	at := func(ms *int64) *time.Time {
		if ms == nil {
			return nil
		}
		t := time.UnixMilli(*ms).UTC()
		return &t
	}
	out := make([]ClientKey, 0, len(reply.GetKeys()))
	for _, k := range reply.GetKeys() {
		out = append(out, ClientKey{
			ID: k.GetId(), Subject: k.GetSubject(), Attributes: k.GetAttributes(), Label: k.GetLabel(), Endpoint: k.GetEndpoint(), Scope: k.GetScope(),
			IssuedAt: time.UnixMilli(k.GetIssuedAtMillis()).UTC(), ExpiresAt: at(k.ExpiresAtMillis), LastUsed: at(k.LastUsedMillis), RevokedAt: at(k.RevokedAtMillis),
		})
	}
	return out, nil
}

// Revoke revokes the key id; one already revoked is no error.
func (s *ClientKeyStore) Revoke(id string) error {
	rpc, err := s.stub()
	if err != nil {
		return err
	}
	reply, err := rpc.RevokeClientKey(s.ctx, &pb.RevokeClientKeyRequest{Id: id, Metadata: metadataToPb(MetadataFrom(s.ctx))})
	if err != nil {
		return tooOld("client keys", "1.20", err)
	}
	if reply.GetError() != nil {
		return errorFromPb(reply.GetError())
	}
	return nil
}
