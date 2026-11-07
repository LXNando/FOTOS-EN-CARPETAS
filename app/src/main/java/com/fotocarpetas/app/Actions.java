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

    /** Diálogo para escribir, editar o borrar la nota de una foto. */
    static void editNote(Activity a, final File photo, final Runnable onSaved) {
        final String current = Storage.readNote(photo);
        final EditText et = new EditText(a);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setSingleLine(false);
        et.setMinLines(4);
        et.setMaxLines(10);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setHint("Escribe una observación sobre esta foto…");
        et.setText(current);
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(a);
        int p = MainActivity.dp(a, 22);
        box.setPadding(p, MainActivity.dp(a, 8), p, 0);
        box.addView(et);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(current.isEmpty() ? "Agregar nota" : "Editar nota")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    if (Storage.writeNote(photo, et.getText().toString())) {
                        if (onSaved != null) onSaved.run();
                    } else {
                        Toast.makeText(a, "No se pudo guardar la nota", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null);
        if (!current.isEmpty()) {
            b.setNeutralButton("Borrar nota", (d, w) -> confirm(a, "¿Borrar la nota?",
                    "Se borrará solo la nota; la foto se conserva.", "Borrar", () -> {
                        Storage.writeNote(photo, "");
                        if (onSaved != null) onSaved.run();
                    }));
        }
        AlertDialog dialog = b.create();
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
        StringBuilder notes = new StringBuilder();
        for (File f : photos) {
            String note = Storage.readNote(f);
            if (note.isEmpty()) continue;
            if (notes.length() > 0) notes.append("\n\n");
            if (photos.size() > 1) notes.append(f.getName()).append(":\n");
            notes.append(note);
        }
        if (notes.length() > 0) i.putExtra(Intent.EXTRA_TEXT, notes.toString());
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

    // ---------- ZIP ----------

    interface Progress {
        void update(int done, int total);
    }

    /** Nombre del ZIP (y de su carpeta principal) para una carpeta. */
    static String zipBaseName(Activity a, File folder) {
        return folder.equals(Storage.root(a)) ? "Mis carpetas" : folder.getName();
    }

    /** Escribe la carpeta completa (subcarpetas, fotos y carpetas vacías) dentro de un ZIP. */
    static int writeZip(File folder, String baseName, OutputStream raw, Progress progress) throws java.io.IOException {
        List<File> photos = new ArrayList<>();
        Storage.collectPhotos(folder, photos);
        final int total = photos.size();
        final int[] done = {0};
        byte[] buf = new byte[64 * 1024];
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(raw))) {
            // Las fotos JPEG ya están comprimidas: guardarlas sin recomprimir es mucho más rápido.
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            addToZip(zip, folder, baseName + "/", buf, done, total, progress);
        }
        return done[0];
    }

    private static void addToZip(java.util.zip.ZipOutputStream zip, File dir, String prefix, byte[] buf,
                                 int[] done, int total, Progress progress) throws java.io.IOException {
        java.util.zip.ZipEntry dirEntry = new java.util.zip.ZipEntry(prefix);
        dirEntry.setTime(dir.lastModified());
        zip.putNextEntry(dirEntry);
        zip.closeEntry();
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        for (File f : fs) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) addToZip(zip, f, prefix + f.getName() + "/", buf, done, total, progress);
            } else if (Storage.isPhoto(f) && f.length() > 0) {
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(prefix + f.getName());
                e.setTime(f.lastModified());
                zip.putNextEntry(e);
                try (InputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                }
                zip.closeEntry();
                File note = Storage.noteFile(f);
                if (note.isFile() && note.length() > 0) {
                    java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(prefix + note.getName());
                    ne.setTime(note.lastModified());
                    zip.putNextEntry(ne);
                    try (InputStream in = new FileInputStream(note)) {
                        int r;
                        while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                    }
                    zip.closeEntry();
                }
                done[0]++;
                if (progress != null) progress.update(done[0], total);
            }
        }
    }

    private static boolean isEmptyFolder(File folder) {
        int[] c = Storage.deepCount(folder);
        return c[0] + c[1] == 0;
    }

    interface StreamOpener {
        OutputStream open() throws java.io.IOException;
    }

    /** Crea el ZIP en segundo plano mostrando el avance; al terminar llama a onDone en la pantalla. */
    private static void zipWithProgress(final Activity a, final File folder, final StreamOpener opener,
                                        final java.util.function.IntConsumer onDone) {
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Creando ZIP")
                .setMessage("Preparando…")
                .setCancelable(false)
                .create();
        progress.show();
        final Handler ui = new Handler(Looper.getMainLooper());
        final String base = zipBaseName(a, folder);
        new Thread(() -> {
            int count = -1;
            try (OutputStream out = opener.open()) {
                if (out == null) throw new java.io.IOException("sin salida");
                count = writeZip(folder, base, out, (d, t) ->
                        ui.post(() -> progress.setMessage("Foto " + d + " de " + t)));
            } catch (Exception e) {
                count = -1;
            }
            final int result = count;
            ui.post(() -> {
                progress.dismiss();
                if (a.isFinishing()) return;
                if (result < 0) {
                    Toast.makeText(a, "No se pudo crear el ZIP. Revisa que haya espacio libre.", Toast.LENGTH_LONG).show();
                } else {
                    onDone.accept(result);
                }
            });
        }).start();
    }

    static final int REQ_SAVE_ZIP = 2;

    /** Abre el selector del sistema para elegir dónde guardar el ZIP (Descargas, Drive, etc.). */
    static boolean startSaveZip(Activity a, File folder) {
        if (isEmptyFolder(folder)) {
            Toast.makeText(a, "La carpeta está vacía", Toast.LENGTH_SHORT).show();
            return false;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, zipBaseName(a, folder) + ".zip");
        try {
            a.startActivityForResult(i, REQ_SAVE_ZIP);
            return true;
        } catch (Exception e) {
            Toast.makeText(a, "Este teléfono no permite elegir dónde guardar. Usa «Enviar como ZIP».", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Llamar con el destino que eligió el usuario en el selector del sistema. */
    static void saveZipTo(final Activity a, File folder, final Uri dest) {
        zipWithProgress(a, folder, () -> a.getContentResolver().openOutputStream(dest, "w"), n ->
                new AlertDialog.Builder(a)
                        .setTitle("ZIP guardado")
                        .setMessage("Se guardó «" + zipBaseName(a, folder) + ".zip» con "
                                + Storage.plural(n, "foto", "fotos")
                                + " y todas sus carpetas, en la ubicación que elegiste.")
                        .setPositiveButton("Aceptar", null)
                        .show());
    }

    /** Crea el ZIP y lo comparte (WhatsApp, correo, Drive, Bluetooth…). */
    static void shareZip(final Activity a, final File folder) {
        if (isEmptyFolder(folder)) {
            Toast.makeText(a, "La carpeta está vacía", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = PhotoProvider.zipCacheDir(a);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        final File zip = new File(dir, zipBaseName(a, folder) + ".zip");
        zipWithProgress(a, folder, () -> new java.io.FileOutputStream(zip), n -> {
            Uri uri = PhotoProvider.zipUriFor(a, zip);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(ClipData.newRawUri("zip", uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Enviar ZIP"));
            } catch (Exception e) {
                Toast.makeText(a, "No hay apps para enviar el archivo", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Excel ----------

    static final int REQ_SAVE_XLSX = 3;
    static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static String excelName(Activity a, File base) {
        return "Carpetas - " + zipBaseName(a, base) + ".xlsx";
    }

    private static void writeExcel(Activity a, File base, OutputStream out) throws java.io.IOException {
        File root = Storage.root(a);
        java.util.List<java.util.List<java.util.List<Object>>> sheets = new ArrayList<>();
        sheets.add(ExcelExport.folderTable(root, base));
        sheets.add(ExcelExport.photoTable(root, base));
        ExcelExport.writeXlsx(out, java.util.Arrays.asList("Carpetas", "Fotos y notas"), sheets);
    }

    /** Copia la tabla al portapapeles con tabulaciones: al pegar en Excel cada dato cae en su celda. */
    static void copyForExcel(Activity a, File base, boolean photos) {
        File root = Storage.root(a);
        java.util.List<java.util.List<Object>> t = photos
                ? ExcelExport.photoTable(root, base) : ExcelExport.folderTable(root, base);
        if (t.size() <= 1) {
            Toast.makeText(a, photos ? "No hay fotos para copiar" : "No hay carpetas para copiar", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(photos ? "Fotos y notas" : "Carpetas", ExcelExport.toTsv(t)));
        int rows = t.size() - 1;
        new AlertDialog.Builder(a)
                .setTitle("Lista copiada")
                .setMessage("Se copiaron " + Storage.plural(rows, photos ? "foto" : "carpeta", photos ? "fotos" : "carpetas")
                        + " con sus columnas (N°, Nivel 1, Nivel 2…).\n\n"
                        + "En Excel, haz clic en la celda donde quieres que empiece la tabla y pega (Ctrl+V, o mantener presionado › Pegar). "
                        + "Cada dato se ubicará en su propia celda.\n\n"
                        + "Si lo pegas en WhatsApp o un correo para pasarlo a la computadora, luego cópialo desde ahí y pégalo en Excel.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    static boolean startSaveExcel(Activity a, File base) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(XLSX_MIME);
        i.putExtra(Intent.EXTRA_TITLE, excelName(a, base));
        try {
            a.startActivityForResult(i, REQ_SAVE_XLSX);
            return true;
        } catch (Exception e) {
            Toast.makeText(a, "Este teléfono no permite elegir dónde guardar. Usa «Enviar archivo Excel».", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static void saveExcelTo(final Activity a, final File base, final Uri dest) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok;
            try (OutputStream out = a.getContentResolver().openOutputStream(dest, "w")) {
                if (out == null) throw new java.io.IOException("sin salida");
                writeExcel(a, base, out);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            ui.post(() -> Toast.makeText(a, done
                    ? "Excel guardado: «" + excelName(a, base) + "»"
                    : "No se pudo guardar el Excel", Toast.LENGTH_LONG).show());
        }).start();
    }

    static void shareExcel(final Activity a, final File base) {
        final Handler ui = new Handler(Looper.getMainLooper());
        File dir = PhotoProvider.zipCacheDir(a);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        final File xlsx = new File(dir, excelName(a, base));
        new Thread(() -> {
            boolean ok;
            try (OutputStream out = new java.io.FileOutputStream(xlsx)) {
                writeExcel(a, base, out);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            ui.post(() -> {
                if (!done) {
                    Toast.makeText(a, "No se pudo crear el Excel", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = PhotoProvider.zipUriFor(a, xlsx);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(XLSX_MIME);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.setClipData(ClipData.newRawUri("excel", uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    a.startActivity(Intent.createChooser(i, "Enviar Excel"));
                } catch (Exception e) {
                    Toast.makeText(a, "No hay apps para enviar el archivo", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
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
