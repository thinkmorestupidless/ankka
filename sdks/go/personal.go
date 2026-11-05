package ankka

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"reflect"
	"regexp"
	"strconv"
)

// Personal data (feature 042): a field about one data subject, never held in the clear. Written by
// a codec (an entity's event or state, a workflow's state, a view's row, a component call's input
// or reply, a topic's message) it is the personal envelope of protocol/ENCODING.md, sealed under the
// subject's key; at an endpoint, where no codec is writing, it is the value with its subject beside
// it. Once the subject is erased its key is destroyed, and every envelope of it reads as erased.

// SubjectRule is the rule a data subject keeps, in the words every SDK refuses with.
const SubjectRule = "a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'"

var subjectPattern = regexp.MustCompile(`^[A-Za-z0-9._/:-]{1,253}$`)

// DataSubject is who a personal field is about ("player/8c1f"). Make one with Subject or
// MustSubject; the zero value is no subject, and a personal field holding it cannot be written.
type DataSubject struct{ value string }

// Subject is value as a data subject, or why it cannot be one.
func Subject(value string) (DataSubject, error) {
	if !subjectPattern.MatchString(value) {
		// The rule, never the value: it may be the personal data the caller meant to protect.
		return DataSubject{}, fmt.Errorf("the value given is not a data subject: %s", SubjectRule)
	}
	return DataSubject{value: value}, nil
}

// MustSubject is Subject, panicking with the rule when value is not a data subject.
func MustSubject(value string) DataSubject {
	s, err := Subject(value)
	if err != nil {
		panic(err.Error())
	}
	return s
}

// String is the subject's text.
func (s DataSubject) String() string { return s.value }

// IsZero reports whether s is no subject.
func (s DataSubject) IsZero() bool { return s.value == "" }

// Personal is a personal field holding a T about one data subject: present, or erased once the
// subject's key is destroyed. Read it with Value:
//
//	if email, ok := p.Value(); ok { ... }
//
// Its text (String, and every fmt verb) names the subject and never the value, so a log line holds no
// personal data.
type Personal[T any] struct {
	subject DataSubject
	value   T
	erased  bool
}

// PersonalOf is a present personal field holding value about subject.
func PersonalOf[T any](subject DataSubject, value T) Personal[T] {
	return Personal[T]{subject: subject, value: value}
}

// Subject is who the field is about.
func (p Personal[T]) Subject() DataSubject { return p.subject }

// Value is the field's value, and false once its subject is erased.
func (p Personal[T]) Value() (T, bool) {
	if p.erased {
		var zero T
		return zero, false
	}
	return p.value, true
}

// Erased reports whether the field's subject was erased.
func (p Personal[T]) Erased() bool { return p.erased }

// OrElse is the value, or def once the subject is erased.
func (p Personal[T]) OrElse(def T) T {
	if p.erased {
		return def
	}
	return p.value
}

// String is "Personal(<subject>)", never the value.
func (p Personal[T]) String() string { return "Personal(" + p.subject.value + ")" }

// GoString is String, so %#v holds no value either.
func (p Personal[T]) GoString() string { return p.String() }

// Format writes String for every verb.
func (p Personal[T]) Format(f fmt.State, _ rune) { _, _ = io.WriteString(f, p.String()) }

func (p Personal[T]) personalParts() (subject string, value reflect.Value, erased bool) {
	return p.subject.value, reflect.ValueOf(&p.value).Elem(), p.erased
}

func (Personal[T]) personalElem() reflect.Type { return reflect.TypeFor[T]() }

func (p *Personal[T]) personalSet(subject string, erased bool) reflect.Value {
	p.subject, p.erased = DataSubject{value: subject}, erased
	var zero T
	p.value = zero
	return reflect.ValueOf(&p.value).Elem()
}

// personalValue and personalTarget are how the codec reads and fills a Personal of any T; their
// methods are unexported, so only Personal has them.
type personalValue interface {
	personalParts() (string, reflect.Value, bool)
	personalElem() reflect.Type
}

type personalTarget interface {
	personalSet(subject string, erased bool) reflect.Value
}

var (
	personalValueType  = reflect.TypeFor[personalValue]()
	personalTargetType = reflect.TypeFor[personalTarget]()
)

func isPersonalType(t reflect.Type) bool {
	return t.Kind() == reflect.Struct && t.Implements(personalValueType) && reflect.PointerTo(t).Implements(personalTargetType)
}

// ── the errors every SDK names (contracts/sdk.md) ─────────────────────────────

// PersonalErrorKey is the Details key of a refusal a personal field fails with; its value is
// "unavailable", "erased" or "corrupt".
const PersonalErrorKey = "personal"

