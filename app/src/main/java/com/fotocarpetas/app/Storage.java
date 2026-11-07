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

    // ---------- Notas ----------

    /** La nota de una foto es un .txt con el mismo nombre, en la misma carpeta (IMG_1.jpg → IMG_1.txt). */
    static File noteFile(File photo) {
        String n = photo.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return new File(photo.getParentFile(), base + ".txt");
    }

    static boolean hasNote(File photo) {
        File n = noteFile(photo);
        return n.isFile() && n.length() > 0;
    }

    /** Texto de la nota, o "" si no tiene. */
    static String readNote(File photo) {
        File n = noteFile(photo);
        if (!n.isFile()) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(n)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            String s = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            return s.trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Guarda la nota; si el texto está vacío, la borra. */
    static boolean writeNote(File photo, String text) {
        File n = noteFile(photo);
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return !n.exists() || n.delete();
        try (java.io.OutputStream out = new java.io.FileOutputStream(n)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** {fotos con nota directamente en dir, fotos con nota en dir y todas sus subcarpetas}. */
    static int[] noteCount(File dir) {
        int[] c = new int[2];
        noteCount(dir, c, true);
        return c;
    }

    private static void noteCount(File dir, int[] c, boolean top) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) noteCount(f, c, false);
            } else if (isPhoto(f) && hasNote(f)) {
                c[1]++;
                if (top) c[0]++;
            }
        }
    }

    /** Borra una foto junto con su nota. */
    static boolean deletePhoto(File photo) {
        File n = noteFile(photo);
        boolean ok = photo.delete() || !photo.exists();
        if (ok && n.exists()) n.delete();
        return ok;
    }

    // ---------- Mover ----------

    static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** Un nombre libre en destDir: si «Nombre» ya existe, prueba «Nombre (2)», «Nombre (3)»… */
    static File uniqueTarget(File destDir, String name, boolean isDir) {
        String base = name, ext = "";
        if (!isDir) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
        }
        File t = new File(destDir, name);
        int n = 2;
        while (t.exists() || (!isDir && noteFile(t).exists())) {
            t = new File(destDir, base + " (" + (n++) + ")" + ext);
        }
        return t;
    }

    /**
     * Mueve una carpeta (con todo su contenido) o una foto (con su nota) dentro de destDir.
     * Devuelve el archivo en su nueva ubicación, o null si no se pudo.
     */
    static File moveInto(File item, File destDir) {
        if (!destDir.isDirectory() || !item.exists()) return null;
        if (sameFile(item.getParentFile(), destDir)) return item;
        boolean dir = item.isDirectory();
        // Una carpeta no puede ir dentro de sí misma ni de sus subcarpetas.
        if (dir && isInside(item, destDir)) return null;
        File target = uniqueTarget(destDir, item.getName(), dir);
        File note = dir ? null : noteFile(item);
        boolean hadNote = note != null && note.isFile();
        if (!item.renameTo(target)) return null;
        if (hadNote) note.renameTo(noteFile(target));
        return target;
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
