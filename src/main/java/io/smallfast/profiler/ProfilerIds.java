package io.smallfast.profiler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProfilerIds {

    private static final ConcurrentHashMap<String, Integer> METHOD_TO_ID = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1); // 0 reserved

    // grows as needed; only used on dump/collision reporting
    private static volatile String[] ID_TO_METHOD = new String[1024];

    private ProfilerIds() {}

    public static int idFor(String methodName) {
        Integer existing = METHOD_TO_ID.get(methodName);
        if (existing != null) return existing;

        int id = NEXT_ID.getAndIncrement();
        Integer raced = METHOD_TO_ID.putIfAbsent(methodName, id);
        if (raced != null) return raced;

        ensureCapacity(id);
        ID_TO_METHOD[id] = methodName;
        return id;
    }

    public static String nameFor(int id) {
        String[] arr = ID_TO_METHOD;
        if (id >= 0 && id < arr.length) {
            String s = arr[id];
            return (s == null) ? ("<id:" + id + ">") : s;
        }
        return "<id:" + id + ">";
    }

    private static synchronized void ensureCapacity(int id) {
        if (id < ID_TO_METHOD.length) return;
        int newCap = ID_TO_METHOD.length;
        while (newCap <= id) newCap <<= 1;
        String[] next = new String[newCap];
        System.arraycopy(ID_TO_METHOD, 0, next, 0, ID_TO_METHOD.length);
        ID_TO_METHOD = next;
    }
}