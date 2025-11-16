package com.maven.privateuploader.ui.table

import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo

/**
 * 依赖表格列配置
 * 集中管理所有列的元数据，避免硬编码分散在各处
 */
enum class DependencyTableColumn(
    val displayName: String,
    val columnClass: Class<*>,
    val preferredWidth: Int,
    val editable: Boolean = false,
    val valueExtractor: (DependencyInfo) -> Any,
    val defaultValue: Any
) {
    /**
     * 选择列
     */
    SELECTED(
        displayName = "选择",
        columnClass = java.lang.Boolean::class.java,
        preferredWidth = 50,
        editable = true,
        valueExtractor = { it.selected },
        defaultValue = false
    ),

    /**
     * 状态列（第2列，方便用户快速查看）
     */
    STATUS(
        displayName = "状态",
        columnClass = CheckStatus::class.java,
        preferredWidth = 120,
        editable = false,
        valueExtractor = { it.checkStatus },
        defaultValue = CheckStatus.UNKNOWN
    ),

    /**
     * 本地路径列
     * 返回预期路径（即使文件不存在也会返回预期路径，便于在UI中显示）
     */
    LOCAL_PATH(
        displayName = "本地路径",
        columnClass = String::class.java,
        preferredWidth = 300,
        editable = false,
        valueExtractor = { it.getExpectedLocalPath() },
        defaultValue = ""
    ),

    /**
     * GroupId列
     */
    GROUP_ID(
        displayName = "GroupId",
        columnClass = String::class.java,
        preferredWidth = 200,
        editable = false,
        valueExtractor = { it.groupId },
        defaultValue = ""
    ),

    /**
     * ArtifactId列
     */
    ARTIFACT_ID(
        displayName = "ArtifactId",
        columnClass = String::class.java,
        preferredWidth = 150,
        editable = false,
        valueExtractor = { it.artifactId },
        defaultValue = ""
    ),

    /**
     * Version列
     */
    VERSION(
        displayName = "Version",
        columnClass = String::class.java,
        preferredWidth = 100,
        editable = false,
        valueExtractor = { it.version },
        defaultValue = ""
    ),

    /**
     * Packaging列
     */
    PACKAGING(
        displayName = "Packaging",
        columnClass = String::class.java,
        preferredWidth = 80,
        editable = false,
        valueExtractor = { it.packaging },
        defaultValue = ""
    );

    companion object {
        /**
         * 获取所有列（按定义顺序）
         */
        val allColumns: Array<DependencyTableColumn> = values()

        /**
         * 根据索引获取列配置
         */
        fun getByIndex(index: Int): DependencyTableColumn? {
            return if (index >= 0 && index < allColumns.size) {
                allColumns[index]
            } else null
        }
    }
}

