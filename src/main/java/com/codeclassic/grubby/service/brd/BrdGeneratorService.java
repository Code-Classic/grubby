package com.codeclassic.grubby.service.brd;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.List;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Renders BRD Markdown content into PDF and DOCX formats.
 *
 * Improvements:
 * - PDF uses standard fonts (Helvetica/Times) for better heading appearance
 * - Numbered lists (1. 2. 3.) are now handled in both PDF and DOCX
 * - Inline bold (**text**) is stripped cleanly before rendering
 * - Blockquote lines (> text) are rendered with indentation
 * - DOCX headings use proper Word styles (Heading1/Heading2/Heading3)
 * - Empty document fallback is handled more gracefully
 */
@Service
public class BrdGeneratorService {

    public byte[] renderMarkdown(String markdown) {
        return (markdown == null ? "" : markdown).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] renderPdf(String markdown) {
        String md = markdown == null ? "" : markdown;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfFont bold   = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont normal = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
            Document doc = new Document(pdf);

            String[] lines = md.split("\r?\n");
            List currentList = null;

            for (String raw : lines) {
                String line = raw;

                // Flush pending list on blank line or non-list content
                boolean isBlank     = line.trim().isEmpty();
                boolean isHeading   = line.startsWith("#");
                boolean isBullet    = line.trim().startsWith("- ");
                boolean isNumbered  = line.trim().matches("^\\d+\\.\\s+.*");
                boolean isBlockquote = line.trim().startsWith("> ");

                if ((isBlank || isHeading || (!isBullet && !isNumbered)) && currentList != null) {
                    doc.add(currentList);
                    currentList = null;
                }

                if (isBlank) {
                    doc.add(new Paragraph(" ").setFont(normal).setFontSize(6));
                    continue;
                }

                if (isHeading) {
                    int level = countPrefix(line, '#');
                    String text = stripInlineMarkdown(line.substring(level).trim());
                    float size = level == 1 ? 20 : level == 2 ? 16 : 13;
                    Paragraph p = new Paragraph(text)
                            .setFont(bold).setFontSize(size)
                            .setFontColor(level == 1 ? ColorConstants.DARK_GRAY : ColorConstants.BLACK)
                            .setMarginTop(level == 1 ? 14 : 8).setMarginBottom(4);
                    if (level == 1) p.setTextAlignment(TextAlignment.LEFT);
                    doc.add(p);
                    continue;
                }

                if (isBullet || isNumbered) {
                    if (currentList == null) currentList = new List().setFont(normal).setFontSize(11);
                    String text = isBullet
                            ? stripInlineMarkdown(line.trim().substring(2))
                            : stripInlineMarkdown(line.trim().replaceFirst("^\\d+\\.\\s+", ""));
                    currentList.add(new ListItem(text));
                    continue;
                }

                if (isBlockquote) {
                    String text = stripInlineMarkdown(line.trim().substring(2));
                    doc.add(new Paragraph(text)
                            .setFont(normal).setFontSize(10)
                            .setFontColor(ColorConstants.GRAY)
                            .setMarginLeft(16).setItalic());
                    continue;
                }

                doc.add(new Paragraph(stripInlineMarkdown(line)).setFont(normal).setFontSize(11));
            }
            if (currentList != null) doc.add(currentList);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            return fallbackPdf("BRD PDF generation failed: " + e.getClass().getSimpleName());
        }
    }

    public byte[] renderDocx(String markdown) {
        String md = markdown == null ? "" : markdown;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            String[] lines = md.split("\r?\n");

            for (String raw : lines) {
                String line = raw;
                if (line.trim().isEmpty()) {
                    doc.createParagraph().createRun().setText("");
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = countPrefix(line, '#');
                    String text = stripInlineMarkdown(line.substring(level).trim());
                    XWPFParagraph p = doc.createParagraph();
                    // Use Word heading styles for proper document structure
                    p.setStyle(level == 1 ? "Heading1" : level == 2 ? "Heading2" : "Heading3");
                    XWPFRun r = p.createRun();
                    r.setText(text);
                    r.setBold(true);
                    r.setFontSize(level == 1 ? 18 : level == 2 ? 16 : 14);
                    continue;
                }
                if (line.trim().startsWith("- ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setNumID(ensureBulletNum(doc));
                    XWPFRun r = p.createRun();
                    r.setText(stripInlineMarkdown(line.trim().substring(2)));
                    continue;
                }
                if (line.trim().matches("^\\d+\\.\\s+.*")) {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText(stripInlineMarkdown(line.trim()));
                    continue;
                }
                if (line.trim().startsWith("> ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(720); // 0.5 inch indent
                    XWPFRun r = p.createRun();
                    r.setItalic(true);
                    r.setText(stripInlineMarkdown(line.trim().substring(2)));
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(stripInlineMarkdown(line));
            }
            doc.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strips **bold**, *italic*, `code` markers from a line before rendering. */
    private String stripInlineMarkdown(String s) {
        if (s == null) return "";
        return s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("~~([^~]+)~~", "$1");
    }

    private int countPrefix(String s, char c) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == c) i++;
        return i;
    }

    private byte[] fallbackPdf(String message) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
            Document doc = new Document(pdf);
            doc.add(new Paragraph(message == null ? "PDF generation error" : message));
            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    /**
     * Returns a numbering ID for bullet lists in DOCX.
     * POI requires at least one numbering definition to exist.
     */
    private java.math.BigInteger ensureBulletNum(XWPFDocument doc) {
        if (doc.getNumbering() == null) {
            doc.createNumbering();
        }
        var numbering = doc.getNumbering();
        // Use abstract numbering 0 if it exists, otherwise create
        try {
            var abs = numbering.getAbstractNum(java.math.BigInteger.ZERO);
            if (abs != null) return numbering.addNum(java.math.BigInteger.ZERO);
        } catch (Exception ignored) {}
        return java.math.BigInteger.ONE;
    }
}
