package com.fotocarpetas.app;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Pantalla principal: navega por carpetas, crea subcarpetas y toma fotos dentro de la carpeta abierta. */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final int M_CONTINUOUS = 1, M_EXPORT = 2, M_INFO = 3, M_NEW_FOLDER = 4, M_ZIP_SAVE = 5, M_ZIP_SEND = 6;
    private static final String PREFS = "ajustes", KEY_CONTINUOUS = "seguir_tomando";

    static final int PRIMARY = 0xFF1E6F5C;

    private File root;
    private File current;
    private File pendingPhoto;
    private File pendingZipFolder;
    private final List<File> items = new ArrayList<>();
    private int dirCount;
    private GridView grid;
    private TextView emptyView;
    private Adapter adapter;
    private int thumbSize;

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private int dp(float v) {
        return dp(this, v);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = Storage.root(this);
        current = root;
        if (state != null) {
            String c = state.getString("current");
            if (c != null) {
                File f = new File(c);
                if (f.isDirectory() && Storage.isInside(root, f)) current = f;
            }
            String p = state.getString("pending");
            if (p != null) pendingPhoto = new File(p);
            String z = state.getString("pendingZip");
            if (z != null) pendingZipFolder = new File(z);
        }
        thumbSize = dp(150);
        buildUi();
    }

    // ---------- Interfaz ----------

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        FrameLayout content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(104));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        grid.setClipToPadding(false);
        grid.setDrawSelectorOnTop(true);
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(0xFF6B7780);
        emptyView.setTextSize(16);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyView.setLineSpacing(0, 1.25f);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        grid.setEmptyView(emptyView);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(10));
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        Button newFolder = makeButton("📁  Nueva carpeta", false);
        Button photo = makeButton("📷  Tomar foto", true);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp1.rightMargin = dp(5);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(56), 1.25f);
        lp2.leftMargin = dp(5);
        bar.addView(newFolder, lp1);
        bar.addView(photo, lp2);
        page.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        newFolder.setOnClickListener(v -> createFolder());
        photo.setOnClickListener(v -> takePhoto());

        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> open(items.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            showOptions(items.get(pos));
            return true;
        });

        setContentView(page);
    }

    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? Color.WHITE : PRIMARY);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (primary) {
            bg.setColor(PRIMARY);
        } else {
            bg.setColor(0xFFE6F1EE);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        while (!current.isDirectory() && !current.equals(root)) {
            File p = current.getParentFile();
            if (p == null || !Storage.isInside(root, p)) { current = root; break; }
            current = p;
        }
        if (!root.exists()) root.mkdirs();
        items.clear();
        List<File> dirs = Storage.listDirs(current);
        dirCount = dirs.size();
        items.addAll(dirs);
        items.addAll(Storage.listPhotos(current));
        adapter.notifyDataSetChanged();

        boolean atRoot = current.equals(root);
        emptyView.setText(atRoot
                ? "Aún no tienes carpetas.\n\nToca «Nueva carpeta» para crear la primera, o «Tomar foto» para guardar una foto aquí."
                : "Esta carpeta está vacía.\n\nToca «Tomar foto» para guardar fotos aquí, o crea una subcarpeta.");
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle(atRoot ? "Mis carpetas" : current.getName());
            ab.setSubtitle(atRoot ? null : "Mis carpetas / " + Storage.relPath(root, current).replace("/", " / "));
            ab.setDisplayHomeAsUpEnabled(!atRoot);
        }
        invalidateOptionsMenu();
    }

    private void navigate(File dir) {
        current = dir;
        refresh();
        grid.setSelection(0);
    }

    private void goUp() {
        File p = current.getParentFile();
        if (p != null && Storage.isInside(root, p)) navigate(p);
        else navigate(root);
    }

    @Override
    public void onBackPressed() {
        if (!current.equals(root)) goUp();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("current", current.getAbsolutePath());
        if (pendingPhoto != null) out.putString("pending", pendingPhoto.getAbsolutePath());
        if (pendingZipFolder != null) out.putString("pendingZip", pendingZipFolder.getAbsolutePath());
    }

    // ---------- Menú ----------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_NEW_FOLDER, 0, "Nueva carpeta");
        menu.add(0, M_CONTINUOUS, 1, "Seguir tomando fotos")
                .setCheckable(true)
                .setChecked(prefs().getBoolean(KEY_CONTINUOUS, false));
        boolean atRoot = current.equals(root);
        menu.add(0, M_ZIP_SAVE, 2, atRoot ? "Descargar todo como ZIP" : "Descargar esta carpeta como ZIP");
        menu.add(0, M_ZIP_SEND, 2, atRoot ? "Enviar todo como ZIP" : "Enviar esta carpeta como ZIP");
        if (Actions.canExportToGallery()) {
            menu.add(0, M_EXPORT, 2, current.equals(root) ? "Copiar todo a la Galería" : "Copiar esta carpeta a la Galería");
        }
        menu.add(0, M_INFO, 3, "¿Dónde se guardan las fotos?");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                goUp();
                return true;
            case M_NEW_FOLDER:
                createFolder();
                return true;
            case M_CONTINUOUS:
                boolean on = !item.isChecked();
                item.setChecked(on);
                prefs().edit().putBoolean(KEY_CONTINUOUS, on).apply();
                Toast.makeText(this, on
                        ? "Activado: al guardar una foto se abrirá la cámara otra vez. Pulsa atrás en la cámara para terminar."
                        : "Desactivado: se toma una foto a la vez.", Toast.LENGTH_LONG).show();
                return true;
            case M_EXPORT:
                Actions.exportToGallery(this, current);
                return true;
            case M_ZIP_SAVE:
                saveZip(current);
                return true;
            case M_ZIP_SEND:
                Actions.shareZip(this, current);
                return true;
            case M_INFO:
                showStorageInfo();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showStorageInfo() {
        new AlertDialog.Builder(this)
                .setTitle("¿Dónde se guardan las fotos?")
                .setMessage("Todo se guarda dentro del teléfono; la app no usa internet.\n\n"
                        + "Ubicación:\n" + root.getAbsolutePath() + "\n\n"
                        + "Desde una computadora (cable USB) la encuentras en:\nAndroid › data › " + getPackageName()
                        + " › files › Pictures › " + Storage.ROOT_NAME + "\n\n"
                        + "Importante: si desinstalas la app, Android borra estas fotos. "
                        + "Antes de desinstalar, usa «Copiar a la Galería» o «Compartir» para guardar una copia.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    // ---------- Carpetas ----------

    private void createFolder() {
        Actions.askName(this, "Nueva carpeta en «" + displayName(current) + "»", "", name -> {
            File d = new File(current, name);
            if (d.exists()) {
                Toast.makeText(this, "Ya existe una carpeta con ese nombre", Toast.LENGTH_SHORT).show();
            } else if (d.mkdirs()) {
                refresh();
                Toast.makeText(this, "Carpeta «" + name + "» creada", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No se pudo crear la carpeta", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String displayName(File dir) {
        return dir.equals(root) ? "Mis carpetas" : dir.getName();
    }

    private void open(File f) {
        if (f.isDirectory()) {
            navigate(f);
        } else {
            Intent i = new Intent(this, ViewerActivity.class);
            i.putExtra(ViewerActivity.EXTRA_FOLDER, f.getParent());
            i.putExtra(ViewerActivity.EXTRA_FILE, f.getName());
            startActivity(i);
        }
    }

    private void showOptions(final File f) {
        final boolean dir = f.isDirectory();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(dir ? "Abrir" : "Ver");
        actions.add(() -> open(f));
        if (dir) {
            labels.add("Tomar foto aquí");
            actions.add(() -> { current = f; refresh(); takePhoto(); });
        }
        if (!dir) {
            labels.add(Storage.hasNote(f) ? "📝 Editar nota" : "📝 Agregar nota");
            actions.add(() -> Actions.editNote(this, f, this::refresh));
        }
        labels.add("Renombrar");
        actions.add(() -> rename(f));
        labels.add(dir ? "Compartir fotos" : "Compartir");
        actions.add(() -> Actions.share(this, dir ? Storage.listPhotos(f) : Collections.singletonList(f)));
        if (dir) {
            labels.add("Descargar como ZIP");
            actions.add(() -> saveZip(f));
            labels.add("Enviar como ZIP");
            actions.add(() -> Actions.shareZip(this, f));
        }
        if (dir && Actions.canExportToGallery()) {
            labels.add("Copiar a la Galería");
            actions.add(() -> Actions.exportToGallery(this, f));
        }
        labels.add("Eliminar");
        actions.add(() -> delete(f));

        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void saveZip(File folder) {
        if (Actions.startSaveZip(this, folder)) pendingZipFolder = folder;
    }

    private void rename(final File f) {
        final boolean dir = f.isDirectory();
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        final String ext = (!dir && dot > 0) ? name.substring(dot) : "";
        String initial = dir || dot <= 0 ? name : name.substring(0, dot);
        Actions.askName(this, dir ? "Renombrar carpeta" : "Renombrar foto", initial, newName -> {
            File target = new File(f.getParentFile(), newName + ext);
            if (target.equals(f)) return;
            if (target.exists()) {
                Toast.makeText(this, "Ya existe un elemento con ese nombre", Toast.LENGTH_SHORT).show();
            } else if (!dir && Storage.noteFile(target).exists() && Storage.hasNote(f)) {
                Toast.makeText(this, "Ya existe una nota con ese nombre", Toast.LENGTH_SHORT).show();
            } else if (moveWithNote(f, target, dir)) {
                refresh();
            } else {
                Toast.makeText(this, "No se pudo renombrar", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean moveWithNote(File f, File target, boolean dir) {
        File oldNote = dir ? null : Storage.noteFile(f);
        if (!f.renameTo(target)) return false;
        if (oldNote != null && oldNote.exists()) oldNote.renameTo(Storage.noteFile(target));
        return true;
    }

    private void delete(final File f) {
        if (f.isDirectory()) {
            int[] c = Storage.deepCount(f);
            String detail = (c[0] + c[1] == 0) ? "La carpeta está vacía."
                    : "Se borrará también todo su contenido: "
                    + Storage.plural(c[1], "foto", "fotos") + " y " + Storage.plural(c[0], "subcarpeta", "subcarpetas") + ".";
            Actions.confirm(this, "¿Eliminar «" + f.getName() + "»?",
                    detail + "\n\nEsta acción no se puede deshacer.", "Eliminar", () -> {
                        Storage.deleteRecursive(f);
                        refresh();
                    });
        } else {
            Actions.confirm(this, "¿Eliminar esta foto?",
                    (Storage.hasNote(f) ? "También se borrará su nota. " : "") + "Esta acción no se puede deshacer.", "Eliminar", () -> {
                Storage.deletePhoto(f);
                refresh();
            });
        }
    }

    // ---------- Cámara ----------

    private void takePhoto() {
        if (!current.exists() && !current.mkdirs()) {
            Toast.makeText(this, "No se pudo acceder a la carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(current, base + ".jpg");
        int n = 2;
        while (f.exists()) f = new File(current, base + "_" + (n++) + ".jpg");

        Uri uri = PhotoProvider.uriFor(this, f);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.setClipData(ClipData.newRawUri("foto", uri));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingPhoto = f;
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException | SecurityException e) {
            pendingPhoto = null;
            Toast.makeText(this, "No se encontró una app de cámara en el teléfono", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Actions.REQ_SAVE_ZIP) {
            File folder = pendingZipFolder;
            pendingZipFolder = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && folder != null && folder.isDirectory()) {
                Actions.saveZipTo(this, folder, data.getData());
            }
            return;
        }
        if (requestCode != REQ_CAMERA) return;
        File f = pendingPhoto;
        pendingPhoto = null;
        if (f == null) return;
        if (f.exists() && f.length() > 0) {
            File parent = f.getParentFile();
            if (parent != null && parent.isDirectory() && Storage.isInside(root, parent)) current = parent;
            refresh();
            Toast.makeText(this, "Foto guardada en «" + displayName(current) + "»", Toast.LENGTH_SHORT).show();
            if (resultCode == RESULT_OK && prefs().getBoolean(KEY_CONTINUOUS, false)) {
                grid.postDelayed(this::takePhoto, 350);
            }
        } else if (f.exists()) {
            f.delete();
        }
    }

    // ---------- Cuadrícula ----------

    /** Contenedor cuadrado para las celdas de la cuadrícula. */
    static class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }

    private static class FolderHolder {
        TextView name, meta;
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position < dirCount ? 0 : 1;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            File f = items.get(position);
            if (getItemViewType(position) == 0) {
                if (convert == null) convert = newFolderCell();
                FolderHolder h = (FolderHolder) convert.getTag();
                h.name.setText(f.getName());
                int[] c = Storage.quickCount(f);
                String meta;
                if (c[0] == 0 && c[1] == 0) meta = "Vacía";
                else if (c[0] == 0) meta = Storage.plural(c[1], "foto", "fotos");
                else if (c[1] == 0) meta = Storage.plural(c[0], "carpeta", "carpetas");
                else meta = c[1] + " fotos · " + c[0] + " carp.";
                h.meta.setText(meta);
            } else {
                if (convert == null) convert = newPhotoCell();
                ImageView iv = (ImageView) ((ViewGroup) convert).getChildAt(0);
                ImageLoader.get().loadThumb(iv, f, thumbSize);
                ((ViewGroup) convert).getChildAt(1).setVisibility(Storage.hasNote(f) ? View.VISIBLE : View.GONE);
            }
            return convert;
        }

        private View newFolderCell() {
            SquareFrame cell = new SquareFrame(MainActivity.this);
            cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1), 0xFFE1E6EA);
            cell.setBackground(bg);

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(6), dp(6), dp(6), dp(6));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageResource(R.drawable.ic_folder);
            col.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

            FolderHolder h = new FolderHolder();
            h.name = new TextView(MainActivity.this);
            h.name.setTextColor(0xFF1F2A30);
            h.name.setTextSize(14);
            h.name.setTypeface(Typeface.DEFAULT_BOLD);
            h.name.setGravity(Gravity.CENTER);
            h.name.setMaxLines(2);
            h.name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(h.name, new LinearLayout.LayoutParams(-1, -2));

            h.meta = new TextView(MainActivity.this);
            h.meta.setTextColor(0xFF7A8790);
            h.meta.setTextSize(11.5f);
            h.meta.setGravity(Gravity.CENTER);
            h.meta.setSingleLine(true);
            h.meta.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(h.meta, new LinearLayout.LayoutParams(-1, -2));

            cell.addView(col, new FrameLayout.LayoutParams(-1, -1));
            cell.setTag(h);
            return cell;
        }

        private View newPhotoCell() {
            SquareFrame cell = new SquareFrame(MainActivity.this);
            cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            ImageView iv = new ImageView(MainActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFDDE3E7);
            bg.setCornerRadius(dp(10));
            iv.setBackground(bg);
            iv.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            iv.setClipToOutline(true);
            cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            TextView badge = new TextView(MainActivity.this);
            badge.setText("📝");
            badge.setTextSize(13);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(0xE6FFFFFF);
            badge.setBackground(bb);
            badge.setElevation(dp(2));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END);
            blp.setMargins(0, 0, dp(5), dp(5));
            cell.addView(badge, blp);
            return cell;
        }
    }
}
