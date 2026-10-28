package main

import (
	"strings"
	"testing"
)

func TestValidateURL(t *testing.T) {
	long := "https://x/" + strings.Repeat("a", maxURLLen-len("https://x/"))
	if len(long) != maxURLLen {
		t.Fatalf("test bug: len=%d", len(long))
	}
	valid := []string{
		"http://example.com", "https://example.com/a?b=c&d=e#f", "HTTPS://EXAMPLE.COM",
		"https://[::1]:8080/", "https://user@example.com/", "https://例え.jp/",
		"https://example.com/a%20b%2Fc", long,
	}
	for _, u := range valid {
		if err := validateURL(u); err != nil {
			t.Errorf("validateURL(%q) = %v, want nil", u, err)
		}
	}
	invalid := []string{
		"", long + "a", "javascript:alert(1)", "file:///etc/passwd", "ftp://x",
		"intent://x#Intent;end", "data:text/html,hi", "-oProxyCommand=x", "--help",
		"http://", "https:///path", "http:example.com", " https://x", "https://x/a b",
		"https://x/\n", "https://x/\x00", "https://x/\x7f", "https://x/\xff",
	}
	for _, u := range invalid {
		if err := validateURL(u); err == nil {
			t.Errorf("validateURL(%q) = nil, want error", u)
		}
	}
}
