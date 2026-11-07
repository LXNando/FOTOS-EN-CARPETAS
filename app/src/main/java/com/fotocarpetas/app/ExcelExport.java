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
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Arma las hojas «Carpetas» y «Fotos y notas», y las escribe como Excel (.xlsx) con formato,
 * o como texto separado por tabulaciones para pegar directo en Excel. Sin librerías externas.
 */
final class ExcelExport {

    private ExcelExport() {}

    // Tipos de celda del cuerpo de la tabla (definen el estilo).
    static final int TEXT = 0, WRAP = 1, NUM = 2, DATE = 3, GRAY = 4, BOLD = 5;
    private static final int KINDS = 6;

    static final class Cell {
        final Object v;
        final int kind;

        Cell(Object v, int kind) {
            this.v = v;
            this.kind = kind;
        }
    }

    static final class Sheet {
        String name, title, info;
        final List<String> headers = new ArrayList<>();
        /** Ancho fijo por columna; null = automático según el contenido. */
        final List<Double> widths = new ArrayList<>();
        final List<List<Cell>> rows = new ArrayList<>();
        /** Fondo alternado por grupo, para distinguir carpetas a simple vista. */
        final List<Boolean> band = new ArrayList<>();

        void col(String header, Double width) {
            headers.add(header);
            widths.add(width);
        }
    }

    private static final int HEADER_ROW = 4;   // filas 1-2: título e información; fila 3: vacía

    // ---------- Datos ----------

    static String baseLabel(File root, File base) {
        return Storage.sameFile(root, base) ? "Mis carpetas" : base.getName();
    }

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

    /** Fotos de la carpeta en orden en que se tomaron (más antigua primero). */
    private static List<File> photosInOrder(File dir) {
        List<File> photos = Storage.listPhotos(dir);
        photos.sort((a, b) -> {
            int c = Long.compare(a.lastModified(), b.lastModified());
            return c != 0 ? c : a.getName().compareToIgnoreCase(b.getName());
        });
        return photos;
    }

