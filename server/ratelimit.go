package main

import (
	"sync"
	"time"
)

// tokenBucket is a global (not per-client) token bucket.
type tokenBucket struct {
	mu     sync.Mutex
	tokens float64
	burst  float64
	perSec float64
	last   time.Time
	now    func() time.Time
}

func newTokenBucket(burst int, perSec float64, now func() time.Time) *tokenBucket {
	if now == nil {
		now = time.Now
	}
	return &tokenBucket{tokens: float64(burst), burst: float64(burst), perSec: perSec, last: now(), now: now}
}

// Allow consumes one token if available.
func (b *tokenBucket) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	t := b.now()
	if el := t.Sub(b.last).Seconds(); el > 0 {
		b.tokens += el * b.perSec
		if b.tokens > b.burst {
			b.tokens = b.burst
		}
	}
	b.last = t
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}
