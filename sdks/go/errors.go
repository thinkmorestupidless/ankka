package ankka

import (
	"errors"
	"fmt"
	"strings"
)

// ErrorCode is the code of a refusal: what a caller sees, and the HTTP status it becomes at an endpoint.
type ErrorCode int

// The refusal codes, as the protocol numbers them.
const (
	Internal     ErrorCode = 0
	BadRequest   ErrorCode = 1
	Unauthorized ErrorCode = 2
	Forbidden    ErrorCode = 3
	NotFound     ErrorCode = 4
	Conflict     ErrorCode = 5
	Timeout      ErrorCode = 6
	Unavailable  ErrorCode = 7
	// WorkflowFailed: a workflow a caller waited for failed or was deleted (protocol 1.18); the step and
	// the reason are in the refusal's Details.
	WorkflowFailed ErrorCode = 8
	// NotHome: a replicated entity's copy here cannot answer (1.19); the message names its home.
	NotHome ErrorCode = 9
)

var errorCodeNames = map[ErrorCode]string{
	Internal: "INTERNAL", BadRequest: "BAD_REQUEST", Unauthorized: "UNAUTHORIZED", Forbidden: "FORBIDDEN",
	NotFound: "NOT_FOUND", Conflict: "CONFLICT", Timeout: "TIMEOUT", Unavailable: "UNAVAILABLE",
	WorkflowFailed: "WORKFLOW_FAILED", NotHome: "NOT_HOME",
}

var httpStatuses = map[ErrorCode]int{
	Internal: 500, BadRequest: 400, Unauthorized: 401, Forbidden: 403, NotFound: 404, Conflict: 409,
	Timeout: 504, Unavailable: 503, WorkflowFailed: 424, NotHome: 421,
}

func (c ErrorCode) String() string {
	if name, ok := errorCodeNames[c]; ok {
		return name
	}
	return fmt.Sprintf("ErrorCode(%d)", int(c))
}

// HTTPStatus is the status a refusal with this code answers at an endpoint.
func (c ErrorCode) HTTPStatus() int {
	if s, ok := httpStatuses[c]; ok {
		return s
	}
	return 500
}

// RefusalError is a refusal: a handler's on purpose ("quantity must be positive"), with nothing
// persisted, or what a call answers when the component it called refused. A handler refuses by
// returning one, made by Refuse; any other error a handler returns is a failure.
type RefusalError struct {
	Code    ErrorCode
	Message string
	// Details is what a caller reads without parsing the message: a failed workflow's "step",
	// "reason" and "deleted".
	Details map[string]string
}

func (e *RefusalError) Error() string {
	return fmt.Sprintf("refused (%s): %s", e.Code, e.Message)
}

// Refuse is a refusal with code and message, for a handler to return as its error.
func Refuse(code ErrorCode, message string) error {
	return &RefusalError{Code: code, Message: message}
}

// Refusef is Refuse with a formatted message.
func Refusef(code ErrorCode, format string, args ...any) error {
	return &RefusalError{Code: code, Message: fmt.Sprintf(format, args...)}
}

// AsRefusal is the refusal in err's chain, if there is one.
func AsRefusal(err error) (*RefusalError, bool) {
	var r *RefusalError
	if errors.As(err, &r) {
		return r, true
	}
	return nil, false
}

// ProtocolTooOldError is a call the sidecar beside this process cannot make, because it speaks an
// older protocol than the call needs; Needs names the minor ("1.18").
type ProtocolTooOldError struct {
	What    string
	Needs   string
	Message string
}

func (e *ProtocolTooOldError) Error() string {
	msg := fmt.Sprintf("the runtime beside this process is too old for %s, which needs protocol %s; this process speaks %s", e.What, e.Needs, ProtocolVersion)
	if e.Message != "" {
		msg += ": " + e.Message
	}
	return msg
}

// HttpProblem is an endpoint's answer with a status of its own and a plain text body, returned as a
// handler's error. A RefusalError returned from an endpoint answers its code's status.
type HttpProblem struct {
	Status  int
	Message string
}

func (e *HttpProblem) Error() string { return fmt.Sprintf("%d: %s", e.Status, e.Message) }

// Problem is something wrong with a service's declarations, found before it starts.
type Problem struct {
	// Where names the component, endpoint or route, when the problem is one's.
	Where   string
	Message string
}

func (p Problem) String() string {
	if p.Where == "" {
		return p.Message
	}
	return p.Where + ": " + p.Message
}

// ProblemsError is a service that refuses to start, with every problem at once.
type ProblemsError struct{ Problems []Problem }

func (e *ProblemsError) Error() string {
	lines := make([]string, len(e.Problems))
	for i, p := range e.Problems {
		lines[i] = "  - " + p.String()
	}
	return fmt.Sprintf("the service cannot start; %d problem(s):\n%s", len(e.Problems), strings.Join(lines, "\n"))
}
