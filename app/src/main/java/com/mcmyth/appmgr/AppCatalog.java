package com.mcmyth.appmgr;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.icu.text.AlphabeticIndex;
import android.icu.text.Collator;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

final class AppCatalog {
    static final class App {
        final String name;
        final String packageName;
        final String version;
        final long versionCode;
        final long installedAt;
        final long updatedAt;
        final long bytes;
        final String dataPath;
        final int compileSdk;
        final int targetSdk;
        final int minSdk;
        final String nativeLibraryDir;
        final boolean system;
        final List<File> files;
        final Intent launch;

        App(PackageInfo info, PackageManager manager) {
            ApplicationInfo application = info.applicationInfo;
            packageName = info.packageName;
            name = application.loadLabel(manager).toString();
            version = info.versionName == null ? "—" : info.versionName;
            versionCode = info.getLongVersionCode();
            installedAt = info.firstInstallTime;
            updatedAt = info.lastUpdateTime;
            system = (application.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            files = new ArrayList<>();
            if (application.sourceDir != null) files.add(new File(application.sourceDir));
            if (application.splitSourceDirs != null) {
                for (String split : application.splitSourceDirs) files.add(new File(split));
            }
            long total = 0;
            for (File file : files) total += file.length();
            bytes = total;
            dataPath = application.dataDir;
            compileSdk = Build.VERSION.SDK_INT >= 31 ? application.compileSdkVersion : 0;
            targetSdk = application.targetSdkVersion;
            minSdk = application.minSdkVersion;
            nativeLibraryDir = application.nativeLibraryDir;
            launch = manager.getLaunchIntentForPackage(packageName);
        }
    }

    static String installedApkAbis(List<File> files) {
        Set<String> abis = new TreeSet<>();
        boolean incomplete = false;
        for (File file : files) {
            try (ZipFile apk = new ZipFile(file)) {
                apk.stream().forEach(entry -> {
                    String path = entry.getName();
                    if (!path.startsWith("lib/") || !path.endsWith(".so")) return;
                    int abiEnd = path.indexOf('/', 4);
                    if (abiEnd > 4 && path.indexOf('/', abiEnd + 1) == -1) {
                        abis.add(path.substring(4, abiEnd));
                    }
                });
            } catch (IOException | SecurityException unreadable) {
                incomplete = true;
            }
        }
        if (abis.isEmpty()) {
            return files.isEmpty() || incomplete ? "无法确认" : "未发现原生库";
        }
        String value = String.join("、", abis);
        return incomplete ? value + "（部分 APK 未能读取）" : value;
    }

    static List<App> read(PackageManager manager, ScanProgress progress) {
        List<PackageInfo> packages = new ArrayList<>();
        for (PackageInfo info : manager.getInstalledPackages(0)) {
            if (info.applicationInfo != null) packages.add(info);
        }
        int total = packages.size();
        List<App> result = new ArrayList<>(total);
        progress.update(0, total, null);
        for (PackageInfo info : packages) {
            App app = new App(info, manager);
            result.add(app);
            progress.update(result.size(), total, app.name);
        }
        return result;
    }

    static App readPackage(PackageManager manager, String packageName) {
        try {
            PackageInfo info = manager.getPackageInfo(packageName, 0);
            return info.applicationInfo == null ? null : new App(info, manager);
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }

    private final AlphabeticIndex.ImmutableIndex<Object> index =
            new AlphabeticIndex<Object>(Locale.SIMPLIFIED_CHINESE)
                    .addLabels(Locale.ENGLISH).buildImmutableIndex();
    private final Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

    String section(String name) {
        String label = index.getBucket(index.getBucketIndex(name)).getLabel();
        return label.matches("[A-Z]") ? label : "#";
    }

    Comparator<App> byName() {
        return Comparator.comparing((App a) -> section(a.name), (a, b) -> {
            if (a.equals(b)) return 0;
            if (a.equals("#")) return 1;
            if (b.equals("#")) return -1;
            return a.compareTo(b);
        }).thenComparing(a -> a.name, collator).thenComparing(a -> a.packageName);
    }

    static boolean matches(String query, String... values) {
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (String word : words) {
            boolean found = false;
            for (String value : values) {
                if (value.toLowerCase(Locale.ROOT).contains(word)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    enum BackupStatus {
        UNINSTALLED("未安装", 0xFFD73542, "×"),
        OLDER("较低版本", 0xFF3949AB, "↓"),
        SAME("相同版本", 0xFF087D9D, ""),
        NEWER("较高版本", 0xFF287F59, "↑"),
        UNREADABLE("无法识别", 0xFF626B75, "!");

        final String label;
        final int color;
        final String badge;

        BackupStatus(String label, int color, String badge) {
            this.label = label;
            this.color = color;
            this.badge = badge;
        }

        static BackupStatus compare(BackupStore.Entry entry, App installed) {
            if (entry.metadata == null) return UNREADABLE;
            if (installed == null) return UNINSTALLED;
            int version = Long.compare(entry.metadata.versionCode, installed.versionCode);
            return version < 0 ? OLDER : version > 0 ? NEWER : SAME;
        }
    }
}

