package com.jobradar.company;

import com.jobradar.common.ApiException;
import com.jobradar.common.TextUtil;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Reads the upload file into raw rows. Business validation happens in CompanyImportService. */
@Component
public class CompanyFileParser {
    /** Far above the 500 limit so over-long files get row-level errors, while still bounding memory. */
    static final int MAX_ROWS = 5000;

    public record ParsedRow(int rowNumber, String name, String careerUrl, String website, String locationPreference) {}

    public List<ParsedRow> parse(String filename, InputStream in) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".csv")) {
                return toRows(readCsv(in));
            }
            if (lower.endsWith(".xlsx")) {
                return toRows(readXlsx(in));
            }
        } catch (IOException | CsvException | org.apache.poi.EmptyFileException
                 | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw ApiException.badRequest("Could not read the file: it may be corrupt or not a real CSV/XLSX");
        }
        throw ApiException.badRequest("Only .csv and .xlsx files are supported");
    }

    private List<String[]> readCsv(InputStream in) throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.readAll();
        }
    }

    private List<String[]> readXlsx(InputStream in) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<String[]> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                int cells = row == null ? 0 : Math.max(row.getLastCellNum(), 0);
                String[] values = new String[cells];
                for (int c = 0; c < cells; c++) {
                    values[c] = formatter.formatCellValue(row.getCell(c));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private List<ParsedRow> toRows(List<String[]> raw) {
        if (raw.isEmpty()) {
            throw ApiException.badRequest("The file is empty");
        }
        Map<String, Integer> header = new HashMap<>();
        String[] first = raw.get(0);
        for (int i = 0; i < first.length; i++) {
            header.put(first[i].replace("﻿", "").trim().toLowerCase(Locale.ROOT), i);
        }
        if (!header.containsKey("company_name")) {
            throw ApiException.badRequest("Missing required column: company_name");
        }
        if (raw.size() - 1 > MAX_ROWS) {
            throw ApiException.badRequest("The file has too many rows (maximum " + MAX_ROWS + ")");
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            String[] line = raw.get(i);
            String name = cell(line, header.get("company_name"));
            String career = cell(line, header.get("career_page_url"));
            String website = cell(line, header.get("website"));
            String location = cell(line, header.get("location_preference"));
            if (name == null && career == null && website == null && location == null) {
                continue; // blank spreadsheet row
            }
            rows.add(new ParsedRow(i + 1, name, career, website, location));
        }
        return rows;
    }

    private static String cell(String[] line, Integer index) {
        if (index == null || index >= line.length || line[index] == null) {
            return null;
        }
        String value = TextUtil.stripNul(line[index]).trim();
        return value.isEmpty() ? null : value;
    }
}
