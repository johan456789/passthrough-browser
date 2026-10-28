package main

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
)

type config struct {
	allow   netip.Prefix
	verbose bool
}

type server struct {
	cfg     config
	opener  Opener
	limiter *tokenBucket
	log     *slog.Logger
}

func newServer(cfg config, o Opener, l *tokenBucket, log *slog.Logger) *server {
	return &server{cfg: cfg, opener: o, limiter: l, log: log}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/open", s.handleOpen)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "no such path")
	})
	return s.allowClients(mux)
}

func clientIP(r *http.Request) (netip.Addr, bool) {
	ap, err := netip.ParseAddrPort(r.RemoteAddr)
	if err != nil {
		return netip.Addr{}, false
	}
	return ap.Addr().Unmap(), true
}

// allowClients rejects peers outside the allowed prefix. Headers such as
// X-Forwarded-For are never consulted.
func (s *server) allowClients(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, ok := clientIP(r)
		if !ok || !s.cfg.allow.Contains(ip) {
			s.log.Warn("rejected client", "remote", r.RemoteAddr, "path", r.URL.Path)
			writeError(w, http.StatusForbidden, "forbidden", "client address not allowed")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, struct {
		OK      bool   `json:"ok"`
		Error   string `json:"error"`
		Message string `json:"message"`
	}{false, code, msg})
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "use GET or HEAD")
		return
	}
	ip, _ := clientIP(r)
	writeJSON(w, http.StatusOK, struct {
		OK      bool   `json:"ok"`
		Name    string `json:"name"`
		Version string `json:"version"`
		Client  string `json:"client"`
	}{true, "passthrough-browser", version, ip.String()})
}

func (s *server) handleOpen(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
		return
	}
	mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || !strings.EqualFold(mt, "application/json") {
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	dec := json.NewDecoder(r.Body)
	var req struct {
		URL any `json:"url"`
	}
	bad := func(err error) {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeError(w, http.StatusRequestEntityTooLarge, "body_too_large", "request body exceeds 16 KiB")
			return
		}
		writeError(w, http.StatusBadRequest, "bad_request", "body must be a single JSON object with a string \"url\"")
	}
	if err := dec.Decode(&req); err != nil {
		bad(err)
		return
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		if err == nil {
			err = errors.New("trailing data")
		}
		bad(err)
		return
	}
	raw, ok := req.URL.(string)
	if !ok || raw == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "missing or empty string \"url\"")
		return
	}
	if err := validateURL(raw); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid_url", err.Error())
		return
	}
	if !s.limiter.Allow() {
		w.Header().Set("Retry-After", "1")
		writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests")
		return
	}
	ip, _ := clientIP(r)
	if err := s.opener.Open(raw); err != nil {
		s.log.Error("open failed", "client", ip.String(), "err", err)
		writeError(w, http.StatusInternalServerError, "open_failed", "could not start opener: "+err.Error())
		return
	}
	logged := raw
	if !s.cfg.verbose {
		if u, err := url.Parse(raw); err == nil {
			logged = u.Scheme + "://" + u.Host
		}
	}
	s.log.Info("open", "client", ip.String(), "url", logged)
	writeJSON(w, http.StatusOK, struct {
		OK bool `json:"ok"`
	}{true})
}
