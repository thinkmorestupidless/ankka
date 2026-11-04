package testkit

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	tcexec "github.com/testcontainers/testcontainers-go/exec"
)

// Keyring is the keyring of the kit's sidecar (feature 042): the runtime's local keyring, kept in the
// kit's database, reached through the sidecar's local console endpoint. Erase destroys a data
// subject's key at once and sends the notice, as an applied erasure request does; Outage makes the
// keyring refuse every request as unavailable, erasures included, until it ends or Recover ends it.
// An outage is the sidecar's: Restart ends it.
type Keyring struct{ kit *Kit }

// Keyring is the sidecar's keyring: erase a data subject, start or end an outage.
func (k *Kit) Keyring() *Keyring { return &Keyring{kit: k} }

func (r *Keyring) post(ctx context.Context, path string, body any) (map[string]any, error) {
	r.kit.mu.Lock()
	base := r.kit.local
	r.kit.mu.Unlock()
	if base == "" {
		return nil, errors.New("the kit is not started")
	}
	data, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+path, bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	text, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s answered %d: %s", path, res.StatusCode, text)
	}
	var answer map[string]any
	if err := json.Unmarshal(text, &answer); err != nil {
		return nil, fmt.Errorf("%s answered %s: %w", path, text, err)
	}
	return answer, nil
}

// Erase erases subject in the service's project now, and answers the notice's sequence once the
// sidecar has carried it out (or after ten seconds). It fails in an outage.
func (r *Keyring) Erase(ctx context.Context, subject string) (int64, error) {
	answer, err := r.post(ctx, "/_ankka/erasures", map[string]any{"subject": subject})
	if err != nil {
		return 0, err
	}
	seq, ok := answer["seq"].(float64)
	if !ok {
		return 0, fmt.Errorf("the erasure answered no sequence: %v", answer)
	}
	return int64(seq), nil
}

// Outage refuses every request as unavailable for d: a subject whose key the sidecar holds is still
// read and written; one it does not hold is refused with ankka.Unavailable.
func (r *Keyring) Outage(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return errors.New("an outage lasts more than 0 seconds; Recover ends one")
	}
	_, err := r.post(ctx, "/_ankka/keyring/outage", map[string]any{"seconds": d.Seconds()})
	return err
}

// Recover ends an outage now.
func (r *Keyring) Recover(ctx context.Context) error {
	_, err := r.post(ctx, "/_ankka/keyring/outage", map[string]any{"seconds": 0})
	return err
}

// personalValueQuery is one statement that searches every column of every table of the service's
// database, cast to text, for each value as written, as base64 (standard and URL-safe, unpadded) and
// as lower-case hex in the lower-cased text: the rules of the JVM kit's PersonalAssertions, the same
// forms. Each row is "<index of the value> <form> <schema>.<table>.<column>".
func personalValueQuery(values []string) string {
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	var rows []string
	for i, v := range values {
		data := []byte(v)
		forms := [][2]string{
			{"plain", v},
			{"base64", base64.RawStdEncoding.EncodeToString(data)},
			{"base64url", base64.RawURLEncoding.EncodeToString(data)},
			{"hex", hex.EncodeToString(data)},
		}
		for _, f := range forms {
			rows = append(rows, fmt.Sprintf("(%d, %s, %s)", i, quote(f[0]), quote(f[1])))
		}
	}
	return "SELECT f.i || ' ' || f.form_name || ' ' || c.table_schema || '.' || c.table_name || '.' || c.column_name " +
		"FROM information_schema.columns c CROSS JOIN (VALUES " + strings.Join(rows, ", ") + ") AS f(i, form_name, form) " +
		"WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema') " +
		"AND (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I WHERE strpos(%s, %L) > 0', " +
		"c.table_schema, c.table_name, " +
		"CASE WHEN f.form_name = 'hex' THEN format('lower(%I::text)', c.column_name) ELSE format('%I::text', c.column_name) END, " +
		"f.form), false, true, '')))[1]::text::int > 0 " +
		"ORDER BY c.table_schema, c.table_name, c.ordinal_position, f.i"
}

// personalValueFailure is the JVM kit's failure message for what personalValueQuery found, or "".
func personalValueFailure(values []string, output string) string {
	var found []string
	for _, line := range strings.Split(output, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		parts := strings.SplitN(line, " ", 3)
		i, err := strconv.Atoi(parts[0])
		if err != nil || len(parts) != 3 || i >= len(values) {
			found = append(found, "  (unreadable) "+line)
			continue
		}
		found = append(found, fmt.Sprintf("  '%s' as %s in %s", values[i], parts[1], parts[2]))
	}
	if len(found) == 0 {
		return ""
	}
	return "a personal value can be read back from what the service kept:\n" + strings.Join(found, "\n")
}

// CheckNoPersonalValue is an error naming each table and column where any of values can be read back
// from the service's database as written, as base64 (standard and URL-safe) or as hex: what a
// personal field must never be (feature 042, FR-030). Every column of every table, cast to text,
// searched by the kit's Postgres itself with the JVM kit's rules. Nil when none is found.
func (k *Kit) CheckNoPersonalValue(ctx context.Context, values ...string) error {
	if len(values) == 0 {
		return nil
	}
	if k.database == nil {
		return errors.New("the kit is not started")
	}
	cmd := []string{"psql", "-U", "ankka", "-d", "ankka", "-v", "ON_ERROR_STOP=1", "-At", "-c", personalValueQuery(values)}
	code, out, err := k.database.Exec(ctx, cmd, tcexec.Multiplexed())
	if err != nil {
		return fmt.Errorf("the database could not be searched: %w", err)
	}
	text, _ := io.ReadAll(out)
	if code != 0 {
		return fmt.Errorf("the database could not be searched: %s", text)
	}
	if failure := personalValueFailure(values, string(text)); failure != "" {
		return errors.New(failure)
	}
	return nil
}

// AssertNoPersonalValue fails t, naming each table and column, when any of values can be read back
// from the service's database: CheckNoPersonalValue as an assertion.
func (k *Kit) AssertNoPersonalValue(t testing.TB, values ...string) {
	t.Helper()
	if err := k.CheckNoPersonalValue(t.Context(), values...); err != nil {
		t.Error(err)
	}
}
