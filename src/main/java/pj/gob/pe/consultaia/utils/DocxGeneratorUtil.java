package pj.gob.pe.consultaia.utils;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class DocxGeneratorUtil {

    private static final String DEFAULT_FONT = "Calibri";
    private static final int FONT_SIZE_BODY = 11;
    private static final int FONT_SIZE_H1 = 16;
    private static final int FONT_SIZE_H2 = 14;
    private static final int FONT_SIZE_H3 = 12;

    private DocxGeneratorUtil() {
    }

    public static byte[] textToDocx(String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            String content = text != null ? text : "";
            String[] lines = content.split("\\r?\\n", -1);

            for (String line : lines) {
                writeLine(doc, line);
            }

            doc.write(baos);
            return baos.toByteArray();
        }
    }

    private static void writeLine(XWPFDocument doc, String line) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.BOTH);

        if (line == null || line.trim().isEmpty()) {
            return;
        }

        String trimmed = line.trim();

        if (trimmed.startsWith("### ")) {
            addStyledRun(paragraph, trimmed.substring(4), true, FONT_SIZE_H3);
            return;
        }
        if (trimmed.startsWith("## ")) {
            addStyledRun(paragraph, trimmed.substring(3), true, FONT_SIZE_H2);
            return;
        }
        if (trimmed.startsWith("# ")) {
            addStyledRun(paragraph, trimmed.substring(2), true, FONT_SIZE_H1);
            return;
        }

        String workingLine = line;
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            paragraph.setIndentationLeft(360);
            workingLine = "• " + trimmed.substring(2);
        }

        writeWithInlineFormatting(paragraph, workingLine);
    }

    private static void writeWithInlineFormatting(XWPFParagraph paragraph, String line) {
        StringBuilder buf = new StringBuilder();
        boolean bold = false;
        boolean italic = false;
        int i = 0;

        while (i < line.length()) {
            if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '*') {
                flushBuffer(paragraph, buf, bold, italic);
                bold = !bold;
                i += 2;
            } else if (line.charAt(i) == '*' || line.charAt(i) == '_') {
                flushBuffer(paragraph, buf, bold, italic);
                italic = !italic;
                i++;
            } else {
                buf.append(line.charAt(i));
                i++;
            }
        }
        flushBuffer(paragraph, buf, bold, italic);
    }

    private static void flushBuffer(XWPFParagraph paragraph, StringBuilder buf, boolean bold, boolean italic) {
        if (buf.length() == 0) return;
        XWPFRun run = paragraph.createRun();
        run.setText(buf.toString());
        run.setBold(bold);
        run.setItalic(italic);
        run.setFontSize(FONT_SIZE_BODY);
        run.setFontFamily(DEFAULT_FONT);
        buf.setLength(0);
    }

    private static void addStyledRun(XWPFParagraph paragraph, String text, boolean bold, int fontSize) {
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setFontFamily(DEFAULT_FONT);
    }
}
