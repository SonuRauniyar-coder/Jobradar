package com.jobradar.job;

import com.jobradar.job.JobDtos.JobRow;
import com.opencsv.CSVWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class JobExporter {
    private static final String[] HEADER = {"Company", "Job Title", "Location", "Work Mode", "Experience", "Category", "Role",
            "Posted", "First Seen", "Source", "Status", "Applied At", "Match %", "Apply URL"};

    public byte[] toCsv(List<JobRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.writeNext(HEADER);
            rows.forEach(row -> writer.writeNext(values(row)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public byte[] toXlsx(List<JobRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Jobs");
            writeRow(sheet.createRow(0), HEADER);
            for (int i = 0; i < rows.size(); i++) {
                writeRow(sheet.createRow(i + 1), values(rows.get(i)));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeRow(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private static String[] values(JobRow r) {
        return new String[] {safe(r.companyName()), safe(r.title()), safe(r.location()), r.workMode().name(), safe(experience(r)),
                r.category().name(), r.roleCategory().name(), text(r.postedAt()), text(r.firstSeenAt()), r.source().name(),
                r.status().name(), text(r.appliedAt()), String.valueOf(r.matchScore()), safe(r.applyUrl())};
    }

    private static String experience(JobRow r) {
        if (r.experienceRaw() != null) {
            return r.experienceRaw();
        }
        return r.minYears() == null ? "" : r.minYears() + "+ years";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** Job text comes from third-party sites; a leading = + - or @ would make a spreadsheet run it as a formula. */
    static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
