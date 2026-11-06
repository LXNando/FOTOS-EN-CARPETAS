package com.fotocarpetas.app;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Acciones compartidas entre pantallas: pedir nombre, compartir, copiar a la galería. */
final class Actions {

    private Actions() {}

    interface NameCallback {
        void onName(String name);
    }

    static void askName(Activity a, String title, String initial, final NameCallback cb) {
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setHint("Nombre");
        et.setText(initial);
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(a);
        int p = MainActivity.dp(a, 22);
        box.setPadding(p, MainActivity.dp(a, 8), p, 0);
        box.addView(et);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Aceptar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        Runnable accept = () -> {
            String name = et.getText().toString().trim();
            String err = Storage.validateName(name);
            if (err != null) {
                et.setError(err);
            } else {
                dialog.dismiss();
                cb.onName(name);
            }
        };
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> accept.run()));
        et.setOnEditorActionListener((v, actionId, event) -> {
            accept.run();
            return true;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        et.requestFocus();
    }

    static void confirm(Activity a, String title, String message, String yes, Runnable onYes) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    static void share(Activity a, List<File> photos) {
        if (photos.isEmpty()) {
            Toast.makeText(a, "No hay fotos para compartir", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : photos) uris.add(PhotoProvider.uriFor(a, f));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType("image/*");
        ClipData clip = ClipData.newRawUri("foto", uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, "Compartir"));
        } catch (Exception e) {
            Toast.makeText(a, "No hay apps para compartir", Toast.LENGTH_SHORT).show();
        }
    }

    static boolean canExportToGallery() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /** Copia las fotos de la carpeta (y subcarpetas) a Galería › Pictures/FotoCarpetas/..., manteniendo la estructura. */
    @TargetApi(29)
    static void exportToGallery(final Activity a, final File folder) {
        final File root = Storage.root(a);
        final List<File> photos = new ArrayList<>();
        Storage.collectPhotos(folder, photos);
        if (photos.isEmpty()) {
            Toast.makeText(a, "No hay fotos en esta carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Copiando a la Galería")
                .setMessage("Preparando…")
                .setCancelable(false)
                .create();
        progress.show();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            ContentResolver cr = a.getContentResolver();
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            int ok = 0;
            byte[] buf = new byte[64 * 1024];
            for (int k = 0; k < photos.size(); k++) {
                final int n = k + 1;
                ui.post(() -> progress.setMessage("Foto " + n + " de " + photos.size()));
                File f = photos.get(k);
                String rel = Storage.relPath(root, f.getParentFile());
                String relative = Environment.DIRECTORY_PICTURES + "/" + Storage.ROOT_NAME
                        + (rel.isEmpty() ? "" : "/" + rel) + "/";
                String lower = f.getName().toLowerCase(Locale.ROOT);
                String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Images.Media.MIME_TYPE, mime);
                v.put(MediaStore.Images.Media.RELATIVE_PATH, relative);
                v.put(MediaStore.Images.Media.DATE_TAKEN, f.lastModified());
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri item = null;
                try {
                    item = cr.insert(collection, v);
                    if (item == null) continue;
                    try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(item)) {
                        if (out == null) throw new java.io.IOException("sin salida");
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(item, done, null, null);
                    ok++;
                } catch (Exception e) {
                    if (item != null) {
                        try { cr.delete(item, null, null); } catch (Exception ignored) { }
                    }
                }
            }
            final int copied = ok;
            ui.post(() -> {
                progress.dismiss();
                if (a.isFinishing()) return;
                new AlertDialog.Builder(a)
                        .setTitle("Listo")
                        .setMessage("Se copiaron " + Storage.plural(copied, "foto", "fotos")
                                + " a la Galería, en el álbum «" + Storage.ROOT_NAME + "» (Pictures/"
                                + Storage.ROOT_NAME + "), con las mismas carpetas."
                                + (copied < photos.size() ? "\n\nNo se pudieron copiar "
                                + (photos.size() - copied) + "." : ""))
                        .setPositiveButton("Aceptar", null)
                        .show();
            });
        }).start();
    }
}
