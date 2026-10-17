// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Test APK only. Java/Android APIs keep the provider independent of the target app's runtime. */
public final class JournalDocumentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !Arrays.asList("normal", "deny_write", "deny_read", "truncated", "replaced").contains(parts.get(0))) {
            throw new IllegalArgumentException("Unknown fixture path");
        }
        if (!UUID.fromString(parts.get(1)).toString().equals(parts.get(1))) {
            throw new IllegalArgumentException("Invalid fixture identity");
        }
        File root = new File(getContext().getFilesDir(), "journal-provider-fixtures");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Cannot create fixture directory");
        }
        return new File(root, parts.get(1) + ".json");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        String scenario = uri.getPathSegments().get(0);
        try {
            if (mode.equals("r")) {
                if (scenario.equals("deny_read")) throw new FileNotFoundException("Injected unavailable read");
                File readable = file;
                if (scenario.equals("truncated")) {
                    readable = new File(file.getPath() + ".read");
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    Files.write(readable.toPath(), Arrays.copyOf(bytes, bytes.length / 2));
                } else if (scenario.equals("replaced")) {
                    readable = new File(file.getPath() + ".read");
                    String payload = "{\"records\":[],\"deletedIds\":[]}";
                    StringBuilder hash = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))) {
                        hash.append(String.format(Locale.ROOT, "%02x", b & 255));
                    }
                    String replacement = "{\"format\":\"solsleep-journal\",\"version\":2,\"createdAt\":\"2026-10-17T08:00:00Z\",\"sha256\":\"" + hash + "\",\"records\":[],\"deletedIds\":[]}";
                    Files.write(readable.toPath(), replacement.getBytes(StandardCharsets.UTF_8));
                }
                return ParcelFileDescriptor.open(readable, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (!mode.equals("wt")) throw new IllegalArgumentException("Unsupported fixture mode");
            if (scenario.equals("deny_write")) throw new FileNotFoundException("Injected unavailable write");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException("Fixture transfer unavailable");
            failure.initCause(e);
            throw failure;
        }
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = file(uri);
        new File(file.getPath() + ".read").delete();
        return file.delete() ? 1 : 0;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Fixture supports file transfer only");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
