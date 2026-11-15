package com.mcmyth.appmgr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public final class SharedFileProvider extends ContentProvider {
    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".shared")
                .appendPath(file.getParentFile().getName()).appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 2) throw new FileNotFoundException();
        try {
            File root = new File(getContext().getCacheDir(), "shares").getCanonicalFile();
            File folder = new File(root, uri.getPathSegments().get(0)).getCanonicalFile();
            File file = new File(folder, uri.getPathSegments().get(1)).getCanonicalFile();
            if (!root.equals(folder.getParentFile()) || !folder.equals(file.getParentFile())
                    || !file.isFile()) throw new FileNotFoundException();
            return file;
        } catch (IOException exception) {
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try {
            File file = resolve(uri);
            ArrayList<String> columns = new ArrayList<>();
            ArrayList<Object> row = new ArrayList<>();
            for (String column : projection) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                    columns.add(column);
                    row.add(file.getName());
                } else if (OpenableColumns.SIZE.equals(column)) {
                    columns.add(column);
                    row.add(file.length());
                }
            }
            MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException ignored) {
            return new MatrixCursor(projection, 0);
        }
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk")
                ? BackupStore.APK_MIME : "application/zip";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}

