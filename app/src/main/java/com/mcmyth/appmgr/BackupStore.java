package com.mcmyth.appmgr;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class BackupStore {
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String DIRECTORY = "backupDirectory";

    static final class Metadata {
        final String label;
        final String packageName;
        final String version;
        final long versionCode;
        final Drawable icon;

        Drawable newIcon() {
            Drawable.ConstantState state = icon.getConstantState();
            return state == null ? icon : state.newDrawable().mutate();
        }

        Metadata(PackageInfo info, String path, Context context) {
            PackageManager manager = context.getPackageManager();
            info.applicationInfo.sourceDir = path;
            info.applicationInfo.publicSourceDir = path;
            label = info.applicationInfo.loadLabel(manager).toString();
            packageName = info.packageName;
            version = info.versionName == null ? "—" : info.versionName;
            versionCode = info.getLongVersionCode();
            Drawable original = info.applicationInfo.loadIcon(manager);
            Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            original.setBounds(0, 0, 96, 96);
            original.draw(new Canvas(bitmap));
            icon = new BitmapDrawable(context.getResources(), bitmap);
        }
    }

    static final class Entry {
        final File file;
        final Uri uri;
        final String name;
        final long bytes;
        final long modified;
        final Metadata metadata;

        Entry(File file, Uri uri, String name, long bytes, long modified, Metadata metadata) {
            this.file = file;
            this.uri = uri;
            this.name = name;
            this.bytes = bytes;
            this.modified = modified;
            this.metadata = metadata;
        }

        String key() { return file != null ? file.getAbsolutePath() : uri.toString(); }
        String label() { return metadata != null ? metadata.label : name; }
        boolean split() { return name.toLowerCase(Locale.ROOT).endsWith(".apks"); }
    }

    static final class Snapshot {
        final List<Entry> entries;
        final long backupBytes;
        final long usedBytes;
        final long totalBytes;
        final String directory;

        Snapshot(List<Entry> entries, long backupBytes, long usedBytes, long totalBytes,
                 String directory) {
            this.entries = entries;
            this.backupBytes = backupBytes;
            this.usedBytes = usedBytes;
            this.totalBytes = totalBytes;
            this.directory = directory;
        }

        Snapshot without(Entry deleted) {
            List<Entry> remaining = new ArrayList<>(entries.size());
            long bytes = 0;
            for (Entry entry : entries) {
                if (entry.key().equals(deleted.key())) continue;
                remaining.add(entry);
                bytes += entry.bytes;
            }
            return new Snapshot(remaining, bytes, usedBytes, totalBytes, directory);
        }

        Snapshot with(Entry added) {
            List<Entry> updated = new ArrayList<>(entries);
            updated.removeIf(entry -> entry.key().equals(added.key()));
            updated.add(added);
            updated.sort(Comparator.comparingLong((Entry entry) -> entry.modified)
                    .reversed().thenComparing(entry -> entry.name));
            long bytes = 0;
            for (Entry entry : updated) bytes += entry.bytes;
            long used = totalBytes > 0 ? Math.min(totalBytes, usedBytes + bytes - backupBytes) : usedBytes;
            return new Snapshot(updated, bytes, used, totalBytes, directory);
        }
    }

    static final class SavedBackup {
        final Entry entry;
        final Uri directory;

        SavedBackup(Entry entry, Uri directory) {
            this.entry = entry;
            this.directory = directory;
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences preferences;

    BackupStore(Context context, SharedPreferences preferences) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
        this.preferences = preferences;
    }

    Uri tree() {
        String saved = preferences.getString(DIRECTORY, null);
        return saved == null ? null : Uri.parse(saved);
    }

    void setTree(Uri uri) {
        preferences.edit().putString(DIRECTORY, uri == null ? null : uri.toString()).apply();
    }

    File defaultDirectory() {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(external != null ? external : context.getFilesDir(), "APK备份");
    }

    String directoryLabel(Uri tree) {
        if (tree == null) return defaultDirectory().getAbsolutePath();
        String id = DocumentsContract.getTreeDocumentId(tree);
        return id.isEmpty() ? tree.toString() : id;
    }

    private Uri root(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    Snapshot scan(ScanProgress progress) throws IOException {
        Uri tree = tree();
        List<Entry> entries = new ArrayList<>();
        long used = 0;
        long total = 0;
        if (tree == null) {
            File folder = defaultDirectory();
            File[] files = folder.listFiles();
            if (folder.exists() && files == null) throw new IOException("无法读取备份目录");
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isPackage(file.getName())) {
                        entries.add(new Entry(file, null, file.getName(), file.length(), file.lastModified(), null));
                    }
                }
            }
            File volume = folder.exists() ? folder : folder.getParentFile();
            if (volume != null && volume.exists()) {
                StatFs stats = new StatFs(volume.getPath());
                total = stats.getTotalBytes();
                used = total - stats.getFreeBytes();
            }
        } else {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE};
            try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
                if (cursor == null) throw new IOException("无法读取备份目录");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (name != null && isPackage(name)
                            && !Document.MIME_TYPE_DIR.equals(cursor.getString(4))) {
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                        entries.add(new Entry(null, uri, name, cursor.getLong(2), cursor.getLong(3), null));
                    }
                }
            }
            // ExternalStorageProvider exposes volume identifiers for local directory trees.
            if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
                String document = DocumentsContract.getTreeDocumentId(tree);
                String volume = document.split(":", 2)[0];
                for (android.os.storage.StorageVolume storage :
                        context.getSystemService(android.os.storage.StorageManager.class).getStorageVolumes()) {
                    if (!(volume.equalsIgnoreCase("primary") && storage.isPrimary())
                            && !volume.equalsIgnoreCase(storage.getUuid())) continue;
                    if (android.os.Build.VERSION.SDK_INT >= 30 && storage.getDirectory() != null) {
                        StatFs stats = new StatFs(storage.getDirectory().getPath());
                        total = stats.getTotalBytes();
                        used = total - stats.getFreeBytes();
                    } else if (storage.isPrimary()) {
                        StatFs stats = new StatFs(Environment.getExternalStorageDirectory().getPath());
                        total = stats.getTotalBytes();
                        used = total - stats.getFreeBytes();
                    }
                    break;
                }
            }
        }
        int count = entries.size();
        progress.update(0, count, null);
        long sum = 0;
        for (int i = 0; i < count; i++) {
            Entry entry = read(entries.get(i));
            entries.set(i, entry);
            sum += entry.bytes;
            progress.update(i + 1, count, entry.label());
        }
        entries.sort(Comparator.comparingLong((Entry e) -> e.modified).reversed().thenComparing(e -> e.name));
        return new Snapshot(entries, sum, used, total, directoryLabel(tree));
    }

    private static boolean isPackage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apks");
    }

    private Entry read(Entry entry) {
        File file = entry.file;
        File temporary = null;
        Metadata metadata = null;
        try {
            File base = file;
            if (entry.split() || file == null) {
                temporary = File.createTempFile("inspect-", ".apk", context.getCacheDir());
                if (entry.split()) {
                    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(open(entry)))) {
                        ZipEntry part;
                        while ((part = zip.getNextEntry()) != null) {
                            if (part.getName().equals("base.apk") && !part.isDirectory()) {
                                try (OutputStream out = new FileOutputStream(temporary)) { copy(zip, out); }
                                break;
                            }
                        }
                    }
                } else {
                    try (InputStream in = open(entry); OutputStream out = new FileOutputStream(temporary)) {
                        copy(in, out);
                    }
                }
                base = temporary;
            }
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(base.getPath(), 0);
            if (info != null && info.applicationInfo != null) {
                metadata = new Metadata(info, base.getPath(), context);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable files remain listed so the user can share or delete them.
        } finally {
            if (temporary != null) temporary.delete();
        }
        return new Entry(file, entry.uri, entry.name, entry.bytes, entry.modified, metadata);
    }

    InputStream open(Entry entry) throws IOException {
        if (entry.file != null) return new FileInputStream(entry.file);
        InputStream stream = resolver.openInputStream(entry.uri);
        if (stream == null) throw new IOException("无法打开 " + entry.name);
        return stream;
    }

    SavedBackup save(AppCatalog.App app) throws IOException {
        String filename = packageFilename(app);
        Uri tree = tree();
        if (tree == null) {
            File folder = defaultDirectory();
            ensureDirectory(folder);
            File target = new File(folder, filename);
            if (target.exists()) {
                filename = withSuffix(filename, UUID.randomUUID().toString().substring(0, 8));
                target = new File(folder, filename);
            }
            File temporary = File.createTempFile("backup-", ".part", folder);
            try {
                try (OutputStream out = new FileOutputStream(temporary)) { write(app, out); }
                if (!temporary.renameTo(target)) throw new IOException("无法保存备份文件");
            } finally {
                temporary.delete();
            }
            return new SavedBackup(read(new Entry(target, null, filename, target.length(),
                    target.lastModified(), null)), null);
        }

        filename = withSuffix(filename, UUID.randomUUID().toString().substring(0, 8));
        Uri document = DocumentsContract.createDocument(resolver, root(tree),
                "application/octet-stream", "backup-" + UUID.randomUUID() + ".part");
        if (document == null) throw new IOException("所选目录无法创建文件");
        boolean complete = false;
        try {
            try (OutputStream out = resolver.openOutputStream(document, "w")) {
                if (out == null) throw new IOException("无法写入备份目录");
                write(app, out);
            }
            Uri renamed = DocumentsContract.renameDocument(resolver, document, filename);
            if (renamed == null) throw new IOException("所选目录不支持重命名，请选择其他目录");
            complete = true;
            long size = 0;
            long modified = System.currentTimeMillis();
            try (Cursor cursor = resolver.query(renamed, new String[]{Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) filename = cursor.getString(0);
                    if (!cursor.isNull(1)) size = Math.max(0, cursor.getLong(1));
                    if (cursor.getLong(2) > 0) modified = cursor.getLong(2);
                }
            } catch (RuntimeException ignored) {
                // The file is already saved even if its provider cannot report metadata.
            }
            return new SavedBackup(read(new Entry(null, renamed, filename, size, modified, null)), tree);
        } finally {
            if (!complete) {
                try { DocumentsContract.deleteDocument(resolver, document); }
                catch (IOException | RuntimeException ignored) { }
            }
        }
    }

    void delete(Entry entry) throws IOException {
        boolean deleted = entry.file != null ? entry.file.delete()
                : DocumentsContract.deleteDocument(resolver, entry.uri);
        if (!deleted) throw new IOException("无法删除 " + entry.name);
    }

    File forSharing(AppCatalog.App app) throws IOException {
        File target = shareTarget(packageFilename(app));
        try {
            try (OutputStream out = new FileOutputStream(target)) { write(app, out); }
            return target;
        } catch (IOException | RuntimeException error) {
            target.delete();
            throw error;
        }
    }

    File forSharing(Entry entry) throws IOException {
        File target = shareTarget(entry.name);
        try {
            try (InputStream in = open(entry); OutputStream out = new FileOutputStream(target)) { copy(in, out); }
            return target;
        } catch (IOException | RuntimeException error) {
            target.delete();
            throw error;
        }
    }

    private File shareTarget(String name) throws IOException {
        File base = new File(context.getCacheDir(), "shares");
        File[] old = base.listFiles();
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (old != null) {
            for (File folder : old) {
                if (!folder.isDirectory() || folder.lastModified() >= cutoff) continue;
                File[] files = folder.listFiles();
                if (files != null) for (File file : files) if (file.isFile()) file.delete();
                folder.delete();
            }
        }
        File folder = new File(base, UUID.randomUUID().toString());
        ensureDirectory(folder);
        return new File(folder, safeName(name));
    }

    private static void ensureDirectory(File folder) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建目录");
    }

    private static String packageFilename(AppCatalog.App app) {
        return safeName(app.name) + "_" + safeName(app.packageName) + "_" + app.versionCode
                + (app.files.size() > 1 ? ".apks" : ".apk");
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "应用";
        return cleaned;
    }

    private static String withSuffix(String name, String suffix) {
        int extension = name.lastIndexOf('.');
        return name.substring(0, extension) + "_" + suffix + name.substring(extension);
    }

    private static void write(AppCatalog.App app, OutputStream output) throws IOException {
        if (app.files.isEmpty()) throw new IOException("未找到安装包");
        if (app.files.size() == 1) {
            try (InputStream in = new FileInputStream(app.files.get(0))) { copy(in, output); }
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            for (int i = 0; i < app.files.size(); i++) {
                File file = app.files.get(i);
                zip.putNextEntry(new ZipEntry(i == 0 ? "base.apk" : "split_" + i + ".apk"));
                try (InputStream in = new FileInputStream(file)) { copy(in, zip); }
                zip.closeEntry();
            }
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }
}

