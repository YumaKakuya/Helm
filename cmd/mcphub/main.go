package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// MCPHUB Go launcher — JRE launcher only.
// AMD-MCPHUB-001: Go exists solely for distribution convenience.
// All logic is in Java. This binary finds the bundled JRE and execs Java.
const version = "0.2.0-alpha"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "start" {
		// "start" is special: background the Java process and poll health
		noDaemon := hasFlag(os.Args, "--no-daemon")
		if noDaemon {
			// Foreground: exec Java directly (replaces this process)
			execJava(replaceArgs("start", "_daemon"))
		} else {
			startDaemon()
		}
		return
	}

	if len(os.Args) > 1 && os.Args[1] == "stop" {
		stopDaemon()
		return
	}

	if len(os.Args) > 1 && os.Args[1] == "restart" {
		stopDaemon()
		noDaemon := hasFlag(os.Args, "--no-daemon")
		if noDaemon {
			execJava(replaceArgs("restart", "_daemon"))
		} else {
			startDaemon()
		}
		return
	}

	// All other subcommands: exec Java directly (replaces this process)
	execJava(os.Args[1:])
}

// execJava replaces the current process with Java running the mcphub-core JAR.
func execJava(javaArgs []string) {
	jre, jar := findArtifacts()
	args := append([]string{jre, "-jar", jar}, javaArgs...)

	// Set MCPHUB_ADAPTER_DIR if not already set (distribution layout: ../adapters)
	env := os.Environ()
	if os.Getenv("MCPHUB_ADAPTER_DIR") == "" {
		base := filepath.Dir(jar) // lib/
		adapterDir := filepath.Join(base, "..", "adapters")
		if info, err := os.Stat(adapterDir); err == nil && info.IsDir() {
			env = append(env, "MCPHUB_ADAPTER_DIR="+adapterDir)
		}
	}

	err := syscall.Exec(jre, args, env)
	// If exec fails, fall back to os/exec
	if err != nil {
		cmd := exec.Command(jre, append([]string{"-jar", jar}, javaArgs...)...)
		cmd.Stdin = os.Stdin
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			fmt.Fprintf(os.Stderr, "mcphub: %v\n", err)
			os.Exit(1)
		}
	}
}

// startDaemon backgrounds the Java daemon process and polls health.
func startDaemon() {
	jre, jar := findArtifacts()
	cmd := exec.Command(jre, "-jar", jar, "_daemon")
	cmd.Stdin = nil
	cmd.Stdout = nil
	cmd.Stderr = nil
	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "mcphub: failed to start daemon: %v\n", err)
		os.Exit(1)
	}

	// Poll health via Java IPC client
	for i := 0; i < 30; i++ {
		time.Sleep(500 * time.Millisecond)
		check := exec.Command(jre, "-jar", jar, "health")
		if err := check.Run(); err == nil {
			jsonOutput := hasFlag(os.Args, "--json")
			if jsonOutput {
				fmt.Printf("{\"status\":\"started\",\"pid\":%d}\n", cmd.Process.Pid)
			} else {
				fmt.Printf("mcphub daemon started (pid %d)\n", cmd.Process.Pid)
			}
			return
		}
	}
	fmt.Fprintln(os.Stderr, "mcphub: daemon did not become ready within 15 seconds")
	os.Exit(1)
}

// stopDaemon signals the daemon to shut down gracefully.
func stopDaemon() {
	// Find and signal the daemon process via PID file
	pidPath := pidFilePath()
	data, err := os.ReadFile(pidPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "mcphub: daemon not running (no pid file)")
		return
	}
	var pid int
	fmt.Sscanf(strings.TrimSpace(string(data)), "%d", &pid)
	proc, err := os.FindProcess(pid)
	if err != nil {
		return
	}
	proc.Signal(syscall.SIGTERM)

	// Wait up to 10 seconds
	for i := 0; i < 20; i++ {
		time.Sleep(500 * time.Millisecond)
		if err := proc.Signal(syscall.Signal(0)); err != nil {
			jsonOutput := hasFlag(os.Args, "--json")
			if jsonOutput {
				fmt.Println("{\"status\":\"stopped\"}")
			} else {
				fmt.Println("mcphub daemon stopped")
			}
			return
		}
	}
	fmt.Fprintln(os.Stderr, "mcphub: daemon did not stop within 10 seconds")
	os.Exit(1)
}

// findArtifacts locates the bundled JRE and mcphub-core.jar.
func findArtifacts() (jrePath, jarPath string) {
	exe, _ := os.Executable()
	base := filepath.Dir(exe)

	// Distribution layout: bin/mcphub → ../jre/bin/java, ../lib/mcphub-core.jar
	jar := filepath.Join(base, "..", "lib", "mcphub-core.jar")
	jre := filepath.Join(base, "..", "jre", "bin", "java")
	if _, err := os.Stat(jar); err == nil {
		if _, err2 := os.Stat(jre); err2 == nil {
			return jre, jar
		}
	}

	// Development mode: use gradle-built jar + system java
	devJar := findDevJar()
	if devJar != "" {
		javaExe := "java"
		if p, err := exec.LookPath("java"); err == nil {
			javaExe = p
		}
		return javaExe, devJar
	}

	fmt.Fprintln(os.Stderr, "mcphub: cannot find mcphub-core.jar — run ./gradlew jar first")
	os.Exit(1)
	return "", ""
}

func findDevJar() string {
	// Walk up to find go.mod (repo root), then look for java/build/libs/*.jar
	dir, _ := os.Getwd()
	for {
		gomod := filepath.Join(dir, "go.mod")
		if _, err := os.Stat(gomod); err == nil {
			libs := filepath.Join(dir, "java", "build", "libs")
			entries, _ := os.ReadDir(libs)
			for _, e := range entries {
				name := e.Name()
				if strings.HasSuffix(name, ".jar") && !strings.Contains(name, "plain") {
					return filepath.Join(libs, name)
				}
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return ""
}

func pidFilePath() string {
	dataDir := os.Getenv("MCPHUB_DATA_DIR")
	if dataDir == "" {
		home, _ := os.UserHomeDir()
		dataDir = filepath.Join(home, ".local", "share", "mcphub")
	}
	return filepath.Join(dataDir, "mcphub.pid")
}

func hasFlag(args []string, flag string) bool {
	for _, a := range args {
		if a == flag {
			return true
		}
	}
	return false
}

func replaceArgs(old, new string) []string {
	result := make([]string, 0, len(os.Args)-1)
	for _, a := range os.Args[1:] {
		if a == old {
			result = append(result, new)
		} else {
			result = append(result, a)
		}
	}
	return result
}
