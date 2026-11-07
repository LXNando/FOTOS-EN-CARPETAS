package com.fotocarpetas.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Arma las tablas de carpetas y de fotos/notas, y las escribe como Excel (.xlsx) o como texto
 * separado por tabulaciones (para pegar directo en Excel). Sin librerías externas.
 */
final class ExcelExport {

    private ExcelExport() {}

    /** Todas las carpetas dentro de base (incluida base si no es la raíz), en orden de árbol. */
    static List<File> folders(File root, File base) {
        List<File> out = new ArrayList<>();
        if (!Storage.sameFile(root, base)) out.add(base);
        collect(base, out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        for (File d : Storage.listDirs(dir)) {
            out.add(d);
            collect(d, out);
        }
    }

    private static String[] levels(File root, File dir) {
        String rel = Storage.relPath(root, dir);
        return rel.isEmpty() ? new String[0] : rel.split("/");
    }

    private static int maxDepth(File root, List<File> dirs) {
        int m = 1;
        for (File d : dirs) m = Math.max(m, levels(root, d).length);
        return m;
    }

    /** Hoja «Carpetas»: N° | Nivel 1 … Nivel N | Ruta completa | Fotos | Subcarpetas */
    static List<List<Object>> folderTable(File root, File base) {
        List<File> dirs = folders(root, base);
        int depth = maxDepth(root, dirs);
        List<List<Object>> t = new ArrayList<>();
        List<Object> h = new ArrayList<>();
        h.add("N°");
        for (int i = 1; i <= depth; i++) h.add("Nivel " + i);
        h.add("Ruta completa");
        h.add("Fotos");
        h.add("Subcarpetas");
        t.add(h);
        int n = 1;
        for (File d : dirs) {
            String[] lv = levels(root, d);
            List<Object> r = new ArrayList<>();
            r.add(n++);
            for (int i = 0; i < depth; i++) r.add(i < lv.length ? lv[i] : "");
            r.add(String.join(" / ", lv));
            int[] c = Storage.quickCount(d);
            r.add(c[1]);
            r.add(c[0]);
            t.add(r);
        }
        return t;
    }

    /** Hoja «Fotos y notas»: N° | Nivel 1 … Nivel N | Foto | Fecha | Nota */
    static List<List<Object>> photoTable(File root, File base) {
        List<File> dirs = new ArrayList<>();
        dirs.add(base);
        collect(base, dirs);
        int depth = maxDepth(root, dirs);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
        List<List<Object>> t = new ArrayList<>();
        List<Object> h = new ArrayList<>();
        h.add("N°");
        for (int i = 1; i <= depth; i++) h.add("Nivel " + i);
        h.add("Foto");
        h.add("Fecha");
        h.add("Nota");
        t.add(h);
        int n = 1;
        for (File d : dirs) {
            String[] lv = levels(root, d);
            List<File> photos = Storage.listPhotos(d);
            photos.sort((a, b) -> {
                int c = Long.compare(a.lastModified(), b.lastModified());
                return c != 0 ? c : a.getName().compareToIgnoreCase(b.getName());
            });
            for (File p : photos) {
                List<Object> r = new ArrayList<>();
                r.add(n++);
                for (int i = 0; i < depth; i++) r.add(i < lv.length ? lv[i] : "");
                r.add(p.getName());
                r.add(fmt.format(new Date(p.lastModified())));
                r.add(Storage.readNote(p));
                t.add(r);
            }
        }
        return t;
    }

    // ---------- Texto para pegar en Excel ----------

    /** Columnas separadas por tabulación y filas por salto de línea: Excel lo reparte en celdas al pegar. */
    static String toTsv(List<List<Object>> table) {
        StringBuilder sb = new StringBuilder();
        for (List<Object> row : table) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append('\t');
                sb.append(String.valueOf(row.get(i)).replace("\t", " ")
                        .replace("\r\n", " / ").replace("\n", " / ").replace("\r", " / "));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    // ---------- Archivo .xlsx ----------

    static void writeXlsx(OutputStream raw, List<String> names, List<List<List<Object>>> sheets) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(raw));
        int n = sheets.size();

        StringBuilder ct = new StringBuilder(head())
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= n; i++) {
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        ct.append("</Types>");
        put(z, "[Content_Types].xml", ct.toString());

        put(z, "_rels/.rels", head()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");

        StringBuilder wb = new StringBuilder(head())
                .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < n; i++) {
            wb.append("<sheet name=\"").append(esc(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        wb.append("</sheets><definedNames>");
        for (int i = 0; i < n; i++) {
            List<List<Object>> t = sheets.get(i);
            wb.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                    .append("\" hidden=\"1\">'").append(esc(names.get(i).replace("'", "''"))).append("'!$A$1:$")
                    .append(col(width(t) - 1)).append("$").append(Math.max(1, t.size())).append("</definedName>");
        }
        wb.append("</definedNames></workbook>");
        put(z, "xl/workbook.xml", wb.toString());

        StringBuilder rels = new StringBuilder(head())
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= n; i++) {
            rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(n + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                .append("</Relationships>");
        put(z, "xl/_rels/workbook.xml.rels", rels.toString());

        put(z, "xl/styles.xml", head()
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "</fonts>"
                + "<fills count=\"3\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1E6F5C\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "</fills>"
                + "<borders count=\"2\">"
                + "<border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFC9D2D8\"/></left><right style=\"thin\"><color rgb=\"FFC9D2D8\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFC9D2D8\"/></top><bottom style=\"thin\"><color rgb=\"FFC9D2D8\"/></bottom><diagonal/></border>"
                + "</borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"4\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");

        for (int i = 0; i < n; i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        z.finish();
        z.flush();
    }

    private static String sheetXml(List<List<Object>> t) {
        int w = width(t);
        StringBuilder sb = new StringBuilder(head())
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int c = 0; c < w; c++) {
            int len = 4;
            for (List<Object> row : t) {
                if (c < row.size()) {
                    String s = String.valueOf(row.get(c));
                    int nl = s.indexOf('\n');
                    len = Math.max(len, nl >= 0 ? nl : s.length());
                }
            }
            double width = Math.min(60, Math.max(6, len + 2));
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(width).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        for (int r = 0; r < t.size(); r++) {
            List<Object> row = t.get(r);
            sb.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                String ref = col(c) + (r + 1);
                if (r > 0 && v instanceof Number) {
                    sb.append("<c r=\"").append(ref).append("\" s=\"2\"><v>").append(v).append("</v></c>");
                } else {
                    String s = String.valueOf(v);
                    int style = r == 0 ? 1 : (s.length() > 55 || s.indexOf('\n') >= 0) ? 3 : 2;
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"").append(style)
                            .append("\"><is><t xml:space=\"preserve\">").append(esc(s)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A1:").append(col(w - 1)).append(Math.max(1, t.size())).append("\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static int width(List<List<Object>> t) {
        int w = 1;
        for (List<Object> row : t) w = Math.max(w, row.size());
        return w;
    }

    /** 0 → A, 25 → Z, 26 → AA … */
    static String col(int i) {
        StringBuilder s = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            s.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return s.toString();
    }

    private static String head() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20) {
                        if (ch == '\r') break;  // Excel usa solo \n dentro de una celda
                        if (ch >= 0xFFFE) break;
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
