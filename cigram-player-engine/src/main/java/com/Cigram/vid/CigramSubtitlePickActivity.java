package com.Cigram.vid;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Lets the viewer pick an .srt / .vtt file from the phone (Storage Access Framework: no storage
 * permission is needed, the viewer chooses the one file). It is a transparent helper screen so the
 * player screens need no onActivityResult of their own.
 *
 * The file is read and validated here, on a worker thread. It is NEVER uploaded and never shared
 * with a Together room: the result goes to the one callback that asked for it, on this device.
 *
 * While the picker is open the player screen is not in front; {@link #isPicking()} tells the player
 * screen not to treat that as "the viewer left" (its onStop would otherwise release the video).
 */
public class CigramSubtitlePickActivity extends Activity {
    private static final int REQ_PICK = 7311;
    private static final long PICK_TIMEOUT_MS = 3L * 60L * 1000L;

    public interface Callback {
        void onPicked(CigramSubtitles.Track track, String displayName, Uri uri);
        void onError(String message);
        void onCancelled();
    }

    private static Callback pending;
    private static long openedAt;

    /** True from the moment the picker is requested until it answers (or 3 minutes pass). */
    public static boolean isPicking() {
        return pending != null && SystemClock.elapsedRealtime() - openedAt < PICK_TIMEOUT_MS;
    }

    public static void pick(Activity from, Callback cb) {
        pending = cb;
        openedAt = SystemClock.elapsedRealtime();
        try {
            from.startActivity(new Intent(from, CigramSubtitlePickActivity.class));
            from.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            pending = null;
            cb.onError("تعذر فتح منتقي الملفات.");
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (pending == null) { finish(); return; }
        if (b != null) return;       // recreated by the system: the answer is already on its way
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-subrip", "text/vtt", "text/plain", "text/srt", "application/x-subtitle", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            done(null, null, null, "لا يوجد تطبيق لاختيار الملفات على هذا الجهاز.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            done(null, null, null, null);
            return;
        }
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { /* a one-time grant is enough for this session */ }
        final ContentResolver cr = getContentResolver();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                CigramSubtitles.Track track = null;
                String error = null;
                try {
                    track = CigramSubtitles.parse(read(cr, uri));
                } catch (CigramEngineException e) {
                    error = e.getMessage();
                } catch (Throwable t) {
                    error = "تعذر قراءة الملف.";
                }
                final CigramSubtitles.Track tr = track;
                final String err = error;
                final String name = displayName(cr, uri);
                main.post(new Runnable() {
                    @Override public void run() { done(tr, name, uri, err); }
                });
            }
        }, "cigram-sub-read").start();
    }

    private void done(CigramSubtitles.Track track, String name, Uri uri, String error) {
        Callback cb = pending;
        pending = null;
        try {
            if (cb != null) {
                if (track != null) cb.onPicked(track, name, uri);
                else if (error != null) cb.onError(error);
                else cb.onCancelled();
            }
        } catch (Throwable t) {
            CigramLog.w("subpick", "callback failed", t);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Reads at most MAX_BYTES + 1 bytes: a bigger file is rejected by the parser, never loaded whole. */
    static byte[] read(ContentResolver cr, Uri uri) throws Exception {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new CigramEngineException(CigramEngineException.PARSE, "تعذر فتح الملف.");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (out.size() <= CigramSubtitles.MAX_BYTES && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "ترجمة" : last;
    }

    @Override public void onBackPressed() {
        done(null, null, null, null);
    }
}
