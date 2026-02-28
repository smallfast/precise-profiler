package io.smallfast.profiler;

final class Config {
    final String[] packages; // prefixes
    final boolean dryRun;

    private Config(String[] packages, boolean dryRun) {
        this.packages = packages;
        this.dryRun = dryRun;
    }

    static Config parse(String args) {
        // Defaults: instrument nothing unless packages= is given.
        String[] pkgs = new String[0];
        boolean dry = false;

        if (args != null && !args.isBlank()) {
            // args are comma-separated key=value pairs
            // packages are separated by '|'
            String[] parts = args.split(",");
            for (String p : parts) {
                String part = p.trim();
                if (part.startsWith("packages=")) {
                    String v = part.substring("packages=".length()).trim();
                    if (!v.isEmpty()) {
                        String[] raw = v.split("\\|");
                        // normalize: no trailing dot needed; treat as prefix
                        for (int i = 0; i < raw.length; i++) raw[i] = raw[i].trim();
                        pkgs = raw;
                    }
                } else if (part.equalsIgnoreCase("dryRun=true")) {
                    dry = true;
                } else if (part.equalsIgnoreCase("dryRun=false")) {
                    dry = false;
                }
            }
        }

        return new Config(pkgs, dry);
    }

    boolean shouldInstrument(String className) {
        if (packages.length == 0) return false;
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty()) continue;
            // Accept both "com.ppb.code" and "com.ppb.code."
            if (className.equals(pkg) || className.startsWith(pkg.endsWith(".") ? pkg : (pkg + "."))) {
                return true;
            }
            // Also allow plain prefix match (in case user provided trailing dot)
            if (className.startsWith(pkg)) return true;
        }
        return false;
    }
}