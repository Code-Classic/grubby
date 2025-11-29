package com.codeclassic.grubby.service.brd;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.List;
import com.itextpdf.layout.element.ListItem;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

@Service
public class BrdGeneratorService {

    /**
     * Canonical markdown bytes (UTF-8).
     */
    public byte[] renderMarkdown(String markdown) {
        return (markdown == null ? "" : markdown).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Very simple Markdown -> PDF rendering using iText7 by mapping headings, lists, and paragraphs.
     */
    public byte[] renderPdf(String markdown) {
        String md = markdown == null ? "" : markdown;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);

            String[] lines = md.split("\r?\n");
            boolean inList = false;
            List currentList = null;

            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    if (inList && currentList != null) {
                        doc.add(currentList);
                        inList = false;
                        currentList = null;
                    } else {
                        doc.add(new Paragraph(" "));
                    }
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = countPrefix(line, '#');
                    String text = line.substring(level).trim();
                    Paragraph p = new Paragraph(text).setBold();
                    if (level == 1) {
                        p.setFontSize(18);
                    } else if (level == 2) {
                        p.setFontSize(16);
                    } else {
                        p.setFontSize(14);
                    }
                    if (inList && currentList != null) {
                        doc.add(currentList);
                        inList = false;
                        currentList = null;
                    }
                    doc.add(p);
                    continue;
                }
                if (line.trim().startsWith("- ")) {
                    if (!inList) {
                        inList = true;
                        currentList = new List();
                    }
                    currentList.add(new ListItem(line.trim().substring(2)));
                    continue;
                }
                // default paragraph
                if (inList && currentList != null) {
                    doc.add(currentList);
                    inList = false;
                    currentList = null;
                }
                doc.add(new Paragraph(line));
            }
            if (inList && currentList != null) {
                doc.add(currentList);
            }
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            // If PDF generation fails, return minimal PDF with error text
            return fallbackPdf("BRD PDF generation failed: " + e.getMessage());
        }
    }

    private byte[] fallbackPdf(String message) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);
            doc.add(new Paragraph(message == null ? "PDF generation error" : message));
            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    /**
     * Very simple Markdown -> DOCX using Apache POI XWPF.
     */
    public byte[] renderDocx(String markdown) {
        String md = markdown == null ? "" : markdown;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            String[] lines = md.split("\r?\n");
            boolean inList = false;

            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.createRun().setText("");
                    inList = false; // simple reset
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = countPrefix(line, '#');
                    String text = line.substring(level).trim();
                    XWPFParagraph p = doc.createParagraph();
                    if (level == 1) p.setAlignment(ParagraphAlignment.LEFT);
                    XWPFRun r = p.createRun();
                    r.setText(text);
                    r.setBold(true);
                    if (level == 1) r.setFontSize(18);
                    else if (level == 2) r.setFontSize(16);
                    else r.setFontSize(14);
                    inList = false;
                    continue;
                }
                if (line.trim().startsWith("- ")) {
                    // Simulate bullet: add an indented paragraph with a bullet prefix
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun r = p.createRun();
                    r.setText("• " + line.trim().substring(2));
                    inList = true;
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(line);
                inList = false;
            }
            doc.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private int countPrefix(String s, char c) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == c) i++;
        return i;
    }
}
