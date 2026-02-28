package io.smallfast.profiler;

import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public final class Profiler {

    // ThreadId -> State (for dump)
    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();

    // dry-run collision detection (only logs when collision is found)
    private static volatile boolean DRY_RUN = false;

    // Histogram config (nanoseconds)
    // Adjust if you expect larger self-times for a single method.
    private static final long HIST_MAX_NS = 30_000_000_000L; // 30s in ns
    private static final int HIST_SIG_DIGITS = 3;

    public static void setDryRun(boolean v) {
        DRY_RUN = v;
    }

    private static final ThreadLocal<State> TL = ThreadLocal.withInitial(() -> {
        Thread t = Thread.currentThread();
        State s = new State(t.getName(), t.getId());
        STATES.put(s.tid, s);
        return s;
    });

    private Profiler() {}

    public static void enter(int methodId) {
        State s = TL.get();
        int d = s.depth;

        if (d == s.stackMethod.length) {
            s.growStacks();
        }

        s.stackMethod[d] = methodId;
        s.stackChildNs[d] = 0L;
        s.depth = d + 1;
        s.stackStartNs[d] = System.nanoTime();
    }

    public static void exit() {
        long end = System.nanoTime();

        State s = TL.get();
        int d = s.depth - 1;
        if (d < 0) {
            s.depth = 0;
            return;
        }
        s.depth = d;

        int methodId = s.stackMethod[d];
        long total = end - s.stackStartNs[d];
        long self = total - s.stackChildNs[d];

        // propagate inclusive time to parent as "child time"
        if (d > 0) {
            s.stackChildNs[d - 1] += total;
        }

        // record SELF time for this stack path
        s.map.add(s, s.stackMethod, d, methodId, self);
    }

    /** Dump flamegraph collapsed files (self-time) one per thread into dir. */
    public static void dumpPerThread(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (State s : STATES.values()) {
            Path out = dir.resolve("collapsed-" + sanitize(s.threadName) + "-" + s.tid + ".txt");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                s.map.dumpToCollapsed(w);
            }
        }
    }

    public static void dumpSpeedscopePerThread(Path dir) throws IOException {
        Files.createDirectories(dir);

        for (State s : STATES.values()) {
            Path out = dir.resolve("thread-" + sanitize(s.threadName) + "-" + s.tid + ".speedscope.json");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                s.map.dumpToSpeedscope(w, s.threadName);
            }
        }
    }

    /** Dump percentiles CSV (self-time) one per thread into dir. */
    public static void dumpPercentilesPerThread(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (State s : STATES.values()) {
            Path out = dir.resolve("percentiles-" + sanitize(s.threadName) + "-" + s.tid + ".csv");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("stack,p50_ns,p90_ns,p99_ns,p999_ns,p100_ns,count");
                s.map.dumpPercentilesCsv(w);
            }
        }
    }

    /** Optional: clear all data after dump. */
    public static void resetAll() {
        for (State s : STATES.values()) {
            s.map.reset();
        }
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }

    static final class State {
        final String threadName;
        final long tid;

        int depth = 0;

        int[] stackMethod = new int[256];
        long[] stackStartNs = new long[256];
        long[] stackChildNs = new long[256];

        final StackMap map = new StackMap();

        State(String threadName, long tid) {
            this.threadName = threadName;
            this.tid = tid;
        }

        void growStacks() {
            int newCap = stackMethod.length << 1;

            int[] nm = new int[newCap];
            long[] ns = new long[newCap];
            long[] nc = new long[newCap];

            System.arraycopy(stackMethod, 0, nm, 0, stackMethod.length);
            System.arraycopy(stackStartNs, 0, ns, 0, stackStartNs.length);
            System.arraycopy(stackChildNs, 0, nc, 0, stackChildNs.length);

            stackMethod = nm;
            stackStartNs = ns;
            stackChildNs = nc;
        }
    }

    /**
     * Hash-bucket map of stack(parents int[]) + leaf -> accumulated self ns + HDR histogram.
     * Collision-safe via full compare. Per-thread (no synchronization needed inside).
     */
    static final class StackMap {
        private Entry[] buckets = new Entry[1 << 14]; // 16384
        private int size = 0;

        void add(State owner, int[] parentStack, int parentLen, int leafMethodId, long selfNs) {
            if (selfNs <= 0) return;

            long h = hash(parentStack, parentLen, leafMethodId);
            int idx = ((int) h) & (buckets.length - 1);

            Entry e = buckets[idx];
            while (e != null) {
                if (e.hash == h) {
                    boolean same =
                            (e.parentLen == parentLen) &&
                                    (e.leaf == leafMethodId) &&
                                    equalsParents(e.parents, parentStack, parentLen);

                    if (same) {
                        e.totalSelfNs += selfNs;
                        // record self-time into histogram (clamp to max trackable)
                        recordHistogram(e.hist, selfNs);
                        return;
                    } else {
                        if (DRY_RUN && !e.reportedCollision) {
                            e.reportedCollision = true;
                            reportCollision(owner, h, e, parentStack, parentLen, leafMethodId);
                        }
                    }
                }
                e = e.next;
            }

            // New unique stack path: copy parents once
            int[] copyParents = new int[parentLen];
            if (parentLen > 0) System.arraycopy(parentStack, 0, copyParents, 0, parentLen);

            Histogram hist = new Histogram(HIST_MAX_NS, HIST_SIG_DIGITS);
            recordHistogram(hist, selfNs);

            Entry ne = new Entry(h, copyParents, parentLen, leafMethodId, selfNs, hist, buckets[idx]);
            buckets[idx] = ne;
            size++;

            if (size > (buckets.length * 4)) {
                rehash();
            }
        }

        void dumpToCollapsed(PrintWriter w) {
            for (Entry head : buckets) {
                Entry e = head;
                while (e != null) {
                    w.print(toCollapsedStack(e.parents, e.parentLen, e.leaf));
                    w.print(' ');
                    w.println(e.totalSelfNs);
                    e = e.next;
                }
            }
        }

        void dumpPercentilesCsv(PrintWriter w) {
            for (Entry head : buckets) {
                Entry e = head;
                while (e != null) {
                    String stack = toCollapsedStack(e.parents, e.parentLen, e.leaf);
                    Histogram h = e.hist;

                    long p50 = h.getValueAtPercentile(50.0);
                    long p90 = h.getValueAtPercentile(90.0);
                    long p99 = h.getValueAtPercentile(99.0);
                    long p999 = h.getValueAtPercentile(99.9);
                    long p100 = h.getMaxValue();
                    long count = h.getTotalCount();

                    // CSV: stack,p50,p90,p99,p999,p100,count
                    w.print(stack);
                    w.print(',');
                    w.print(p50);
                    w.print(',');
                    w.print(p90);
                    w.print(',');
                    w.print(p99);
                    w.print(',');
                    w.print(p999);
                    w.print(',');
                    w.print(p100);
                    w.print(',');
                    w.println(count);

                    e = e.next;
                }
            }
        }

        void reset() {
            buckets = new Entry[1 << 14];
            size = 0;
        }

        private void rehash() {
            Entry[] old = buckets;
            Entry[] next = new Entry[old.length << 1];
            int mask = next.length - 1;

            for (Entry head : old) {
                Entry e = head;
                while (e != null) {
                    Entry n = e.next;
                    int idx = ((int) e.hash) & mask;
                    e.next = next[idx];
                    next[idx] = e;
                    e = n;
                }
            }
            buckets = next;
        }

        private static boolean equalsParents(int[] storedParents, int[] currentParents, int len) {
            for (int i = 0; i < len; i++) {
                if (storedParents[i] != currentParents[i]) return false;
            }
            return true;
        }

        private static long hash(int[] parents, int parentLen, int leaf) {
            // FNV-1a 64-bit
            long h = 1469598103934665603L;
            for (int i = 0; i < parentLen; i++) {
                h ^= (parents[i] * 0x9E3779B9L);
                h *= 1099511628211L;
            }
            h ^= (leaf * 0x9E3779B9L);
            h *= 1099511628211L;
            return h;
        }

        private static void recordHistogram(Histogram h, long valueNs) {
            // HDRHistogram throws if value > highestTrackableValue
            if (valueNs <= 0) return;
            if (valueNs > HIST_MAX_NS) {
                h.recordValue(HIST_MAX_NS);
            } else {
                h.recordValue(valueNs);
            }
        }

        private static String toCollapsedStack(int[] parents, int parentLen, int leaf) {
            // Semicolon-separated stack (flamegraph "collapsed" format)
            StringBuilder sb = new StringBuilder(128);
            for (int i = 0; i < parentLen; i++) {
                if (i > 0) sb.append(';');
                sb.append(ProfilerIds.nameFor(parents[i]));
            }
            if (parentLen > 0) sb.append(';');
            sb.append(ProfilerIds.nameFor(leaf));
            return sb.toString();
        }

        private static void reportCollision(State owner, long hash, Entry existing,
                                            int[] newParents, int newParentLen, int newLeaf) {
            System.err.println("=== PROFILER HASH COLLISION DETECTED ===");
            System.err.println("Thread: " + owner.threadName + " (id=" + owner.tid + ")");
            System.err.println("Hash:   " + hash);
            System.err.println("Stack1: " + stackToString(existing.parents, existing.parentLen, existing.leaf));
            System.err.println("Stack2: " + stackToString(newParents, newParentLen, newLeaf));
            System.err.println("=======================================");
        }

        private static String stackToString(int[] parents, int parentLen, int leaf) {
            StringBuilder sb = new StringBuilder(128);
            for (int i = 0; i < parentLen; i++) {
                if (i > 0) sb.append(" -> ");
                sb.append(ProfilerIds.nameFor(parents[i]));
            }
            if (parentLen > 0) sb.append(" -> ");
            sb.append(ProfilerIds.nameFor(leaf));
            return sb.toString();
        }

        static final class Entry {
            final long hash;
            final int[] parents;
            final int parentLen;
            final int leaf;

            long totalSelfNs;     // sum of self-time across calls for this stack
            final Histogram hist; // latency distribution of self-time per call

            Entry next;

            boolean reportedCollision = false;

            Entry(long hash, int[] parents, int parentLen, int leaf,
                  long firstSelfNs, Histogram hist, Entry next) {
                this.hash = hash;
                this.parents = parents;
                this.parentLen = parentLen;
                this.leaf = leaf;
                this.totalSelfNs = firstSelfNs;
                this.hist = hist;
                this.next = next;
            }
        }

        void dumpToSpeedscope(PrintWriter w, String profileName) {

            // 1️⃣ Collect entries into deterministic list
            java.util.List<Entry> entries = new java.util.ArrayList<>();

            for (Entry head : buckets) {
                Entry e = head;
                while (e != null) {
                    entries.add(e);
                    e = e.next;
                }
            }

            // 2️⃣ Build frame table
            java.util.Map<Integer, Integer> methodIdToFrameIndex = new java.util.HashMap<>();
            java.util.List<String> frames = new java.util.ArrayList<>();

            for (Entry e : entries) {
                for (int i = 0; i < e.parentLen; i++) {
                    int mid = e.parents[i];
                    if (!methodIdToFrameIndex.containsKey(mid)) {
                        methodIdToFrameIndex.put(mid, frames.size());
                        frames.add(ProfilerIds.nameFor(mid));
                    }
                }
                int leaf = e.leaf;
                if (!methodIdToFrameIndex.containsKey(leaf)) {
                    methodIdToFrameIndex.put(leaf, frames.size());
                    frames.add(ProfilerIds.nameFor(leaf));
                }
            }

            // 3️⃣ Write JSON
            w.println("{");
            w.println("  \"$schema\": \"https://www.speedscope.app/file-format-schema.json\",");
            w.println("  \"shared\": {");
            w.println("    \"frames\": [");

            for (int i = 0; i < frames.size(); i++) {
                w.print("      { \"name\": \"");
                w.print(escapeJson(frames.get(i)));
                w.print("\" }");
                if (i < frames.size() - 1) w.println(",");
                else w.println();
            }

            w.println("    ]");
            w.println("  },");
            w.println("  \"profiles\": [");
            w.println("    {");
            w.println("      \"type\": \"sampled\",");
            w.print("      \"name\": \"");
            w.print(escapeJson(profileName));
            w.println("\",");
            w.println("      \"unit\": \"nanoseconds\",");

            // 4️⃣ Samples
            w.println("      \"samples\": [");

            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);

                w.print("        [");

                for (int j = 0; j < e.parentLen; j++) {
                    int frameIndex = methodIdToFrameIndex.get(e.parents[j]);
                    w.print(frameIndex);
                    w.print(",");
                }

                int leafIndex = methodIdToFrameIndex.get(e.leaf);
                w.print(leafIndex);

                w.print("]");

                if (i < entries.size() - 1) w.println(",");
                else w.println();
            }

            w.println("      ],");

            // 5️⃣ Weights (must match sample order exactly)
            w.println("      \"weights\": [");

            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                w.print("        ");
                w.print(e.totalSelfNs);

                if (i < entries.size() - 1) w.println(",");
                else w.println();
            }

            w.println("      ]");

            w.println("    }");
            w.println("  ]");
            w.println("}");
        }

        private static String escapeJson(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 32) {
                            sb.append(String.format("\\u%04x", (int)c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }
    }
}