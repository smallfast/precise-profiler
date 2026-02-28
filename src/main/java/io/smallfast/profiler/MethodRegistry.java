package io.smallfast.profiler;

public final class MethodRegistry {

    private static volatile String[] ID_TO_NAME = new String[4096];

    private MethodRegistry() {}

    public static void register(int id, String name) {
        ensureCapacity(id);
        ID_TO_NAME[id] = name;
    }

    public static String nameFor(int id) {
        String[] arr = ID_TO_NAME;
        if (id >= 0 && id < arr.length) {
            String s = arr[id];
            return (s == null) ? ("<id:" + id + ">") : s;
        }
        return "<id:" + id + ">";
    }

    private static synchronized void ensureCapacity(int id) {
        if (id < ID_TO_NAME.length) return;
        int newCap = ID_TO_NAME.length;
        while (newCap <= id) newCap <<= 1;
        String[] next = new String[newCap];
        System.arraycopy(ID_TO_NAME, 0, next, 0, ID_TO_NAME.length);
        ID_TO_NAME = next;
    }
}