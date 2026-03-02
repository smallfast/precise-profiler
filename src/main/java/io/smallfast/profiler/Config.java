package io.smallfast.profiler;

final class Config {
    final String[] packages; // prefixes
    final boolean dryRun;
    final boolean histogram;
    final String[] skipMethods;

    private Config(String[] packages, boolean dryRun, boolean histogram, String[] skipMethods) {
        this.packages = packages;
        this.dryRun = dryRun;
        this.histogram = histogram;
        this.skipMethods = skipMethods;
    }

    static Config parse(String args) {
        String[] pkgs = new String[0];
        boolean dry = false;
        boolean histogram = false;
        String[] skip = new String[0];

        if (args != null && !args.isBlank()) {
            String[] parts = args.split(",");
            for (String p : parts) {
                String part = p.trim();
                if (part.startsWith("packages=")) {
                    String v = part.substring("packages=".length()).trim();
                    if (!v.isEmpty()) {
                        String[] raw = v.split("\\|");
                        for (int i = 0; i < raw.length; i++) raw[i] = raw[i].trim();
                        pkgs = raw;
                    }
                } else if (part.equalsIgnoreCase("dryRun=true")) {
                    dry = true;
                } else if (part.equalsIgnoreCase("dryRun=false")) {
                    dry = false;
                } else if (part.equalsIgnoreCase("histogram=true")) {
                    histogram = true;
                } else if (part.equalsIgnoreCase("histogram=false")) {
                    histogram = false;
                } else if (part.startsWith("skipMethods=")) {
                    String v = part.substring("skipMethods=".length()).trim();
                    if (!v.isEmpty()) {
                        String[] raw = v.split("\\|");
                        for (int i = 0; i < raw.length; i++) raw[i] = raw[i].trim();
                        skip = raw;
                    }
                }

            }
        }

        return new Config(pkgs, dry, histogram, skip);
    }

    boolean shouldSkipMethod(String methodName) {
        for (String name : skipMethods) {
            if (name.equals(methodName)) return true;
        }
        return false;
    }

    boolean shouldInstrument(String className) {
        if (packages.length == 0) return false;
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty()) continue;
            String prefix = pkg.endsWith(".") ? pkg : (pkg + ".");
            if (className.equals(pkg) || className.startsWith(prefix) || className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }
}