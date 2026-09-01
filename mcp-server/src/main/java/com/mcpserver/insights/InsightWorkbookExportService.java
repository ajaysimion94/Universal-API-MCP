package com.mcpserver.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.reports.RqlModel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a fully executed Insights/RQL report into a portable Excel workbook. */
@Service
public class InsightWorkbookExportService {

    private static final int EXCEL_CELL_LIMIT = 32_767;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private final ObjectMapper json = new ObjectMapper();

    public byte[] export(String title, InsightModel.Data data) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            writeSummary(workbook.createSheet("Summary"), titleStyle, headerStyle, title, data);
            writeRequests(workbook.createSheet("Requests"), headerStyle, data.requests());

            Set<String> names = new LinkedHashSet<>();
            names.add("Summary");
            names.add("Requests");
            for (Map.Entry<String, InsightModel.DatasetData> entry : data.datasets().entrySet()) {
                Sheet sheet = workbook.createSheet(uniqueSheetName(entry.getKey(), names));
                writeDataset(sheet, headerStyle, entry.getValue());
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create the Excel report: " + exception.getMessage(), exception);
        }
    }

    private void writeSummary(Sheet sheet, CellStyle titleStyle, CellStyle headerStyle, String title,
                              InsightModel.Data data) {
        Row titleRow = sheet.createRow(0);
        writeCell(titleRow, 0, title == null || title.isBlank() ? "Insight report" : title, titleStyle);
        int row = 2;
        String[][] values = {
                {"Generated", TIME.format(Instant.now())},
                {"Datasets", String.valueOf(data.datasets().size())},
                {"Rows", String.valueOf(data.datasets().values().stream().mapToInt(value -> value.rows().size()).sum())},
                {"Requests", String.valueOf(data.requests().size())},
                {"Successful requests", String.valueOf(data.requests().stream().filter(RqlModel.RequestExecution::success).count())},
                {"Diagnostics", String.valueOf(data.diagnostics().size())}
        };
        for (String[] value : values) {
            Row current = sheet.createRow(row++);
            writeCell(current, 0, value[0], headerStyle);
            writeCell(current, 1, value[1], null);
        }
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 44 * 256);
    }

    private void writeRequests(Sheet sheet, CellStyle headerStyle, List<RqlModel.RequestExecution> requests) {
        Row header = sheet.createRow(0);
        String[] columns = {"Request", "Method", "Status", "Success", "Duration (ms)", "Cached"};
        for (int index = 0; index < columns.length; index++) writeCell(header, index, columns[index], headerStyle);
        int rowNumber = 1;
        for (RqlModel.RequestExecution request : requests) {
            Row row = sheet.createRow(rowNumber++);
            writeCell(row, 0, request.request(), null);
            writeCell(row, 1, request.method(), null);
            writeCell(row, 2, String.valueOf(request.status()), null);
            writeCell(row, 3, String.valueOf(request.success()), null);
            writeCell(row, 4, String.valueOf(request.durationMs()), null);
            writeCell(row, 5, String.valueOf(request.cached()), null);
        }
        for (int index = 0; index < columns.length; index++) sheet.setColumnWidth(index, 20 * 256);
    }

    private void writeDataset(Sheet sheet, CellStyle headerStyle, InsightModel.DatasetData dataset) {
        List<String> columns = dataset.columns();
        Row header = sheet.createRow(0);
        for (int index = 0; index < columns.size(); index++) {
            writeCell(header, index, columns.get(index), headerStyle);
            sheet.setColumnWidth(index, 22 * 256);
        }
        int rowNumber = 1;
        for (Map<String, Object> values : dataset.rows()) {
            Row row = sheet.createRow(rowNumber++);
            for (int index = 0; index < columns.size(); index++) writeCell(row, index, serialize(values.get(columns.get(index))), null);
        }
        sheet.createFreezePane(0, 1);
    }

    private String serialize(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try { return json.writeValueAsString(value); }
            catch (Exception ignored) { return String.valueOf(value); }
        }
        return String.valueOf(value);
    }

    private static void writeCell(Row row, int index, String value, CellStyle style) {
        Cell cell = row.createCell(index);
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        if (safe.length() > EXCEL_CELL_LIMIT) safe = safe.substring(0, EXCEL_CELL_LIMIT - 1) + "…";
        cell.setCellValue(safe);
        if (style != null) cell.setCellStyle(style);
    }

    private static CellStyle titleStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 15);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static String uniqueSheetName(String candidate, Set<String> used) {
        String base = candidate == null || candidate.isBlank() ? "Dataset" : candidate
                .replace('\\', '_').replace('/', '_').replace(':', '_').replace('*', '_')
                .replace('?', '_').replace('[', '_').replace(']', '_');
        base = base.length() > 31 ? base.substring(0, 31) : base;
        String name = base;
        for (int suffix = 2; used.contains(name); suffix++) {
            String tail = "_" + suffix;
            name = base.substring(0, Math.min(base.length(), 31 - tail.length())) + tail;
        }
        used.add(name);
        return name;
    }
}
