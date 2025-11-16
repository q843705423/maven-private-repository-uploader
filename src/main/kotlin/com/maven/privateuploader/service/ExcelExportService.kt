package com.maven.privateuploader.service

import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.io.IOException

/**
 * Excel 导出服务
 * 用于将依赖列表导出为 Excel 文件
 */
object ExcelExportService {

    /**
     * 导出依赖列表到 Excel 文件
     * @param dependencies 依赖列表
     * @param filePath 输出文件路径
     * @param exportMissingOnly 是否只导出缺失的依赖（私仓缺失或本地缺失）
     * @throws IOException 文件写入错误
     */
    @Throws(IOException::class)
    fun exportDependencies(
        dependencies: List<DependencyInfo>,
        filePath: String,
        exportMissingOnly: Boolean = false
    ) {
        // 过滤依赖（如果需要只导出缺失的）
        val filteredDeps = if (exportMissingOnly) {
            dependencies.filter { 
                it.checkStatus == CheckStatus.MISSING || !it.isLocalFileExists()
            }
        } else {
            dependencies
        }

        // 创建工作簿
        val workbook: Workbook = XSSFWorkbook()
        val sheet: Sheet = workbook.createSheet("依赖列表")

        // 创建标题行样式
        val headerStyle = createHeaderStyle(workbook)
        
        // 创建数据行样式
        val dataStyle = createDataStyle(workbook)

        // 创建标题行
        val headerRow = sheet.createRow(0)
        val headers = arrayOf(
            "选择", "状态", "GroupId", "ArtifactId", "Version", "Packaging",
            "本地路径", "错误信息", "GAV坐标"
        )
        
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        // 填充数据行
        filteredDeps.forEachIndexed { rowIndex, dependency ->
            val row = sheet.createRow(rowIndex + 1)
            
            // 选择状态
            row.createCell(0).setCellValue(if (dependency.selected) "是" else "否")
            
            // 状态
            val statusText = when (dependency.checkStatus) {
                CheckStatus.EXISTS -> "私仓-已存在"
                CheckStatus.MISSING -> "私仓-缺失"
                CheckStatus.CHECKING -> "私仓-检查中"
                CheckStatus.ERROR -> "私仓-错误"
                CheckStatus.UNKNOWN -> {
                    if (!dependency.isLocalFileExists()) "未检查（本地缺失）" else "未检查"
                }
            }
            row.createCell(1).setCellValue(statusText)
            
            // GroupId
            row.createCell(2).setCellValue(dependency.groupId)
            
            // ArtifactId
            row.createCell(3).setCellValue(dependency.artifactId)
            
            // Version
            row.createCell(4).setCellValue(dependency.version)
            
            // Packaging
            row.createCell(5).setCellValue(dependency.packaging)
            
            // 本地路径
            val localPath = if (dependency.isLocalFileExists()) {
                dependency.localPath
            } else {
                dependency.getExpectedLocalPath()
            }
            row.createCell(6).setCellValue(localPath)
            
            // 错误信息
            row.createCell(7).setCellValue(dependency.errorMessage)
            
            // GAV坐标
            row.createCell(8).setCellValue(dependency.getGAV())
            
            // 应用样式
            (0 until headers.size).forEach { colIndex ->
                row.getCell(colIndex)?.cellStyle = dataStyle
            }
        }

        // 自动调整列宽
        (0 until headers.size).forEach { columnIndex ->
            sheet.autoSizeColumn(columnIndex)
            // 设置最小列宽，避免列太窄
            val currentWidth = sheet.getColumnWidth(columnIndex)
            if (currentWidth < 2000) {
                sheet.setColumnWidth(columnIndex, 2000)
            }
            // 设置最大列宽，避免列太宽
            if (currentWidth > 15000) {
                sheet.setColumnWidth(columnIndex, 15000)
            }
        }

        // 冻结标题行
        sheet.createFreezePane(0, 1)

        // 写入文件
        FileOutputStream(filePath).use { outputStream ->
            workbook.write(outputStream)
        }

        workbook.close()
    }

    /**
     * 创建标题行样式
     */
    private fun createHeaderStyle(workbook: Workbook): CellStyle {
        val style = workbook.createCellStyle()
        val font = workbook.createFont()
        font.bold = true
        font.fontHeightInPoints = 11
        style.setFont(font)
        style.fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        style.alignment = HorizontalAlignment.CENTER
        style.verticalAlignment = VerticalAlignment.CENTER
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        return style
    }

    /**
     * 创建数据行样式
     */
    private fun createDataStyle(workbook: Workbook): CellStyle {
        val style = workbook.createCellStyle()
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        style.verticalAlignment = VerticalAlignment.CENTER
        return style
    }
}