func keyringUnavailable(why string) *RefusalError {
	return &RefusalError{Code: Unavailable, Message: "the keyring is unavailable: " + why,
		Details: map[string]string{PersonalErrorKey: "unavailable"}}
}

func subjectErased(subject string) *RefusalError {
	return &RefusalError{Code: Conflict, Message: fmt.Sprintf("the data subject '%s' is erased; nothing was written", subject),
		Details: map[string]string{PersonalErrorKey: "erased"}}
}

func envelopeCorrupt(subject, why string) *RefusalError {
	return &RefusalError{Code: Internal, Message: fmt.Sprintf("the personal envelope of '%s' is corrupt: %s", subject, why),
		Details: map[string]string{PersonalErrorKey: "corrupt"}}
}

// ── the cipher: AES-256-GCM with the associated data of ENCODING.md ───────────

const (
	nonceBytes      = 12
	tagBytes        = 16
	envelopeVersion = 1
)

// personalNonce makes the nonce an envelope is sealed with. Only the fixtures' tests replace it: a
// nonce used twice under one key breaks GCM.
var personalNonce = func() ([]byte, error) {
	n := make([]byte, nonceBytes)
	_, err := rand.Read(n)
	return n, err
}

// associatedData is each part as a 4-byte big-endian length and its UTF-8 bytes: subject, project,
// manifest.
func associatedData(subject, project, manifest string) []byte {
	var out []byte
	for _, part := range []string{subject, project, manifest} {
		out = binary.BigEndian.AppendUint32(out, uint32(len(part)))
		out = append(out, part...)
	}
	return out
}

