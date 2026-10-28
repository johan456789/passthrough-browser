package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

var version = "0.1.0"

func main() { os.Exit(run()) }

func run() int {
	addrFlag := flag.String("addr", "192.168.240.1", "IP address to bind")
	port := flag.Int("port", 7157, "TCP port (1-65535)")
	allowCIDR := flag.String("allow-cidr", "192.168.240.0/24", "only serve clients in this prefix")
	retry := flag.Duration("retry-interval", 2*time.Second, "delay between bind attempts")
	opener := flag.String("opener", "xdg-open", "program run with the URL as its single argument")
	verbose := flag.Bool("verbose", false, "log full URLs")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("passthrough-browser %s\n", version)
		return 0
	}
	logger := slog.New(slog.NewTextHandler(os.Stderr, nil))

	fail := func(msg string, args ...any) int {
		fmt.Fprintf(os.Stderr, msg+"\n", args...)
		return 2
	}
	addr, err := netip.ParseAddr(*addrFlag)
	if err != nil {
		return fail("invalid -addr: %v", err)
	}
	if *port < 1 || *port > 65535 {
		return fail("invalid -port: must be 1-65535")
	}
	prefix, err := netip.ParsePrefix(*allowCIDR)
	if err != nil {
		return fail("invalid -allow-cidr: %v", err)
	}
	prefix = prefix.Masked()
	if *retry <= 0 {
		return fail("invalid -retry-interval: must be > 0")
	}
	if !prefix.Contains(addr) {
		logger.Warn("-addr is not inside -allow-cidr", "addr", addr.String(), "allow", prefix.String())
	}
	if _, err := exec.LookPath(*opener); err != nil {
		logger.Warn("opener not found; opens will fail", "opener", *opener, "err", err)
	}

	logger.Info("starting", "version", version, "addr", addr.String(), "port", *port, "allow", prefix.String())

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	ln, err := listenWithRetry(ctx, net.Listen, "tcp", net.JoinHostPort(addr.String(), strconv.Itoa(*port)), *retry, logger)
	if err != nil {
		return 0 // cancelled
	}

	s := newServer(config{allow: prefix, verbose: *verbose}, execOpener{program: *opener, log: logger},
		newTokenBucket(5, 1, time.Now), logger)
	srv := &http.Server{
		Handler:           s.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()

	select {
	case <-ctx.Done():
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sctx)
		return 0
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("serve failed", "err", err)
			return 1
		}
		return 0
	}
}
