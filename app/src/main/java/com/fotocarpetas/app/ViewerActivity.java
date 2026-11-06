package com.fotocarpetas.app;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visor de fotos a pantalla completa: deslizar para cambiar, pellizcar o doble toque para zoom. */
public class ViewerActivity extends Activity implements ZoomImageView.Listener {

    static final String EXTRA_FOLDER = "carpeta";
    static final String EXTRA_FILE = "archivo";

    private static final int M_SHARE = 1, M_DELETE = 2, M_INFO = 3, M_NOTE = 4;

    private List<File> photos;
    private int index;
    private File folder;
    private ZoomImageView image;
    private ProgressBar spinner;
    private android.widget.TextView noteView;
    private boolean chromeVisible = true;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int token;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String folderPath = getIntent().getStringExtra(EXTRA_FOLDER);
        String name = state != null ? state.getString(EXTRA_FILE) : null;
        if (name == null) name = getIntent().getStringExtra(EXTRA_FILE);
        if (folderPath == null) { finish(); return; }
        folder = new File(folderPath);
        if (!Storage.isInside(Storage.root(this), folder)) { finish(); return; }
        photos = Storage.listPhotos(folder);
        if (photos.isEmpty()) { finish(); return; }
        index = 0;
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).getName().equals(name)) { index = i; break; }
        }

        FrameLayout box = new FrameLayout(this);
        image = new ZoomImageView(this);
        image.setListener(this);
        box.addView(image, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        box.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        noteView = new android.widget.TextView(this);
        noteView.setTextColor(0xFFFFFFFF);
        noteView.setTextSize(15);
        noteView.setLineSpacing(0, 1.15f);
        noteView.setMaxLines(6);
        noteView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        noteView.setBackgroundColor(0xB3000000);
        int pad = MainActivity.dp(this, 14);
        noteView.setPadding(pad, pad, pad, pad);
        noteView.setOnClickListener(v -> editNote());
        box.addView(noteView, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(box);

        ActionBar ab = getActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(true);
        show(0);
    }

    private void show(int direction) {
        final File f = photos.get(index);
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle((index + 1) + " de " + photos.size());
            ab.setSubtitle(folder.equals(Storage.root(this)) ? "Mis carpetas" : folder.getName());
        }
        updateNote();
        final int my = ++token;
        spinner.setVisibility(android.view.View.VISIBLE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int req = Math.max(dm.widthPixels, dm.heightPixels);
        worker.execute(() -> {
            Bitmap b = null;
            try {
                b = ImageLoader.decodeSampled(f, req, req);
            } catch (OutOfMemoryError e) {
                try { b = ImageLoader.decodeSampled(f, req / 2, req / 2); } catch (Throwable ignored) { }
            } catch (Throwable ignored) {
            }
            final Bitmap result = b;
            main.post(() -> {
                if (my != token || isFinishing()) return;
                spinner.setVisibility(android.view.View.GONE);
                if (result == null) {
                    image.setImageBitmap(null);
                    Toast.makeText(this, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(result);
                if (direction != 0) {
                    image.setTranslationX(direction * image.getWidth() * 0.25f);
                    image.setAlpha(0f);
                    image.animate().translationX(0).alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    private void updateNote() {
        String note = Storage.readNote(photos.get(index));
        noteView.setText("📝  " + note);
        noteView.setVisibility(!note.isEmpty() && chromeVisible ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void editNote() {
        File f = photos.get(index);
        Actions.editNote(this, f, () -> {
            if (photos.get(index).equals(f)) updateNote();
            Toast.makeText(this, Storage.hasNote(f) ? "Nota guardada" : "Nota borrada", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onSwipe(int direction) {
        int next = index + direction;
        if (next < 0 || next >= photos.size()) {
            Toast.makeText(this, direction > 0 ? "Es la última foto" : "Es la primera foto", Toast.LENGTH_SHORT).show();
            return;
        }
        index = next;
        show(direction);
    }

    @Override
    public void onTap() {
        ActionBar ab = getActionBar();
        if (ab == null) return;
        chromeVisible = !ab.isShowing();
        if (chromeVisible) ab.show();
        else ab.hide();
        updateNote();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_NOTE, 0, "📝 Nota").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, M_SHARE, 0, "Compartir").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_DELETE, 1, "Eliminar").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_INFO, 2, "Información");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (photos == null || photos.isEmpty()) return super.onOptionsItemSelected(item);
        final File f = photos.get(index);
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case M_NOTE:
                editNote();
                return true;
            case M_SHARE:
                Actions.share(this, Collections.singletonList(f));
                return true;
            case M_DELETE:
                Actions.confirm(this, "¿Eliminar esta foto?",
                        (Storage.hasNote(f) ? "También se borrará su nota. " : "") + "Esta acción no se puede deshacer.", "Eliminar", () -> {
                    if (!Storage.deletePhoto(f)) {
                        Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    photos.remove(index);
                    if (photos.isEmpty()) {
                        finish();
                        return;
                    }
                    if (index >= photos.size()) index = photos.size() - 1;
                    show(0);
                });
                return true;
            case M_INFO:
                int[] wh = ImageLoader.dimensions(f);
                String size = String.format(Locale.getDefault(), "%.1f MB", f.length() / 1048576.0);
                String date = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(new Date(f.lastModified()));
                String where = "Mis carpetas" + (folder.equals(Storage.root(this)) ? ""
                        : " / " + Storage.relPath(Storage.root(this), folder).replace("/", " / "));
                new AlertDialog.Builder(this)
                        .setTitle(f.getName())
                        .setMessage("Fecha: " + date
                                + "\nTamaño: " + wh[0] + " × " + wh[1] + " px (" + size + ")"
                                + "\nCarpeta: " + where)
                        .setPositiveButton("Cerrar", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (photos != null && !photos.isEmpty()) out.putString(EXTRA_FILE, photos.get(index).getName());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
