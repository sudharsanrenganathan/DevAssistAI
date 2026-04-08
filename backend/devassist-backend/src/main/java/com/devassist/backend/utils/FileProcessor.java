package com.devassist.backend.utils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public class FileProcessor {

    public static String extractText(MultipartFile file) {
        try {
            String contentType = file.getContentType();

            if (contentType != null && contentType.equals("application/pdf")) {
                return extractPDF(file);
            }

            // fallback (txt, etc.)
            return new String(file.getBytes());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String extractPDF(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             PDDocument document = PDDocument.load(is)) {

            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}