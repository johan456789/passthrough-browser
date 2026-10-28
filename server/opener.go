package main

import (
	"log/slog"
	"os"
	"os/exec"
)

type Opener interface {
	Open(url string) error
}

type execOpener struct {
	program string
	log     *slog.Logger
}

// Open starts "<program> <url>" (exactly two argv entries, never via a shell)
// and reaps it in the background. It is never killed on timeout: some xdg-open
// backends stay attached to the browser process.
func (o execOpener) Open(url string) error {
	cmd := exec.Command(o.program, url)
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() {
		if err := cmd.Wait(); err != nil {
			o.log.Warn("opener exited with error", "program", o.program, "err", err)
		} else {
			o.log.Debug("opener exited", "program", o.program)
		}
	}()
	return nil
}
