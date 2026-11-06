package com.fotocarpetas.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Utilidades para manejar las carpetas y fotos guardadas en el teléfono (sin internet). */
final class Storage {

    static final String ROOT_NAME = "FotoCarpetas";

    private Storage() {}

    /** Carpeta raíz donde la app guarda todo. */
    static File root(Context ctx) {
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) base = new File(ctx.getFilesDir(), "Pictures");
        File root = new File(base, ROOT_NAME);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    static boolean isInside(File root, File f) {
        try {
            String r = root.getCanonicalPath();
            String c = f.getCanonicalPath();
            return c.equals(r) || c.startsWith(r + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** Ruta relativa de f respecto a root ("" si es la raíz), usando "/" como separador. */
    static String relPath(File root, File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) return "";
        if (p.startsWith(r + File.separator)) return p.substring(r.length() + 1).replace(File.separatorChar, '/');
        return f.getName();
    }

    static boolean isPhoto(File f) {
        if (!f.isFile()) return false;
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    static List<File> listDirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** Fotos de la carpeta, de la más reciente a la más antigua. */
    static List<File> listPhotos(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) if (isPhoto(f) && f.length() > 0) out.add(f);
        out.sort((a, b) -> {
            int c = Long.compare(b.lastModified(), a.lastModified());
            return c != 0 ? c : b.getName().compareToIgnoreCase(a.getName());
        });
        return out;
    }

    /** {subcarpetas, fotos} directamente dentro de dir. */
    static int[] quickCount(File dir) {
        int[] c = new int[2];
        File[] fs = dir.listFiles();
        if (fs == null) return c;
        for (File f : fs) {
            if (f.isDirectory()) { if (!f.getName().startsWith(".")) c[0]++; }
            else if (isPhoto(f)) c[1]++;
        }
        return c;
    }

    /** {subcarpetas, fotos} en todo el árbol bajo dir. */
    static int[] deepCount(File dir) {
        int[] c = new int[2];
        deepCount(dir, c);
        return c;
    }

    private static void deepCount(File dir, int[] c) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { c[0]++; deepCount(f, c); }
            else if (isPhoto(f)) c[1]++;
        }
    }

    static void collectPhotos(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            if (f.isDirectory()) collectPhotos(f, out);
            else if (isPhoto(f) && f.length() > 0) out.add(f);
        }
    }

    static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        return f.delete();
    }

    /** Devuelve un mensaje de error, o null si el nombre es válido. */
    static String validateName(String s) {
        if (s.isEmpty()) return "Escribe un nombre";
        if (s.length() > 80) return "El nombre es demasiado largo";
        if (s.equals(".") || s.equals("..") || s.startsWith(".")) return "El nombre no puede empezar con un punto";
        for (char ch : s.toCharArray()) {
            if ("/\\:*?\"<>|".indexOf(ch) >= 0 || ch < 32) {
                return "No uses estos caracteres:  / \\ : * ? \" < > |";
            }
        }
        return null;
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