    private static String summary(File root, File base) {
        List<File> all = new ArrayList<>();
        all.add(base);
        collect(base, all);
        int dirs = folders(root, base).size(), photos = 0, notes = 0;
        for (File d : all) {
            for (File p : Storage.listPhotos(d)) {
                photos++;
                if (Storage.hasNote(p)) notes++;
            }
        }
        String when = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date());
        return "Exportado el " + when + "   ·   " + Storage.plural(dirs, "carpeta", "carpetas")
                + "   ·   " + Storage.plural(photos, "foto", "fotos")
                + "   ·   " + Storage.plural(notes, "foto con nota", "fotos con nota");
    }

    /** Hoja «Carpetas»: N° | Nivel 1…N | Notas de las fotos */
    static Sheet folderSheet(File root, File base) {
        List<File> dirs = folders(root, base);
        int depth = maxDepth(root, dirs);
        Sheet s = new Sheet();
        s.name = "Carpetas";
        s.title = "Registro fotográfico — Carpetas de «" + baseLabel(root, base) + "»";
        s.info = summary(root, base);
        s.col("N°", 6.0);
        for (int i = 1; i <= depth; i++) s.col("Nivel " + i, null);
        s.col("Notas de las fotos", 60.0);

        int n = 1;
        boolean band = true;
        String group = null;
        for (File d : dirs) {
            String[] lv = levels(root, d);
            String g = lv.length > 0 ? lv[0] : "";
            if (!g.equals(group)) {
                band = !band;
                group = g;
            }
            List<Cell> r = new ArrayList<>();
            r.add(new Cell(n++, NUM));
            for (int i = 0; i < depth; i++) {
                if (i < lv.length) r.add(new Cell(lv[i], i == lv.length - 1 ? BOLD : GRAY));
                else r.add(new Cell("", TEXT));
            }
            // Solo el texto de cada nota; si hay varias, una por línea.
            StringBuilder notes = new StringBuilder();
            for (File p : photosInOrder(d)) {
                String note = Storage.readNote(p);
                if (note.isEmpty()) continue;
                if (notes.length() > 0) notes.append('\n');
                notes.append(note);
            }
            r.add(new Cell(notes.toString(), WRAP));
            s.rows.add(r);
            s.band.add(band);
        }
        return s;
    }

    /** Hoja «Fotos y notas»: N° | Nivel 1…N | Foto | Fecha | Nota */
    static Sheet photoSheet(File root, File base) {
        List<File> dirs = new ArrayList<>();
        dirs.add(base);
        collect(base, dirs);
        int depth = maxDepth(root, dirs);
        Sheet s = new Sheet();
        s.name = "Fotos y notas";
        s.title = "Registro fotográfico — Fotos y notas de «" + baseLabel(root, base) + "»";
        s.info = summary(root, base);
        s.col("N°", 6.0);
        for (int i = 1; i <= depth; i++) s.col("Nivel " + i, null);
        s.col("Foto", null);
        s.col("Fecha", 17.0);
        s.col("Nota", 60.0);

        int n = 1;
        boolean band = true;
        for (File d : dirs) {
            List<File> photos = photosInOrder(d);
            if (photos.isEmpty()) continue;
            band = !band;   // cada carpeta con su propio fondo
            String[] lv = levels(root, d);
            for (File p : photos) {
                List<Cell> r = new ArrayList<>();
                r.add(new Cell(n++, NUM));
                for (int i = 0; i < depth; i++) r.add(new Cell(i < lv.length ? lv[i] : "", TEXT));
                r.add(new Cell(p.getName(), TEXT));
                r.add(new Cell(new Date(p.lastModified()), DATE));
                r.add(new Cell(Storage.readNote(p), WRAP));
                s.rows.add(r);
                s.band.add(band);
            }
        }
        return s;
    }

    // ---------- Texto para pegar en Excel ----------

    /** Encabezados y filas separados por tabulación: al pegar en Excel cada dato cae en su celda. */
    static String toTsv(Sheet s) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", s.headers)).append("\r\n");
        for (List<Cell> row : s.rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append('\t');
                Object v = row.get(i).v;
                String t = v instanceof Date ? fmt.format((Date) v) : String.valueOf(v);
                // Un salto de línea partiría la fila al pegar: se reemplaza por « | ».
                t = t.replace("\t", " ").replace("\r", "").replace("\n   ", " ").replace("\n", " | ");
                sb.append(t);
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    // ---------- Archivo .xlsx ----------

    // Índices de estilos (cellXfs) definidos en styles.xml
    private static final int XF_HEADER = 1, XF_TITLE = 2, XF_INFO = 3, XF_BODY = 4;

    private static int bodyXf(int kind, boolean band) {
        return XF_BODY + kind + (band ? KINDS : 0);
    }

    static void writeXlsx(OutputStream raw, List<Sheet> sheets) throws IOException {
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
            wb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        wb.append("</sheets><definedNames>");
        for (int i = 0; i < n; i++) {
            Sheet s = sheets.get(i);
            String q = "'" + esc(s.name.replace("'", "''")) + "'!";
            int last = HEADER_ROW + s.rows.size();
            wb.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                    .append("\" hidden=\"1\">").append(q).append("$A$").append(HEADER_ROW).append(":$")
                    .append(col(s.headers.size() - 1)).append("$").append(last).append("</definedName>");
            wb.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(i).append("\">")
                    .append(q).append("$").append(HEADER_ROW).append(":$").append(HEADER_ROW).append("</definedName>");
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

        put(z, "xl/styles.xml", stylesXml());
        for (int i = 0; i < n; i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        z.finish();
        z.flush();
    }

    private static String stylesXml() {
        String border = "borderId=\"1\" applyBorder=\"1\" applyAlignment=\"1\"";
        // Estilos del cuerpo: TEXT, WRAP, NUM, DATE, GRAY, BOLD (en ese orden), sin y con fondo alternado.
        String[][] kinds = {
                {"0", "0", "<alignment vertical=\"top\"/>"},
                {"0", "0", "<alignment vertical=\"top\" wrapText=\"1\"/>"},
                {"0", "0", "<alignment horizontal=\"center\" vertical=\"top\"/>"},
                {"0", "164", "<alignment horizontal=\"center\" vertical=\"top\"/>"},
                {"4", "0", "<alignment vertical=\"top\"/>"},
                {"5", "0", "<alignment vertical=\"top\"/>"},
        };
        StringBuilder body = new StringBuilder();
        for (int band = 0; band < 2; band++) {
            for (String[] k : kinds) {
                body.append("<xf numFmtId=\"").append(k[1]).append("\" fontId=\"").append(k[0])
                        .append("\" fillId=\"").append(band == 1 ? 3 : 0).append("\" ").append(border)
                        .append(" applyFont=\"1\"").append(band == 1 ? " applyFill=\"1\"" : "")
                        .append(k[1].equals("0") ? "" : " applyNumberFormat=\"1\"")
                        .append(" xfId=\"0\">").append(k[2]).append("</xf>");
            }
        }
        return head()
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy hh:mm\"/></numFmts>"
                + "<fonts count=\"6\">"
                + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"15\"/><color rgb=\"FF1E6F5C\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><i/><sz val=\"10\"/><color rgb=\"FF6B7780\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><sz val=\"11\"/><color rgb=\"FF8A969C\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FF1F2A30\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "</fonts>"
                + "<fills count=\"4\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1E6F5C\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF5F2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "</fills>"
                + "<borders count=\"2\">"
                + "<border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFC9D2D8\"/></left><right style=\"thin\"><color rgb=\"FFC9D2D8\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFC9D2D8\"/></top><bottom style=\"thin\"><color rgb=\"FFC9D2D8\"/></bottom><diagonal/></border>"
                + "</borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"" + (XF_BODY + 2 * KINDS) + "\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + body
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static double[] columnWidths(Sheet s) {
        double[] w = new double[s.headers.size()];
        for (int c = 0; c < w.length; c++) {
            Double fixed = s.widths.get(c);
            if (fixed != null) {
                w[c] = fixed;
                continue;
            }
            int len = s.headers.get(c).length();
            for (List<Cell> row : s.rows) {
                if (c < row.size()) len = Math.max(len, String.valueOf(row.get(c).v).length());
            }
            w[c] = Math.min(45, Math.max(10, len + 3));
        }
        return w;
    }

    /** Alto de fila aproximado para que se lean completas las notas largas. */
    private static double rowHeight(List<Cell> row, double[] widths) {
        int lines = 1;
        for (int c = 0; c < row.size(); c++) {
            if (row.get(c).kind != WRAP) continue;
            int perLine = Math.max(10, (int) (widths[c] * 1.05));
            int l = 0;
            for (String part : String.valueOf(row.get(c).v).split("\n", -1)) {
                l += Math.max(1, (part.length() + perLine - 1) / perLine);
            }
            lines = Math.max(lines, l);
        }
        return Math.min(409, lines * 15.0 + 3);
    }

    private static String sheetXml(Sheet s) {
        int w = s.headers.size();
        int last = HEADER_ROW + s.rows.size();
        double[] widths = columnWidths(s);
        StringBuilder sb = new StringBuilder(head())
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<dimension ref=\"A1:").append(col(w - 1)).append(last).append("\"/>")
                .append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"100\">")
                .append("<pane ySplit=\"").append(HEADER_ROW).append("\" topLeftCell=\"A").append(HEADER_ROW + 1)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A").append(HEADER_ROW + 1)
                .append("\" sqref=\"A").append(HEADER_ROW + 1).append("\"/>")
                .append("</sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int c = 0; c < w; c++) {
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(widths[c]).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");

        sb.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">");
        textCell(sb, "A1", s.title, XF_TITLE);
        sb.append("</row><row r=\"2\">");
        textCell(sb, "A2", s.info, XF_INFO);
        sb.append("</row>");

        sb.append("<row r=\"").append(HEADER_ROW).append("\" ht=\"22\" customHeight=\"1\">");
        for (int c = 0; c < w; c++) textCell(sb, col(c) + HEADER_ROW, s.headers.get(c), XF_HEADER);
        sb.append("</row>");

        for (int r = 0; r < s.rows.size(); r++) {
            List<Cell> row = s.rows.get(r);
            boolean band = s.band.get(r);
            int rn = HEADER_ROW + 1 + r;
            double ht = rowHeight(row, widths);
            sb.append("<row r=\"").append(rn).append("\"");
            if (ht > 18) sb.append(" ht=\"").append(ht).append("\" customHeight=\"1\"");
            sb.append(">");
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                String ref = col(c) + rn;
                int xf = bodyXf(cell.kind, band);
                if (cell.v instanceof Number) {
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(xf).append("\"><v>")
                            .append(cell.v).append("</v></c>");
                } else if (cell.v instanceof Date) {
                    long ms = ((Date) cell.v).getTime();
                    long local = ms + TimeZone.getDefault().getOffset(ms);
                    double serial = local / 86400000.0 + 25569.0;
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(xf).append("\"><v>")
                            .append(String.format(Locale.US, "%.6f", serial)).append("</v></c>");
                } else {
                    textCell(sb, ref, String.valueOf(cell.v), xf);
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A").append(HEADER_ROW).append(":").append(col(w - 1)).append(last).append("\"/>");
        sb.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static void textCell(StringBuilder sb, String ref, String text, int xf) {
        sb.append("<c r=\"").append(ref).append("\" s=\"").append(xf).append("\"");
        if (text.isEmpty()) {
            sb.append("/>");
            return;
        }
        sb.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(text)).append("</t></is></c>");
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
                case '\r': break;  // Excel usa solo \n dentro de una celda
                default:
                    if ((ch == '\t' || ch == '\n' || ch >= 0x20) && ch < 0xFFFE) sb.append(ch);
            }
        }
        return sb.toString();
    }
}
