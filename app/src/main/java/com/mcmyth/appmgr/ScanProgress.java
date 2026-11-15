package com.mcmyth.appmgr;

@FunctionalInterface
interface ScanProgress {
    ScanProgress IGNORE = (read, total, name) -> { };

    void update(int read, int total, String name);
}
