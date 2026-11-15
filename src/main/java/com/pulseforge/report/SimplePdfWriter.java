package com.pulseforge.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PDF 1.4 writer for monospaced text reports (no external libraries). Lines are
 * wrapped to the page width and paginated automatically; headings (lines starting with "# ")
 * are rendered in bold.
 */
public final class SimplePdfWriter {

    private static final int PAGE_W = 595, PAGE_H = 842, MARGIN = 40;
    private static final float FONT_SIZE = 8.5f, LEADING = 11f;
    private static final int MAX_CHARS = (int) ((PAGE_W - 2 * MARGIN) / (FONT_SIZE * 0.6));
    private static final int LINES_PER_PAGE = (int) ((PAGE_H - 2 * MARGIN) / LEADING);

    private SimplePdfWriter() {
    }

    public static void write(List<String> rawLines, Path out) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String s = sanitize(l);
            if (s.isEmpty()) {
                lines.add("");
            }
            while (!s.isEmpty()) {
                int cut = Math.min(s.length(), MAX_CHARS);
                lines.add(s.substring(0, cut));
                s = s.substring(cut);
            }
        }
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += LINES_PER_PAGE) {
            pages.add(lines.subList(i, Math.min(lines.size(), i + LINES_PER_PAGE)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of(""));
        }

        // Object numbering: 1 catalog, 2 pages, 3 font regular, 4 font bold, then (page, content) pairs.
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) {
            kids.append(5 + p * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold >>");
        for (int p = 0; p < pages.size(); p++) {
            int contentObj = 6 + p * 2;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_W + " " + PAGE_H + "] "
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentObj + " 0 R >>");
            String stream = pageStream(pages.get(p), p + 1, pages.size());
            objects.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        append(buf, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(buf.size());
            append(buf, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = buf.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        append(buf, sb.toString());
        Files.write(out, buf.toByteArray());
    }

    private static String pageStream(List<String> lines, int pageNo, int pageCount) {
        StringBuilder s = new StringBuilder();
        s.append("BT\n").append(String.format("%.1f TL\n", LEADING));
        s.append(MARGIN).append(' ').append(PAGE_H - MARGIN).append(" Td\n");
        for (String line : lines) {
            boolean heading = line.startsWith("# ") || line.startsWith("## ");
            String text = heading ? line.replaceFirst("^#+ ", "") : line;
            s.append(heading ? "/F2 " : "/F1 ").append(FONT_SIZE).append(" Tf\n");
            s.append('(').append(escape(text)).append(") Tj T*\n");
        }
        s.append("ET\n");
        s.append("BT /F1 7 Tf ").append(PAGE_W / 2 - 25).append(" 20 Td (Page ").append(pageNo).append(" of ")
                .append(pageCount).append(") Tj ET");
        return s.toString();
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\t') sb.append("    ");
            else if (c >= 32 && c < 127) sb.append(c);
            else if (c == 0x2013 || c == 0x2014) sb.append('-');            // en/em dash
            else if (c == 0x2018 || c == 0x2019) sb.append('\'');           // curly single quotes
            else if (c == 0x201C || c == 0x201D) sb.append('"');            // curly double quotes
            else if (c != '\r' && c != '\n') sb.append('?');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static void append(ByteArrayOutputStream buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        buf.write(b, 0, b.length);
    }
}
