# Java Deterministic Latency Profiler

A non-sampling, instrumentation-based Java agent that records **per-call-stack latency** and **latency percentiles**, exporting interactive **Speedscope flamegraphs** and CSV percentile reports per thread.

This profiler measures **every method call** (no sampling), making it suitable for precise latency analysis and tail latency investigation.

---

## Features

- ✅ Instrument selected packages only
- ✅ Deterministic (non-sampling) call tracing
- ✅ Self-time hierarchical accounting
- ✅ Per-thread flamegraph export (Speedscope format)
- ✅ Per-stack latency percentiles (p50, p90, p99, p99.9, p100)
- ✅ Optional hash collision detection (dry run mode)
- ✅ Auto-growing stack depth
- ✅ Reset without JVM restart

---

## What This Profiler Is NOT

- ❌ Not a sampling profiler
- ❌ Not zero-overhead
- ❌ Not a replacement for async-profiler

This profiler is designed for **precise latency analysis**, especially tail latency.

---

## How It Works

For each instrumented method:

1. On enter:
    - Push method ID onto thread-local stack
    - Record start timestamp (`System.nanoTime()`)

2. On exit:
    - Compute total duration
    - Compute self-time (`total - childTime`)
    - Record:
        - Accumulated self-time (for flamegraph)
        - Histogram record (for percentiles)

Each unique call stack maintains:

- Total self-time (nanoseconds)
- Histogram latency distribution

---

## Installation

Build the agent:

```bash
mvn clean package
```

---

## Running the Agent

Attach using `-javaagent`.

### Example

```bash
java -javaagent:precise-profiler-<version>.jar=packages=<com.package1|com.package2>,dryRun=<true|false> \
     -jar your-app.jar
```

---

## Agent Parameters

### `packages` (required)

Pipe-separated list of package prefixes to instrument:

```
packages=com.ppb.code|com.foo.bar
```

Only classes in those packages will be instrumented.

---

### `dryRun` (optional)

```
dryRun=true
```

When enabled:

- Detects hash collisions between stack paths
- Logs both colliding stacks
- Adds minor overhead

Recommended workflow:

1. Run once with `dryRun=true`
2. If no collisions are reported, disable for production runs

---

## Dumping Results

From your application:

```java
Profiler.dumpSpeedscopePerThread(Path.of("profiles"));
Profiler.dumpPercentilesPerThread(Path.of("profiles"));
```

This generates:

```
profiles/
  thread-main-1.speedscope.json
  percentiles-main-1.csv
```

One set per thread.

---

## Viewing Flamegraphs

Open Speedscope:

👉 https://www.speedscope.app

Upload:

```
thread-main-1.speedscope.json
```
---

## Percentiles Output

CSV format:

```
stack,p50_ns,p90_ns,p99_ns,p999_ns,p100_ns,count
```

Example:

```
com.ppb.code.Runs.doWork;com.ppb.code.Runs.doMoreWork,2000,3000,8000,9000,12000,100
```

Values are in **nanoseconds**.

---

## Stack Depth

Initial depth: 256  
Automatically expands if exceeded.

Safe for recursion and deep call chains.


## Resetting Between Runs

```java
Profiler.resetAll();
```
Clears accumulated profiling data without restarting the JVM.