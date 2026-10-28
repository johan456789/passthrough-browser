package main

import (
	"errors"
	"fmt"
	"net/url"
	"strings"
	"unicode/utf8"
)

const maxURLLen = 8192

// validateURL returns nil or an error whose message is user-facing.
func validateURL(raw string) error {
	if len(raw) < 1 || len(raw) > maxURLLen {
		return fmt.Errorf("url length must be between 1 and %d bytes", maxURLLen)
	}
	if !utf8.ValidString(raw) {
		return errors.New("url is not valid UTF-8")
	}
	for i := 0; i < len(raw); i++ {
		if raw[i] <= 0x20 || raw[i] == 0x7f {
			return errors.New("url contains whitespace or control characters")
		}
	}
	if strings.HasPrefix(raw, "-") {
		return errors.New("url must not start with '-'")
	}
	lower := strings.ToLower(raw)
	if !strings.HasPrefix(lower, "http://") && !strings.HasPrefix(lower, "https://") {
		return errors.New("url must start with http:// or https://")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("url does not parse: %v", err)
	}
	if s := strings.ToLower(u.Scheme); s != "http" && s != "https" {
		return errors.New("url scheme must be http or https")
	}
	if u.Opaque != "" {
		return errors.New("url must not be opaque")
	}
	if u.Hostname() == "" {
		return errors.New("url has no host")
	}
	return nil
}
