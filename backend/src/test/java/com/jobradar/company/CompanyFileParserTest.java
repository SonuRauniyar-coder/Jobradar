package com.jobradar.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.common.ApiException;
import com.jobradar.company.CompanyFileParser.ParsedRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CompanyFileParserTest {
    private final CompanyFileParser parser = new CompanyFileParser();

    private List<ParsedRow> csv(String content) {
        return parser.parse("c.csv", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsAllCsvColumns() {
        List<ParsedRow> rows = csv("company_name,career_page_url,website,location_preference\nAcme,https://a.co/jobs,https://a.co,Noida\n");
        assertThat(rows).containsExactly(new ParsedRow(2, "Acme", "https://a.co/jobs", "https://a.co", "Noida"));
    }

    @Test
    void headerOrderAndCaseDoNotMatterAndOptionalColumnsMayBeMissing() {
        List<ParsedRow> rows = csv("Website,COMPANY_NAME\nhttps://a.co,Acme\n");
        assertThat(rows.get(0).name()).isEqualTo("Acme");
        assertThat(rows.get(0).website()).isEqualTo("https://a.co");
        assertThat(rows.get(0).careerUrl()).isNull();
    }

    @Test
    void stripsByteOrderMarkFromExcelCsv() {
        assertThat(csv("﻿company_name\nAcme\n")).hasSize(1);
    }

    @Test
    void handlesQuotedCommas() {
        assertThat(csv("company_name,website\n\"Acme, Inc\",https://a.co\n").get(0).name()).isEqualTo("Acme, Inc");
    }

    @Test
    void skipsBlankRowsButKeepsTheFileRowNumber() {
        List<ParsedRow> rows = csv("company_name,website\n,\nAcme,\n");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rowNumber()).isEqualTo(3);
    }

    @Test
    void keepsRowWithMissingNameSoItCanBeReported() {
        assertThat(csv("company_name,website\n,https://a.co\n").get(0).name()).isNull();
    }

    @Test
    void rejectsMissingRequiredColumn() {
        assertThatThrownBy(() -> csv("name,website\nAcme,\n"))
                .isInstanceOf(ApiException.class).hasMessageContaining("company_name");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> parser.parse("c.txt", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(ApiException.class).hasMessageContaining(".csv");
    }

    @Test
    void rejectsFakeXlsx() {
        assertThatThrownBy(() -> parser.parse("c.xlsx", new ByteArrayInputStream("not excel".getBytes())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void readsXlsx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("company_name");
            header.createCell(1).setCellValue("website");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Acme");
            row.createCell(1).setCellValue("https://a.co");
            wb.write(out);
        }
        List<ParsedRow> rows = parser.parse("C.XLSX", new ByteArrayInputStream(out.toByteArray()));
        assertThat(rows).containsExactly(new ParsedRow(2, "Acme", null, "https://a.co", null));
    }
}
