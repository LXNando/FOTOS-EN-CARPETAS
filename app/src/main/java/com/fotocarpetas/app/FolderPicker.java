package com.fotocarpetas.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Diálogo para elegir la carpeta de destino al mover carpetas o fotos. */
final class FolderPicker {

    interface Callback {
        void onPicked(File dest);
    }

    private FolderPicker() {}

    /**
     * @param start  carpeta donde empieza a mostrar (normalmente la carpeta actual)
     * @param source carpeta donde están hoy los elementos (no se permite "mover aquí" a la misma)
     * @param moving elementos que se van a mover (las carpetas que se mueven no aparecen como destino)
     */
    static void show(final Activity a, String title, File start, final File source,
                     List<File> moving, final Callback cb) {
        final File root = Storage.root(a);
        final Set<String> hidden = new HashSet<>();
        for (File f : moving) if (f.isDirectory()) hidden.add(f.getAbsolutePath());

        final File[] dir = {start != null && start.isDirectory() && Storage.isInside(root, start) ? start : root};
        final List<File> rows = new ArrayList<>();   // null = "subir un nivel"

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = MainActivity.dp(a, 20);

        final TextView path = new TextView(a);
        path.setTextSize(15);
        path.setTypeface(Typeface.DEFAULT_BOLD);
        path.setTextColor(MainActivity.PRIMARY);
        path.setPadding(p, MainActivity.dp(a, 6), p, MainActivity.dp(a, 2));
        box.addView(path);

        final TextView hint = new TextView(a);
        hint.setTextSize(13);
        hint.setTextColor(0xFF7A8790);
        hint.setPadding(p, 0, p, MainActivity.dp(a, 8));
        box.addView(hint);

        final ListView list = new ListView(a);
        list.setDivider(null);
        box.addView(list, new LinearLayout.LayoutParams(-1, MainActivity.dp(a, 300)));

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Mover aquí", null)
                .setNeutralButton("Nueva carpeta", null)
                .setNegativeButton("Cancelar", null)
                .create();

        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int i) {
                return rows.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                TextView tv = convert instanceof TextView ? (TextView) convert : new TextView(a);
                tv.setTextSize(16);
                tv.setPadding(p, MainActivity.dp(a, 14), p, MainActivity.dp(a, 14));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                File f = rows.get(i);
                if (f == null) {
                    File up = dir[0].getParentFile();
                    tv.setText("⬆   Subir a «" + name(root, up) + "»");
                    tv.setTextColor(0xFF4A5961);
                } else {
                    int[] c = Storage.quickCount(f);
                    tv.setText("📁   " + f.getName() + (c[0] > 0 ? "   ›" : ""));
                    tv.setTextColor(0xFF1F2A30);
                }
                return tv;
            }
        };
        list.setAdapter(adapter);

        final Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            rows.clear();
            if (!Storage.sameFile(dir[0], root)) rows.add(null);
            for (File f : Storage.listDirs(dir[0])) {
                if (!hidden.contains(f.getAbsolutePath())) rows.add(f);
            }
            adapter.notifyDataSetChanged();
            list.setSelection(0);
            String rel = Storage.relPath(root, dir[0]);
            path.setText("📂  Mis carpetas" + (rel.isEmpty() ? "" : " / " + rel.replace("/", " / ")));
            boolean here = Storage.sameFile(dir[0], source);
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setEnabled(!here);
                ok.setAlpha(here ? 0.4f : 1f);
            }
            boolean noSubfolders = rows.isEmpty() || (rows.size() == 1 && rows.get(0) == null);
            hint.setText(here
                    ? "Ya está en esta carpeta. Toca una carpeta para entrar en ella."
                    : noSubfolders ? "Toca «Mover aquí» para moverlo a esta carpeta."
                    : "Toca una carpeta para entrar, o «Mover aquí» para dejarlo en esta.");
        };

        list.setOnItemClickListener((parent, view, pos, id) -> {
            File f = rows.get(pos);
            File next = f == null ? dir[0].getParentFile() : f;
            if (next == null || !Storage.isInside(root, next)) next = root;
            dir[0] = next;
            reload[0].run();
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!dir[0].isDirectory()) {
                    Toast.makeText(a, "La carpeta ya no existe", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                cb.onPicked(dir[0]);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    Actions.askName(a, "Nueva carpeta en «" + name(root, dir[0]) + "»", "", n -> {
                        File nd = new File(dir[0], n);
                        if (nd.exists()) {
                            Toast.makeText(a, "Ya existe una carpeta con ese nombre", Toast.LENGTH_SHORT).show();
                        } else if (nd.mkdirs()) {
                            dir[0] = nd;
                            reload[0].run();
                        } else {
                            Toast.makeText(a, "No se pudo crear la carpeta", Toast.LENGTH_SHORT).show();
                        }
                    }));
            reload[0].run();
        });
        dialog.show();
    }

    static String name(File root, File dir) {
        return dir == null || Storage.sameFile(dir, root) ? "Mis carpetas" : dir.getName();
    }
}
