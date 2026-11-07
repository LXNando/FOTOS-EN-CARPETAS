package com.fotocarpetas.app;

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
import java.util.List;
import java.util.Locale;

/**
 * Da acceso temporal a un archivo de la app (sin exponer nada más):
 * - a la app de cámara, para que escriba la foto directo en la carpeta elegida;
 * - a otras apps, cuando el usuario comparte una foto.
 */
public class PhotoProvider extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".fotos";
    }

    static Uri uriFor(Context c, File f) {
        String rel = Storage.relPath(Storage.root(c), f);
        Uri.Builder b = new Uri.Builder().scheme("content").authority(authority(c));
        for (String seg : rel.split("/")) if (!seg.isEmpty()) b.appendPath(seg);
        return b.build();
    }

    /** Segmento reservado para los ZIP temporales (los nombres de carpeta no pueden empezar con punto). */
    private static final String ZIP_SEGMENT = ".zip";

    static File zipCacheDir(Context c) {
        File d = new File(c.getCacheDir(), "zip");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static Uri zipUriFor(Context c, File zip) {
        return new Uri.Builder().scheme("content").authority(authority(c))
                .appendPath(ZIP_SEGMENT).appendPath(zip.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        java.util.List<String> segs = uri.getPathSegments();
        boolean zip = !segs.isEmpty() && ZIP_SEGMENT.equals(segs.get(0));
        File root = zip ? zipCacheDir(getContext()) : Storage.root(getContext());
        File f = root;
        for (int i = zip ? 1 : 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            if (seg.equals("..") || seg.equals(".") || seg.contains("/")) throw new FileNotFoundException();
            f = new File(f, seg);
        }
        try {
            String r = root.getCanonicalPath();
            String c = f.getCanonicalPath();
            if (!c.startsWith(r + File.separator)) throw new FileNotFoundException();
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (mode.contains("w")) {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols.add(col);
                vals.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols.add(col);
                vals.add(f.length());
            }
        }
        MatrixCursor c = new MatrixCursor(cols.toArray(new String[0]), 1);
        c.addRow(vals.toArray());
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return null;
        n = n.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".xlsx")) return Actions.XLSX_MIME;
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
