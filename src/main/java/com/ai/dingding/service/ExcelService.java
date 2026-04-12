package com.ai.dingding.service;

import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Excel 后处理工具：在考试记录 Excel 首列插入"区域"列，
 * 区域值从"用户昵称"列的括号内容提取，兼容中英文括号。
 */
@Log4j2
public class ExcelService {

    private static final String NICK_NAME_HEADER = "用户昵称";
    private static final String REGION_HEADER = "区域";

    /**
     * 读取 Excel 字节数组，在首列插入"区域"列后返回新字节数组。
     * 处理失败时记录错误并返回原始字节，保证文件可用。
     */
    public static byte[] addRegionColumn(byte[] excelBytes) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
                processSheet(workbook.getSheetAt(si));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Excel 添加区域列处理异常，返回原始文件", e);
            return excelBytes;
        }
    }

    private static void processSheet(Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            return;
        }

        // 找到"用户昵称"列索引
        int nickNameColIdx = -1;
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            if (NICK_NAME_HEADER.equals(getCellString(headerRow.getCell(i)))) {
                nickNameColIdx = i;
                break;
            }
        }
        if (nickNameColIdx < 0) {
            log.warn("Sheet「{}」未找到「{}」列，跳过区域提取", sheet.getSheetName(), NICK_NAME_HEADER);
            return;
        }

        // 逐行在首列插入区域值：从后往前移动所有单元格，再写入首列
        for (int rowIdx = 0; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) {
                continue;
            }
            shiftCellsRight(row);

            Cell firstCell = row.createCell(0);
            if (rowIdx == 0) {
                firstCell.setCellValue(REGION_HEADER);
                // 复制"用户昵称"表头样式，保持与其他表头一致
                Cell nickNameHeader = row.getCell(nickNameColIdx + 1);
                if (nickNameHeader != null) {
                    firstCell.setCellStyle(nickNameHeader.getCellStyle());
                }
            } else {
                // nickNameColIdx + 1：移位后昵称列向右偏移了一位
                String nickName = getCellString(row.getCell(nickNameColIdx + 1));
                firstCell.setCellValue(extractRegion(nickName));
            }
        }
    }

    /**
     * 将 row 中所有单元格整体向右移动一列（col → col+1），从右往左操作避免覆盖。
     */
    private static void shiftCellsRight(Row row) {
        int lastCol = row.getLastCellNum() - 1;
        for (int col = lastCol; col >= 0; col--) {
            Cell src = row.getCell(col);
            Cell dest = row.createCell(col + 1);
            if (src != null) {
                copyCell(src, dest);
            }
        }
    }

    private static void copyCell(Cell src, Cell dest) {
        dest.setCellStyle(src.getCellStyle());
        CellType type = src.getCellType();
        if (type == CellType.FORMULA) {
            type = src.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING  -> dest.setCellValue(src.getStringCellValue());
            case NUMERIC -> dest.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dest.setCellValue(src.getBooleanCellValue());
            default      -> dest.setCellValue(src.toString());
        }
    }

    private static String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> cell.toString();
        };
    }

    /**
     * 从昵称末尾括号中提取区域，兼容中文（）和英文()。
     * 示例："夏伊热（哈密）" → "哈密"，无括号返回空字符串。
     */
    static String extractRegion(String nickName) {
        if (nickName == null || nickName.isBlank()) {
            return "";
        }
        int lastClose = -1;
        int lastOpen = -1;
        for (int i = nickName.length() - 1; i >= 0; i--) {
            char c = nickName.charAt(i);
            if ((c == '）' || c == ')') && lastClose == -1) {
                lastClose = i;
            } else if ((c == '（' || c == '(') && lastClose != -1) {
                lastOpen = i;
                break;
            }
        }
        if (lastOpen >= 0 && lastClose > lastOpen) {
            String region = nickName.substring(lastOpen + 1, lastClose).trim();
            return region.isBlank() ? "" : region;
        }
        return "";
    }
}
