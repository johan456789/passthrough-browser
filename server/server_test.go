package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeOpener struct {
	mu    sync.Mutex
	calls []string
	err   error
}

func (f *fakeOpener) Open(u string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, u)
	return f.err
}

const okClient = "192.168.240.112:40000"

func newTestServer(f *fakeOpener, burst int) http.Handler {
	cfg := config{allow: netip.MustParsePrefix("192.168.240.0/24")}
	return newServer(cfg, f, newTokenBucket(burst, 1, time.Now), quietLog()).routes()
}

func do(h http.Handler, method, path, remote, ctype, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.RemoteAddr = remote
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func postOpen(h http.Handler, body string) *httptest.ResponseRecorder {
	return do(h, "POST", "/open", okClient, "application/json", body)
}

func errCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("bad json %q: %v", rec.Body.String(), err)
	}
	s, _ := m["error"].(string)
	return s
}

func TestHealthOK(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "GET", "/health", okClient, "", "")
	if rec.Code != 200 {
		t.Fatal(rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Fatal(ct)
	}
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	if m["ok"] != true || m["name"] != "passthrough-browser" || m["version"] != version || m["client"] != "192.168.240.112" {
		t.Fatalf("%v", m)
	}
}

func TestHealthHEAD(t *testing.T) {
	if rec := do(newTestServer(&fakeOpener{}, 5), "HEAD", "/health", okClient, "", ""); rec.Code != 200 {
		t.Fatal(rec.Code)
	}
}

func TestHealthWrongMethod(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "POST", "/health", okClient, "", "")
	if rec.Code != 405 || rec.Header().Get("Allow") != "GET, HEAD" {
		t.Fatal(rec.Code, rec.Header())
	}
}

func TestOpenSuccess(t *testing.T) {
	f := &fakeOpener{}
	const u = "https://example.com/?a=1&b=%20x"
	rec := postOpen(newTestServer(f, 5), `{"url":"`+u+`"}`)
	if rec.Code != 200 || strings.TrimSpace(rec.Body.String()) != `{"ok":true}` {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if len(f.calls) != 1 || f.calls[0] != u {
		t.Fatal(f.calls)
	}
}

func TestOpenContentTypeWithCharset(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "POST", "/open", okClient, "Application/JSON; charset=utf-8", `{"url":"https://a.b"}`)
	if rec.Code != 200 {
		t.Fatal(rec.Code)
	}
}

func TestOpenWrongMethod(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "GET", "/open", okClient, "", "")
	if rec.Code != 405 || rec.Header().Get("Allow") != "POST" || errCode(t, rec) != "method_not_allowed" {
		t.Fatal(rec.Code, rec.Header())
	}
}

func TestOpenMissingContentType(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "POST", "/open", okClient, "", `{"url":"https://a.b"}`)
	if rec.Code != 415 {
		t.Fatal(rec.Code)
	}
}

func TestOpenTextPlain(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "POST", "/open", okClient, "text/plain", "https://a.b")
	if rec.Code != 415 || errCode(t, rec) != "unsupported_media_type" {
		t.Fatal(rec.Code)
	}
}

func TestOpenBodyTooLarge(t *testing.T) {
	body := `{"url":"https://x/` + strings.Repeat("a", 17<<10) + `"}`
	rec := postOpen(newTestServer(&fakeOpener{}, 5), body)
	if rec.Code != 413 || errCode(t, rec) != "body_too_large" {
		t.Fatal(rec.Code, rec.Body.String())
	}
}

func TestOpenBadJSON(t *testing.T) {
	for _, b := range []string{"{", "[]", `{"url":5}`, "{}", `{"url":""}`, `{"url":"x"} {"url":"y"}`} {
		f := &fakeOpener{}
		rec := postOpen(newTestServer(f, 5), b)
		if rec.Code != 400 || errCode(t, rec) != "bad_request" || len(f.calls) != 0 {
			t.Errorf("%q: %d %s", b, rec.Code, rec.Body.String())
		}
	}
}

func TestOpenInvalidURL(t *testing.T) {
	for _, u := range []string{"javascript:alert(1)", "file:///etc/passwd", "-x"} {
		f := &fakeOpener{}
		rec := postOpen(newTestServer(f, 5), `{"url":"`+u+`"}`)
		if rec.Code != 422 || errCode(t, rec) != "invalid_url" || len(f.calls) != 0 {
			t.Errorf("%q: %d", u, rec.Code)
		}
	}
}

func TestForbiddenClient(t *testing.T) {
	for _, remote := range []string{"10.0.0.5:1234", "127.0.0.1:1"} {
		f := &fakeOpener{}
		h := newTestServer(f, 5)
		if rec := do(h, "GET", "/health", remote, "", ""); rec.Code != 403 || errCode(t, rec) != "forbidden" {
			t.Errorf("%s health: %d", remote, rec.Code)
		}
		if rec := do(h, "POST", "/open", remote, "application/json", `{"url":"https://a.b"}`); rec.Code != 403 {
			t.Errorf("%s open: %d", remote, rec.Code)
		}
		if len(f.calls) != 0 {
			t.Error("opener called")
		}
	}
}

func TestIPv4MappedClientAllowed(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "GET", "/health", "[::ffff:192.168.240.5]:1", "", "")
	if rec.Code != 200 {
		t.Fatal(rec.Code)
	}
}

func TestOpenerError(t *testing.T) {
	rec := postOpen(newTestServer(&fakeOpener{err: errors.New("boom")}, 5), `{"url":"https://a.b"}`)
	if rec.Code != 500 || errCode(t, rec) != "open_failed" {
		t.Fatal(rec.Code, rec.Body.String())
	}
}

func TestRateLimited(t *testing.T) {
	h := newTestServer(&fakeOpener{}, 2)
	for i := 0; i < 2; i++ {
		if rec := postOpen(h, `{"url":"https://a.b"}`); rec.Code != 200 {
			t.Fatal(i, rec.Code)
		}
	}
	rec := postOpen(h, `{"url":"https://a.b"}`)
	if rec.Code != 429 || rec.Header().Get("Retry-After") != "1" || errCode(t, rec) != "rate_limited" {
		t.Fatal(rec.Code, rec.Header())
	}
}

func TestNotFound(t *testing.T) {
	rec := do(newTestServer(&fakeOpener{}, 5), "GET", "/foo", okClient, "", "")
	if rec.Code != 404 || errCode(t, rec) != "not_found" {
		t.Fatal(rec.Code)
	}
}
