package main

import (
	"context"
	"log/slog"
	"net"
	"time"
)

type listenFunc func(network, address string) (net.Listener, error)

// listenWithRetry keeps trying to bind until it succeeds or ctx is cancelled.
func listenWithRetry(ctx context.Context, listen listenFunc, network, address string,
	interval time.Duration, log *slog.Logger) (net.Listener, error) {
	var lastWarn time.Time
	for {
		ln, err := listen(network, address)
		if err == nil {
			log.Info("listening", "addr", ln.Addr().String())
			return ln, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if lastWarn.IsZero() || time.Since(lastWarn) >= 60*time.Second {
			log.Warn("bind failed, will retry", "addr", address, "err", err, "interval", interval.String())
			lastWarn = time.Now()
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(interval):
		}
	}
}
