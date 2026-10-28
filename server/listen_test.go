package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"testing"
	"time"
)

func quietLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestListenRetrySucceeds(t *testing.T) {
	calls := 0
	fake := func(network, address string) (net.Listener, error) {
		calls++
		if calls <= 3 {
			return nil, errors.New("not yet")
		}
		return net.Listen("tcp", "127.0.0.1:0")
	}
	ln, err := listenWithRetry(context.Background(), fake, "tcp", "x:1", time.Millisecond, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	if calls != 4 {
		t.Fatalf("calls=%d want 4", calls)
	}
}

func TestListenRetryCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(20 * time.Millisecond); cancel() }()
	fake := func(network, address string) (net.Listener, error) { return nil, errors.New("nope") }
	start := time.Now()
	_, err := listenWithRetry(ctx, fake, "tcp", "x:1", time.Millisecond, quietLog())
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err=%v want context.Canceled", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("not prompt")
	}
}
