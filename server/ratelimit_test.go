package main

import (
	"testing"
	"time"
)

func TestTokenBucket(t *testing.T) {
	now := time.Unix(1000, 0)
	b := newTokenBucket(3, 1, func() time.Time { return now })
	for i := 0; i < 3; i++ {
		if !b.Allow() {
			t.Fatalf("token %d denied", i)
		}
	}
	if b.Allow() {
		t.Fatal("burst exceeded but allowed")
	}
	now = now.Add(time.Second)
	if !b.Allow() {
		t.Fatal("expected refill after 1s")
	}
	if b.Allow() {
		t.Fatal("only one token should have refilled")
	}
	now = now.Add(time.Hour)
	for i := 0; i < 3; i++ {
		if !b.Allow() {
			t.Fatalf("capped token %d denied", i)
		}
	}
	if b.Allow() {
		t.Fatal("tokens must be capped at burst")
	}
}
