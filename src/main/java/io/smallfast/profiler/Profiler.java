package io.smallfast.profiler;

import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Profiler {

    private static final ConcurrentHashMap<Long, State> STATES = new ConcurrentHashMap<>();
    private static volatile boolean DRY_RUN = false; // not used by tree (no hashing), kept for compatibility

    private static final long HIST_MAX_NS = 30_000_000_000L; // 30s
    private static final int HIST_SIG_DIGITS = 3;
    private static boolean ENABLE_HISTOGRAM = false;

    public static void setHistogramEnabled(boolean enabled) {
        ENABLE_HISTOGRAM = enabled;
    }
    public static void setDryRun(boolean v) { DRY_RUN = v; }

    private static final ThreadLocal<State> TL = ThreadLocal.withInitial(() -> {
        Thread t = Thread.currentThread();
        State s = new State(t.getName(), t.getId());
        STATES.put(s.tid, s);
        return s;
    });

    private Profiler() {}

    // ---------------- hot path ----------------

    public static void enter(int methodId) {
        State s = TL.get();
        int d = s.depth;

        if (d == s.stackNode.length) s.growStacks();

        Node parent = (d == 0) ? s.root : s.stackNode[d - 1];
        Node child = parent.getOrCreateChild(methodId);

        s.stackNode[d] = child;
        s.stackStartNs[d] = System.nanoTime();
        s.stackChildNs[d] = 0L;

        s.depth = d + 1;
    }

    public static void exit() {
        State s = TL.get();
        int d = s.depth - 1;
        if (d < 0) { s.depth = 0; return; }

        s.depth = d;

        long end = System.nanoTime();
        Node node = s.stackNode[d];

        long total = end - s.stackStartNs[d];
        long self = total - s.stackChildNs[d];

        // propagate inclusive to parent as child time
        if (d > 0) {
            s.stackChildNs[d - 1] += total;
        }

        if (self > 0) {
            node.totalSelfNs += self;
            if (ENABLE_HISTOGRAM) {
                recordHistogram(node.hist, self);
            }
        }
    }

    // ---------------- dumps ----------------

    public static void dumpSpeedscopePerThread(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (State s : STATES.values()) {
            Path out = dir.resolve("thread-" + sanitize(s.threadName) + "-" + s.tid + ".speedscope.json");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                dumpTreeToSpeedscope(w, s, s.threadName + "-" + s.tid);
            }
        }
    }

    public static void dumpPercentilesPerThread(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (State s : STATES.values()) {
            Path out = dir.resolve("percentiles-" + sanitize(s.threadName) + "-" + s.tid + ".csv");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("stack,p50_ns,p90_ns,p99_ns,p999_ns,p100_ns,count");
                dumpTreeToPercentilesCsv(w, s);
            }
        }
    }

    public static void resetAll() {
        for (State s : STATES.values()) {
            s.root.resetSubtree();
            s.depth = 0;
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

    // ---------------- state / tree ----------------

    static final class State {
        final String threadName;
        final long tid;

        int depth = 0;

        Node[] stackNode = new Node[256];
        long[] stackStartNs = new long[256];
        long[] stackChildNs = new long[256];

        final Node root = new Node(0);

        State(String threadName, long tid) {
            this.threadName = threadName;
            this.tid = tid;
        }

        void growStacks() {
            int newCap = stackNode.length << 1;
            stackNode = Arrays.copyOf(stackNode, newCap);
            stackStartNs = Arrays.copyOf(stackStartNs, newCap);
            stackChildNs = Arrays.copyOf(stackChildNs, newCap);
        }
    }

    // A node represents a unique call-path element: (parent, methodId).
    static final class Node {
        final int methodId;

        long totalSelfNs = 0;
        Histogram hist; // nullable

        // Children stored in parallel arrays (fast for small branching factor)
        int[] childMethodId = new int[4];
        Node[] childNode = new Node[4];
        int childCount = 0;

        Node(int methodId) {
            this.methodId = methodId;
            if (ENABLE_HISTOGRAM) {
                this.hist = new Histogram(HIST_MAX_NS, HIST_SIG_DIGITS);
            }
        }

        Node getOrCreateChild(int mid) {
            // linear scan is usually fastest (small childCount, contiguous memory)
            for (int i = 0; i < childCount; i++) {
                if (childMethodId[i] == mid) return childNode[i];
            }
            // create
            Node n = new Node(mid);
            if (childCount == childMethodId.length) {
                int newCap = childCount << 1;
                childMethodId = Arrays.copyOf(childMethodId, newCap);
                childNode = Arrays.copyOf(childNode, newCap);
            }
            childMethodId[childCount] = mid;
            childNode[childCount] = n;
            childCount++;
            return n;
        }

        void resetSubtree() {
            // iterative DFS to avoid recursion depth issues
            ArrayDeque<Node> q = new ArrayDeque<>();
            q.add(this);
            while (!q.isEmpty()) {
                Node n = q.removeLast();
                n.totalSelfNs = 0;
                if (ENABLE_HISTOGRAM && n.hist != null) {
                    n.hist.reset();
                }
                for (int i = 0; i < n.childCount; i++) {
                    q.add(n.childNode[i]);
                }
            }
        }
    }

    private static void recordHistogram(Histogram h, long valueNs) {
        if (valueNs <= 0) return;
        if (valueNs > HIST_MAX_NS) h.recordValue(HIST_MAX_NS);
        else h.recordValue(valueNs);
    }

    // ---------------- export helpers ----------------

    private static final class PathEntry {
        final int[] frames; // methodIds for the stack (excluding synthetic root)
        final int len;
        final long weight; // totalSelfNs
        final Histogram hist;

        PathEntry(int[] frames, int len, long weight, Histogram hist) {
            this.frames = frames;
            this.len = len;
            this.weight = weight;
            this.hist = hist;
        }
    }

    private static List<PathEntry> collectPathEntries(State s) {
        // iterative DFS over tree, maintaining a methodId path stack
        ArrayList<PathEntry> out = new ArrayList<>();

        int[] path = new int[256];
        int depth = 0;

        // stack of iterators over children
        ArrayDeque<IterFrame> st = new ArrayDeque<>();
        st.push(new IterFrame(s.root, 0, false));

        while (!st.isEmpty()) {
            IterFrame f = st.peek();
            Node n = f.node;

            if (!f.entered) {
                f.entered = true;
                // push this node's methodId onto path (skip synthetic root methodId=0)
                if (n.methodId != 0) {
                    if (depth == path.length) path = Arrays.copyOf(path, depth << 1);
                    path[depth++] = n.methodId;

                    if (n.totalSelfNs > 0) {
                        int[] copy = Arrays.copyOf(path, depth);
                        out.add(new PathEntry(copy, depth, n.totalSelfNs, n.hist));
                    }
                }
            }

            if (f.childIndex < n.childCount) {
                Node child = n.childNode[f.childIndex++];
                st.push(new IterFrame(child, 0, false));
            } else {
                st.pop();
                // pop path on leaving node (if not root)
                if (n.methodId != 0) depth--;
            }
        }

        // optional: sort by weight (visual nicer + stable)
        out.sort((a, b) -> Long.compare(b.weight, a.weight));
        return out;
    }

    private static final class IterFrame {
        final Node node;
        int childIndex;
        boolean entered;
        IterFrame(Node node, int childIndex, boolean entered) {
            this.node = node;
            this.childIndex = childIndex;
            this.entered = entered;
        }
    }

    private static void dumpTreeToPercentilesCsv(PrintWriter w, State s) {
        if (!ENABLE_HISTOGRAM) {
            w.println("Histograms disabled.");
            return;
        }
        List<PathEntry> entries = collectPathEntries(s);
        for (PathEntry e : entries) {
            String stack = toCollapsedStack(e.frames, e.len);
            Histogram h = e.hist;
            long p50 = h.getValueAtPercentile(50.0);
            long p90 = h.getValueAtPercentile(90.0);
            long p99 = h.getValueAtPercentile(99.0);
            long p999 = h.getValueAtPercentile(99.9);
            long p100 = h.getMaxValue();
            long count = h.getTotalCount();

            w.print(stack); w.print(',');
            w.print(p50); w.print(',');
            w.print(p90); w.print(',');
            w.print(p99); w.print(',');
            w.print(p999); w.print(',');
            w.print(p100); w.print(',');
            w.println(count);
        }
    }

    private static void dumpTreeToSpeedscope(PrintWriter w, State s, String profileName) {
        List<PathEntry> entries = collectPathEntries(s);

        // Build frame table: methodId -> frameIndex, and frames list (names)
        Map<Integer, Integer> midToIdx = new HashMap<>();
        List<String> frames = new ArrayList<>();

        for (PathEntry e : entries) {
            for (int i = 0; i < e.len; i++) {
                int mid = e.frames[i];
                if (!midToIdx.containsKey(mid)) {
                    midToIdx.put(mid, frames.size());
                    frames.add(MethodRegistry.nameFor(mid));
                }
            }
        }

        // JSON
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
        w.print("      \"name\": \""); w.print(escapeJson(profileName)); w.println("\",");
        w.println("      \"unit\": \"nanoseconds\",");
        w.println("      \"samples\": [");

        for (int i = 0; i < entries.size(); i++) {
            PathEntry e = entries.get(i);
            w.print("        [");
            for (int j = 0; j < e.len; j++) {
                int idx = midToIdx.get(e.frames[j]);
                w.print(idx);
                if (j < e.len - 1) w.print(",");
            }
            w.print("]");
            if (i < entries.size() - 1) w.println(",");
            else w.println();
        }

        w.println("      ],");
        w.println("      \"weights\": [");
        for (int i = 0; i < entries.size(); i++) {
            w.print("        ");
            w.print(entries.get(i).weight);
            if (i < entries.size() - 1) w.println(",");
            else w.println();
        }
        w.println("      ]");
        w.println("    }");
        w.println("  ]");
        w.println("}");
    }

    private static String toCollapsedStack(int[] frames, int len) {
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(';');
            sb.append(MethodRegistry.nameFor(frames[i]));
        }
        return sb.toString();
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
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}