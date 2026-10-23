package ankka

import (
	"context"
	"errors"
	"fmt"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// MaxSecretValue is the largest secret value, in bytes of UTF-8.
const MaxSecretValue = 65536

// SecretStore is the service's secret store: named text values the runtime keeps in the service's
// own database, encrypted with a key this process never sees. It is offered to endpoints, workflow
// steps, consumers, timed actions and agents; from an entity, a view or a workflow's command every
// call fails, naming which.
type SecretStore struct{ ctx context.Context }

// Secrets is the secret store, as the handler ctx belongs to.
func Secrets(ctx context.Context) *SecretStore { return &SecretStore{ctx: ctx} }

func (s *SecretStore) stub() (pb.ClientClient, error) {
	if who, ok := s.ctx.Value(withoutSecretsKey).(string); ok {
		return nil, fmt.Errorf("the secret store is not offered to %s; read a secret in an endpoint, a step, a consumer, a timed action or an agent and pass what it needs", who)
	}
	_, rpc, err := clientOf(s.ctx)
	return rpc, err
}

// Get is the value kept under name, and whether there is one.
func (s *SecretStore) Get(name string) (string, bool, error) {
	rpc, err := s.stub()
	if err != nil {
		return "", false, err
	}
	reply, err := rpc.GetSecret(s.ctx, &pb.GetSecretRequest{Name: name})
	if err != nil {
		return "", false, tooOld("the secret store", "1.6", err)
	}
	switch r := reply.GetResult().(type) {
	case *pb.GetSecretReply_Value:
		return r.Value, true, nil
	case *pb.GetSecretReply_Absent:
		return "", false, nil
	case *pb.GetSecretReply_Error:
		return "", false, errorFromPb(r.Error)
	}
	return "", false, errors.New("the secret store answered nothing")
}

// Put keeps value under name, replacing what was there.
func (s *SecretStore) Put(name, value string) error {
	if value == "" {
		return Refuse(BadRequest, "a secret's value must not be empty")
	}
	if len(value) > MaxSecretValue {
		return Refusef(BadRequest, "a secret's value is at most %d bytes as UTF-8; this one is %d", MaxSecretValue, len(value))
	}
	rpc, err := s.stub()
	if err != nil {
		return err
	}
	reply, err := rpc.PutSecret(s.ctx, &pb.PutSecretRequest{Name: name, Value: value})
	if err != nil {
		return tooOld("the secret store", "1.6", err)
	}
	if reply.GetError() != nil {
		return errorFromPb(reply.GetError())
	}
	return nil
}

// Delete removes what is kept under name; nothing there is no error.
func (s *SecretStore) Delete(name string) error {
	rpc, err := s.stub()
	if err != nil {
		return err
	}
	reply, err := rpc.DeleteSecret(s.ctx, &pb.DeleteSecretRequest{Name: name})
	if err != nil {
		return tooOld("the secret store", "1.6", err)
	}
	if reply.GetError() != nil {
		return errorFromPb(reply.GetError())
	}
	return nil
}
