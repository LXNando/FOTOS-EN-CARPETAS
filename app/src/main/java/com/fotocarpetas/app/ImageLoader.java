package com.fotocarpetas.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga miniaturas en segundo plano con caché en memoria. */
final class ImageLoader {

    private static ImageLoader instance;

    static synchronized ImageLoader get() {
        if (instance == null) instance = new ImageLoader();
        return instance;
    }

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageLoader() {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void loadThumb(final ImageView iv, final File f, final int size) {
        final String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length() + "|" + size;
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap thumb = null;
            try {
                Bitmap full = decodeSampled(f, size, size);
                if (full != null) {
                    thumb = ThumbnailUtils.extractThumbnail(full, size, size);
                    if (thumb != full) full.recycle();
                    cache.put(key, thumb);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = thumb;
            main.post(() -> {
                if (result != null && key.equals(iv.getTag())) iv.setImageBitmap(result);
            });
        });
    }

    /** Decodifica reduciendo tamaño para no agotar memoria y corrige la rotación EXIF. */
    static Bitmap decodeSampled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2;
        while (Math.max(o.outWidth, o.outHeight) / s > 4096
                || (long) (o.outWidth / s) * (o.outHeight / s) > 12_000_000L) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (b == null) return null;
        int deg = rotationDegrees(f);
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    static int rotationDegrees(File f) {
        try {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            int o = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** {ancho, alto} de la foto tal como se ve (ya rotada). */
    static int[] dimensions(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int deg = rotationDegrees(f);
        if (deg == 90 || deg == 270) return new int[]{o.outHeight, o.outWidth};
        return new int[]{o.outWidth, o.outHeight};
    }
}
