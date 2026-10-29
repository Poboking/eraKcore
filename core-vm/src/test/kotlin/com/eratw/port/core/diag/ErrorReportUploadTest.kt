package com.eratw.port.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ErrorReportUpload] 定向单测（方案③ 上报）：钉住端点判定、待发名单、索引合并与
 * 报告名形状。上报是「玩家设备 → 开发者端点」的契约面，判定漂移即返工。
 */
class ErrorReportUploadTest {

    // ---- 端点判定 -------------------------------------------------------

    @Test
    fun 端点必须https且非空白() {
        assertTrue(ErrorReportUpload.isHttpsEndpoint("https://eratw-report.example.workers.dev"))
        assertTrue(ErrorReportUpload.isHttpsEndpoint("  https://x.example  "))
        assertFalse(ErrorReportUpload.isHttpsEndpoint(null))
        assertFalse(ErrorReportUpload.isHttpsEndpoint(""))
        assertFalse(ErrorReportUpload.isHttpsEndpoint("   "))
        assertFalse(ErrorReportUpload.isHttpsEndpoint("http://x.example")) // 明文拒
        assertFalse(ErrorReportUpload.isHttpsEndpoint("ftp://x.example"))
        assertFalse(ErrorReportUpload.isHttpsEndpoint("https://x.example/a b")) // 带空白拒
    }

    // ---- 报告名形状 -----------------------------------------------------

    @Test
    fun 报告名形状与方案一产出一致() {
        assertTrue(ErrorReportUpload.isReportFileName("eratw-report-20261027-123456.txt"))
        assertFalse(ErrorReportUpload.isReportFileName(".uploaded"))
        assertFalse(ErrorReportUpload.isReportFileName("eratw-report-20261027-123456"))
        assertFalse(ErrorReportUpload.isReportFileName("notes.txt"))
    }

    // ---- 待发名单 -------------------------------------------------------

    @Test
    fun 待发名单过滤索引与杂名并按时间序() {
        val disk = listOf(
            "b.txt",
            "eratw-report-20261027-120000.txt",
            "eratw-report-20261026-090000.txt",
            ".uploaded",
            "eratw-report-20261027-130000.txt",
        )
        val uploaded = setOf("eratw-report-20261026-090000.txt")
        assertEquals(
            listOf(
                "eratw-report-20261027-120000.txt",
                "eratw-report-20261027-130000.txt",
            ),
            ErrorReportUpload.pendingNames(disk, uploaded),
        )
    }

    @Test
    fun 索引引用已删除文件时以磁盘实存为准() {
        val disk = listOf("eratw-report-20261027-120000.txt")
        val uploaded = setOf("eratw-report-20261020-000000.txt")
        assertEquals(
            listOf("eratw-report-20261027-120000.txt"),
            ErrorReportUpload.pendingNames(disk, uploaded),
        )
    }

    // ---- 索引合并 -------------------------------------------------------

    @Test
    fun 合并去重升序并滚动保留最近N条() {
        val existing = setOf("eratw-report-20261025-000000.txt", "eratw-report-20261026-000000.txt")
        val merged = ErrorReportUpload.mergeIndex(existing, listOf("eratw-report-20261027-000000.txt"), cap = 2)
        assertEquals(
            listOf("eratw-report-20261026-000000.txt", "eratw-report-20261027-000000.txt"),
            merged,
        )
    }

    @Test
    fun 索引容量缺省为INDEX_CAP且尾部保留() {
        val many = (1..(ErrorReportUpload.INDEX_CAP + 10)).map {
            "eratw-report-2026%04d-000000.txt".format(it)
        }
        val merged = ErrorReportUpload.mergeIndex(emptySet(), many)
        assertEquals(ErrorReportUpload.INDEX_CAP, merged.size)
        assertEquals(many.first(), "eratw-report-20260001-000000.txt")
        assertFalse(merged.contains(many.first()))
        assertTrue(merged.contains(many.last()))
    }
}
