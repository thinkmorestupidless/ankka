package samples

import (
	"context"
	"fmt"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// KeepSecret is a credential a person gives the service.
type KeepSecret struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

// docs:start credentials

// CredentialsEndpoint is where a person gives the service a credential it must keep. The value
// goes to the secret store and nowhere else: not to an entity, so it reaches no journal, snapshot
// or view. The name is a query parameter because it may hold a slash.
var CredentialsEndpoint = ankka.NewEndpoint("/credentials", ankka.AllowAll(),
	ankka.Post("/", func(ctx context.Context, _ *ankka.Request, keep KeepSecret) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Put(keep.Name, keep.Value)
	}),
	ankka.Get("/", func(ctx context.Context, req *ankka.Request) (string, error) {
		name := req.Query.Get("name")
		value, found, err := ankka.Secrets(ctx).Get(name)
		if err == nil && !found {
			err = &ankka.HttpProblem{Status: 404, Message: fmt.Sprintf("no secret '%s'", name)}
		}
		return value, err
	}),
	ankka.Delete("/", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Delete(req.Query.Get("name"))
	}),
)

// docs:end credentials

// Charge is a charge to make through a payment provider.
type Charge struct {
	Provider string `json:"provider"`
	Amount   int    `json:"amount"`
}

// ChargeState records that a charge was made, and never the credential it was made with.
type ChargeState struct {
	Provider         string `json:"provider"`
	CredentialLength int    `json:"credentialLength"`
	Status           string `json:"status"`
}

type chargeStep = ankka.StepEffect[ChargeState]
type chargeStart = ankka.WorkflowEffect[ChargeState, ankka.Done]

// docs:start charge-workflow

// ChargeWorkflow charges a customer through a payment provider. The step reads the provider's
// credential from the secret store when it needs it, and records only that it charged.
var ChargeWorkflow = ankka.Workflow[ChargeState]("charge",
	func(string) ChargeState { return ChargeState{Status: "not-started"} },
	ankka.WorkflowCommand("start", func(_ context.Context, _ ChargeState, c Charge) (chargeStart, error) {
		return ankka.WorkflowUpdate[ChargeState, ankka.Done](ChargeState{Provider: c.Provider, Status: "started"}).
			ThenTransitionTo("charge", c).ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Step("charge", func(ctx context.Context, s ChargeState, c Charge) (chargeStep, error) {
		credential, found, err := ankka.Secrets(ctx).Get("provider/" + c.Provider)
		if err != nil {
			return chargeStep{}, err
		}
		if !found {
			return chargeStep{}, fmt.Errorf("no credential for %s", c.Provider)
		}
		// Calling the provider with the credential goes here. The state records that it was
		// charged, and never the credential.
		s.CredentialLength, s.Status = len(credential), "charged"
		return ankka.StepUpdate(s).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ChargeState](ankka.Manifest("charge-state"))),
)

// docs:end charge-workflow

// ChargeService keeps credentials and charges with them.
func ChargeService() *ankka.Service { return ankka.NewService(CredentialsEndpoint, ChargeWorkflow) }
