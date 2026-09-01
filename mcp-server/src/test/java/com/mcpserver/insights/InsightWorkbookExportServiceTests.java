package com.mcpserver.insights;

import com.mcpserver.reports.RqlModel;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsightWorkbookExportServiceTests {

    @Test
    void createsSummaryRequestAndDatasetSheetsWithoutFormulaInjection() throws Exception {
        InsightModel.Data data = new InsightModel.Data(
                Map.of("orders/2026", new InsightModel.DatasetData(
                        List.of("id", "note"),
                        List.of(Map.of("id", 7, "note", "=not-a-formula")),
                        Map.of("id", "number", "note", "string"))),
                List.of(), List.of(), List.of(),
                List.of(new RqlModel.RequestExecution("Orders", "GET", 200, true, 14, false)));

        byte[] bytes = new InsightWorkbookExportService().export("Revenue report", data);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("Summary").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Revenue report");
            assertThat(workbook.getSheet("Requests").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Orders");
            assertThat(workbook.getSheet("orders_2026").getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("'=not-a-formula");
        }
    }
}