func gcmOf(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// sealField is nonce ‖ ciphertext ‖ tag.
func sealField(key, nonce, plaintext, ad []byte) ([]byte, error) {
	if len(nonce) != nonceBytes {
		return nil, fmt.Errorf("a nonce is %d bytes, was %d", nonceBytes, len(nonce))
	}
	gcm, err := gcmOf(key)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(append([]byte(nil), nonce...), nonce, plaintext, ad), nil
}

// openField is the plaintext, or false when the tag does not verify under key and ad.
func openField(key, boxed, ad []byte) ([]byte, bool) {
	if len(boxed) < nonceBytes+tagBytes {
		return nil, false
	}
	gcm, err := gcmOf(key)
	if err != nil {
		return nil, false
	}
	plain, err := gcm.Open(nil, boxed[:nonceBytes], boxed[nonceBytes:], ad)
	return plain, err == nil
}

// ── the scope: what a codec writing or reading envelopes holds ────────────────

// sealScope is a codec's call under its manifest, with every key it needs already fetched. A nil
// scope is the edge: the field is written and read as {"subject", "value"}.
type sealScope struct {
	manifest string
	project  string // the project this process writes under; "" when nothing is written
	keys     map[subjectRef]keyAnswer
}

func writePersonal(p *plan, v reflect.Value, buf *bytes.Buffer, path string, sc *sealScope) error {
	subject, value, erased := v.Interface().(personalValue).personalParts()
	if subject == "" {
		return fail(path, "a personal field has no data subject; make it with ankka.PersonalOf(ankka.MustSubject(...), value)")
	}
	if sc == nil {
		buf.WriteString(`{"subject":`)
		_ = writeString(buf, subject, path)
		if !erased {
			buf.WriteString(`,"value":`)
			if err := writeValue(p.elem, value, buf, path, nil); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
		return nil
	}
	var boxed []byte
	if !erased {
		answer, ok := sc.keys[subjectRef{project: sc.project, subject: subject}]
		switch {
		case !ok:
			return keyringUnavailable(fmt.Sprintf("no key was fetched for '%s'", subject))
		case answer.erased:
			return subjectErased(subject)
		}
		var plain bytes.Buffer
		if err := writeValue(p.elem, value, &plain, path, sc); err != nil {
			return err
		}
		nonce, err := personalNonce()
		if err != nil {
			return fail(path, "no nonce: %v", err)
		}
		if boxed, err = sealField(answer.key, nonce, plain.Bytes(), associatedData(subject, sc.project, sc.manifest)); err != nil {
			return fail(path, "cannot seal: %v", err)
		}
	}
	buf.WriteString(`{"$personal":`)
	buf.WriteString(strconv.Itoa(envelopeVersion))
	buf.WriteString(`,"project":`)
	_ = writeString(buf, sc.project, path)
	buf.WriteString(`,"subject":`)
	_ = writeString(buf, subject, path)
	if boxed != nil {
		buf.WriteString(`,"c":`)
		_ = writeString(buf, base64.StdEncoding.EncodeToString(boxed), path)
	}
	buf.WriteByte('}')
	return nil
}

func readPersonal(p *plan, raw any, target reflect.Value, path string, sc *sealScope) error {
	obj, ok := raw.(*jsonObject)
	if !ok {
		if sc == nil {
			return refuse(path, "expected a personal field: {\"subject\", \"value\"}, got %s", kindOfRaw(raw))
		}
		return refuse(path, "expected a personal envelope, got %s", kindOfRaw(raw))
	}
	set := target.Addr().Interface().(personalTarget)
	if sc == nil {
		s, _ := obj.get("subject")
		subject, ok := s.(string)
		if !ok {
			return refuse(path, "a personal field names its data subject")
		}
		if _, err := Subject(subject); err != nil {
			return refuse(path, "%v", err)
		}
		value, present := obj.get("value")
		slot := set.personalSet(subject, !present)
		if present {
			return readValue(p.elem, value, slot, path, nil)
		}
		return nil
	}
	ref, version, boxed, problem := envelopeParts(obj)
	if problem != "" {
		return refuse(path, "%s", problem)
	}
	if version.n != envelopeVersion {
		return envelopeCorrupt(ref.subject, fmt.Sprintf("its version is %s, and this reader knows %d", version.text, envelopeVersion))
	}
	if boxed == nil {
		set.personalSet(ref.subject, true)
		return nil
	}
	answer, fetched := sc.keys[ref]
	if !fetched {
		return keyringUnavailable(fmt.Sprintf("no key was fetched for '%s'", ref.subject))
	}
	if answer.erased {
		set.personalSet(ref.subject, true)
		return nil
	}
	bytes, err := base64.StdEncoding.DecodeString(*boxed)
	if err != nil {
		return envelopeCorrupt(ref.subject, "it is not base64")
	}
	plain, ok := openField(answer.key, bytes, associatedData(ref.subject, ref.project, sc.manifest))
	if !ok {
		return envelopeCorrupt(ref.subject, "it failed authentication")
	}
	inner, err := parseJSON(plain)
	if err != nil {
		return envelopeCorrupt(ref.subject, "its plaintext is not JSON")
	}
	return readValue(p.elem, inner, set.personalSet(ref.subject, false), path, sc)
}

type envelopeVersionText struct {
	n    int
	text string
}

// envelopeParts reads an envelope's clear parts; problem is why it is no envelope at all.
func envelopeParts(obj *jsonObject) (ref subjectRef, version envelopeVersionText, boxed *string, problem string) {
	p, _ := obj.get("project")
	s, _ := obj.get("subject")
	project, okP := p.(string)
	subject, okS := s.(string)
	if !okP || !okS {
		return ref, version, nil, "a personal envelope names its project and its data subject"
	}
	if _, err := Subject(subject); err != nil {
		return ref, version, nil, err.Error()
	}
	ref = subjectRef{project: project, subject: subject}
	version = envelopeVersionText{n: -1, text: "absent"}
	if v, ok := obj.get("$personal"); ok {
		version.text = string(rawText(v))
		if n, ok := v.(json.Number); ok {
			if i, err := strconv.Atoi(string(n)); err == nil {
				version.n = i
			}
		}
	}
	if c, ok := obj.get("c"); ok {
		if text, ok := c.(string); ok {
			boxed = &text
		} else {
			boxed = new(string) // not base64, so corrupt
			*boxed = "!"
		}
	}
	return ref, version, boxed, ""
}

// ── what a codec must fetch before it runs ─────────────────────────────────────

// holdsPersonal reports whether p, or anything inside it, is a personal field.
func holdsPersonal(p *plan, seen map[*plan]bool) bool {
	if p == nil || seen[p] {
		return false
	}
	seen[p] = true
	switch p.kind {
	case kindPersonal:
		return true
	case kindOptional, kindSequence, kindMap:
		return holdsPersonal(p.elem, seen)
	case kindRecord:
		for _, f := range p.fields {
			if holdsPersonal(f.plan, seen) {
				return true
			}
		}
	case kindSum:
		for _, c := range p.sum.byName {
			if holdsPersonal(c.record, seen) {
				return true
			}
		}
	}
	return false
}

// personalInValue walks v by p: every subject a present field is about, and whether any personal
// field (present or erased) is there at all.
func personalInValue(p *plan, v reflect.Value, subjects map[string]bool) (any bool) {
	if !p.personal {
		return false
	}
	switch p.kind {
	case kindPersonal:
		subject, _, erased := v.Interface().(personalValue).personalParts()
		if !erased && subject != "" {
			subjects[subject] = true
		}
		return true
	case kindOptional:
		if v.IsNil() {
			return false
		}
		return personalInValue(p.elem, v.Elem(), subjects)
	case kindSequence:
		if p.length < 0 && v.IsNil() {
			return false
		}
		for i := 0; i < v.Len(); i++ {
			any = personalInValue(p.elem, v.Index(i), subjects) || any
		}
	case kindMap:
		for it := v.MapRange(); it.Next(); {
			any = personalInValue(p.elem, it.Value(), subjects) || any
		}
	case kindRecord:
		for _, f := range p.fields {
			any = personalInValue(f.plan, v.Field(f.index), subjects) || any
		}
	case kindSum:
		if v.IsNil() {
			return false
		}
		dyn := v.Elem()
		if dyn.Kind() == reflect.Pointer {
			if dyn.IsNil() {
				return false
			}
			dyn = dyn.Elem()
		}
		if c := p.sum.byType[dyn.Type()]; c != nil {
			return personalInValue(c.record, dyn, subjects)
		}
	}
	return any
}

// envelopesInJSON walks a parsed document by p: every (project, subject) an envelope sealed with a
// key names. What is not an envelope is left to the reader to refuse.
func envelopesInJSON(p *plan, raw any, out map[subjectRef]bool) {
	if !p.personal || raw == nil {
		return
	}
	switch p.kind {
	case kindPersonal:
		if obj, ok := raw.(*jsonObject); ok {
			ref, version, boxed, problem := envelopeParts(obj)
			if problem == "" && version.n == envelopeVersion && boxed != nil {
				out[ref] = true
			}
		}
	case kindOptional:
		envelopesInJSON(p.elem, raw, out)
	case kindSequence:
		if arr, ok := raw.(*jsonArray); ok {
			for _, item := range arr.items {
				envelopesInJSON(p.elem, item, out)
			}
		}
	case kindMap:
		if obj, ok := raw.(*jsonObject); ok {
			for _, k := range obj.keys {
				envelopesInJSON(p.elem, obj.values[k], out)
			}
		}
	case kindRecord:
		if obj, ok := raw.(*jsonObject); ok {
			for _, f := range p.fields {
				if v, ok := obj.get(f.name); ok {
					envelopesInJSON(f.plan, v, out)
				}
			}
		}
	case kindSum:
		if obj, ok := raw.(*jsonObject); ok {
			d, _ := obj.get("type")
			if name, ok := d.(string); ok {
				if c := p.sum.byName[name]; c != nil {
					envelopesInJSON(c.record, raw, out)
				}
			}
		}
	}
}

// sealJSON is a codec's encoding under manifest: a personal field as its envelope, its key fetched
// first. A write for an erased subject fails before anything is written.
func sealJSON(p *plan, v reflect.Value, manifest string) ([]byte, error) {
	if !p.personal {
		return encodeJSON(p, v)
	}
	subjects := map[string]bool{}
	sc := &sealScope{manifest: manifest}
	if personalInValue(p, v, subjects) {
		// Asked for the own project (an empty one); the answers say which project that is.
		kr := currentKeyring()
		refs := make([]subjectRef, 0, len(subjects))
		for s := range subjects {
			refs = append(refs, subjectRef{subject: s})
		}
		var err error
		if sc.keys, err = kr.prefetch(refs, true); err != nil {
			return nil, err
		}
		if sc.project, err = kr.ownProject(); err != nil {
			return nil, err
		}
		for _, ref := range refs {
			if sc.keys[subjectRef{project: sc.project, subject: ref.subject}].erased {
				return nil, subjectErased(ref.subject)
			}
		}
	}
	var buf bytes.Buffer
	if err := writeValue(p, v, &buf, "", sc); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// openJSON is a codec's decoding under manifest: every envelope's key fetched first, an erased
// subject's field read as erased.
func openJSON(p *plan, data []byte, target reflect.Value, manifest string) error {
	if !p.personal {
		return decodeJSON(p, data, target)
	}
	raw, err := parseJSON(data)
	if err != nil {
		return err
	}
	found := map[subjectRef]bool{}
	envelopesInJSON(p, raw, found)
	sc := &sealScope{manifest: manifest, keys: map[subjectRef]keyAnswer{}}
	if len(found) > 0 {
		refs := make([]subjectRef, 0, len(found))
		for r := range found {
			refs = append(refs, r)
		}
		if sc.keys, err = currentKeyring().prefetch(refs, false); err != nil {
			return err
		}
	}
	return readValue(p, raw, target, "", sc)
}
