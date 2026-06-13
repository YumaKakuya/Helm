# MCPHUB Performance Benchmark Report

## Methodology

Benchmarks use the automated VT-016 integration test (`integration/vt_test.go:TestVT_016_LatencyP50P99`).

**Procedure:**
1. Start a fresh MCPHUB daemon with default config
2. Open a session (CLOSED -> ARMED -> OPEN)
3. Execute 100 sequential `tools/call` invocations of the `list` tool (path: `/tmp`)
4. Read `latency_ms` from the `route_log` SQLite table (sorted ascending)
5. Compute p50 and p99 from the latency distribution

**What is measured:**
- MCPHUB internal overhead only: request receipt at Java bridge -> daemon routing -> policy check -> provider dispatch initiation
- Provider execution time is excluded (measured separately in VT-017)

**What is NOT measured:**
- Network latency (there is none -- all communication is local UDS/stdio)
- Provider execution time (adapter I/O, external API calls, disk access)
- AI client MCP protocol overhead

### Reproducing the benchmark

```bash
cd /path/to/MCPHUB
make
go test -v -run TestVT_016 ./integration/
```

Output includes `VT-016: p50=Xms p99=Xms (N=100)`.

## Results

### Latency (VT-016)

| Metric | Measured | Target (Spec REQ-8.10.3) | Status |
|--------|----------|--------------------------|--------|
| p50 | 1ms | < 50ms | PASS (50x headroom) |
| p99 | 3ms | < 100ms | PASS (33x headroom) |
| Sample size | 100 calls | -- | -- |

### Provider isolation (VT-017)

Verifies that MCPHUB latency does NOT include provider execution time.

| Metric | Value |
|--------|-------|
| Synthetic provider delay | 500ms |
| Total elapsed time | ~500ms |
| MCPHUB `latency_ms` recorded | < 100ms |
| Provider time excluded | Confirmed |

### Reproducing VT-017

```bash
go test -v -run TestVT_017 ./integration/
```

## Environment

| Item | Value |
|------|-------|
| Platform | Linux (WSL2) |
| Java | OpenJDK 21 (Temurin) |
| Go | 1.26.2 |
| IPC | Unix domain socket |
| Storage | SQLite 3.45.x (WAL mode) |

## Interpretation

MCPHUB adds negligible overhead (1-3ms) to tool calls. The dominant latency in any real tool invocation is the provider's own execution (HTTP calls, file I/O, etc.), not MCPHUB's routing layer.

The 50x headroom on p50 means MCPHUB could serve significantly more complex routing logic before approaching the performance budget.

---

*MCPHUB Benchmark Report -- BL-12*
