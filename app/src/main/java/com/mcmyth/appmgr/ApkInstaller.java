package com.mcmyth.appmgr;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ApkInstaller extends BroadcastReceiver {

    static void install(Context context, BackupStore store, BackupStore.Entry entry) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(entry.metadata.packageName);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            if (entry.split()) {
                Set<String> names = new HashSet<>();
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(store.open(entry)))) {
                    ZipEntry part;
                    while ((part = zip.getNextEntry()) != null) {
                        String name = part.getName();
                        if (part.isDirectory() || !name.matches("[A-Za-z0-9_.-]+\\.apk")) continue;
                        if (!names.add(name)) throw new IOException("安装包中有重复的 APK");
                        stage(session, name, zip, part.getSize());
                    }
                }
                if (!names.contains("base.apk")) throw new IOException("缺少基础安装包 base.apk");
            } else {
                try (InputStream stream = store.open(entry)) {
                    stage(session, "base.apk", stream, entry.bytes > 0 ? entry.bytes : -1);
                }
            }
            Intent callback = new Intent(context, ApkInstaller.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(context, id, callback, flags);
            session.commit(result.getIntentSender());
            committed = true;
        } finally {
            if (!committed) installer.abandonSession(id);
        }
    }

    private static void stage(PackageInstaller.Session session, String name,
                              InputStream in, long bytes) throws IOException {
        try (OutputStream out = session.openWrite(name, 0, bytes)) {
            BackupStore.copy(in, out);
            session.fsync(out);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (RuntimeException ignored) { }
            }
            Toast.makeText(context, "无法打开系统安装确认", Toast.LENGTH_LONG).show();
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "安装完成", Toast.LENGTH_SHORT).show();
        } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, message == null ? "安装失败" : "安装失败：" + message,
                    Toast.LENGTH_LONG).show();
        }
    }
}

