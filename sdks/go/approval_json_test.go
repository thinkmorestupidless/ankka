package ankka

import (
	"encoding/json"
	"testing"
	"time"
)

// An approval request is written as every other SDK writes one, and read back unchanged.
func TestApprovalRequestJSON(t *testing.T) {
	at := time.UnixMilli(1700000000123).UTC()
	until := at.Add(time.Hour)
	req := ApprovalRequest{ID: "ap-1", Tool: "refund", ArgumentsJSON: `{"order":"o-1"}`, RequestedAt: at, ExpiresAt: &until}

	data, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"id":"ap-1","tool":"refund","arguments":{"order":"o-1"},"requestedAt":1700000000123,"expiresAt":1700003600123}`
	if string(data) != want {
		t.Fatalf("got %s, want %s", data, want)
	}

	var back ApprovalRequest
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatal(err)
	}
	if back.ID != req.ID || back.Tool != req.Tool || back.ArgumentsJSON != req.ArgumentsJSON ||
		!back.RequestedAt.Equal(at) || back.ExpiresAt == nil || !back.ExpiresAt.Equal(until) {
		t.Fatalf("read back %+v, want %+v", back, req)
	}
}

// No arguments are written as an empty object, no time limit as no expiresAt, and arguments that
// are not JSON are refused rather than written as broken JSON.
func TestApprovalRequestJSONEdges(t *testing.T) {
	data, err := json.Marshal(ApprovalRequest{ID: "ap-2", Tool: "t", RequestedAt: time.UnixMilli(5)})
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"id":"ap-2","tool":"t","arguments":{},"requestedAt":5}`; string(data) != want {
		t.Fatalf("got %s, want %s", data, want)
	}
	if _, err := json.Marshal(ApprovalRequest{ID: "ap-3", ArgumentsJSON: "{not json"}); err == nil {
		t.Fatal("arguments that are not JSON were written")
	}
}
