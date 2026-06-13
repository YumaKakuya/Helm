package main

import (
	"strings"
	"testing"
)

func TestWithDefaultCliCommandPreservesExisting(t *testing.T) {
	env := withDefaultCliCommand([]string{"OTHER=value", "MCPHUB_CLI_COMMAND=/custom/mcphub"})

	count := 0
	for _, entry := range env {
		if strings.HasPrefix(entry, "MCPHUB_CLI_COMMAND=") {
			count++
			if entry != "MCPHUB_CLI_COMMAND=/custom/mcphub" {
				t.Fatalf("existing MCPHUB_CLI_COMMAND was not preserved: %s", entry)
			}
		}
	}
	if count != 1 {
		t.Fatalf("expected exactly one MCPHUB_CLI_COMMAND, got %d in %v", count, env)
	}
}

func TestWithDefaultCliCommandInjectsWhenMissing(t *testing.T) {
	env := withDefaultCliCommand([]string{"OTHER=value"})

	found := false
	for _, entry := range env {
		if strings.HasPrefix(entry, "MCPHUB_CLI_COMMAND=") {
			found = true
			if strings.TrimPrefix(entry, "MCPHUB_CLI_COMMAND=") == "" {
				t.Fatalf("injected MCPHUB_CLI_COMMAND is empty")
			}
		}
	}
	if !found {
		t.Fatalf("expected MCPHUB_CLI_COMMAND to be injected into %v", env)
	}
}
