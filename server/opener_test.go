package main

import (
	"os/exec"
	"testing"
)

func TestExecOpener(t *testing.T) {
	if _, err := exec.LookPath("true"); err != nil {
		t.Skip("true not on PATH")
	}
	if err := (execOpener{program: "true", log: quietLog()}).Open("https://example.com"); err != nil {
		t.Fatal(err)
	}
	if err := (execOpener{program: "definitely-not-a-real-program-xyz", log: quietLog()}).Open("https://example.com"); err == nil {
		t.Fatal("expected error")
	}
}
