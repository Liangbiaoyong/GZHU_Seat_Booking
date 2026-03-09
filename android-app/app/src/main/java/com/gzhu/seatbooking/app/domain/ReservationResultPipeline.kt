package com.gzhu.seatbooking.app.domain

import com.gzhu.seatbooking.app.GzhuSeatBookingApp
import com.gzhu.seatbooking.app.data.model.ReservationResult

object ReservationResultPipeline {
    data class Summary(
        val successCount: Int,
        val failCount: Int
    )

    fun record(
        app: GzhuSeatBookingApp,
        scene: String,
        title: String,
        results: List<ReservationResult>
    ): Summary {
        if (results.isEmpty()) {
            app.logRepository.append("INFO", "结果入库跳过：scene=$scene title=$title total=0")
            return Summary(successCount = 0, failCount = 0)
        }
        val panelResults = collapseTransientFrequencyFailures(results)
        app.reservationResultRepository.append(panelResults)
        val successCount = panelResults.count { it.success }
        val failCount = panelResults.size - successCount
        results.forEach { result ->
            val level = if (result.success) "SUCCESS" else "ERROR"
            app.logRepository.append(
                level,
                "结果明细：scene=$scene time=${result.requestAt} date=${result.date} seat=${result.seatCode} range=${result.start}-${result.end} code=${result.code} message=${result.message}"
            )
        }
        if (panelResults.size != results.size) {
            app.logRepository.append(
                "INFO",
                "结果面板已折叠瞬时限频失败：scene=$scene rawTotal=${results.size} panelTotal=${panelResults.size}"
            )
        }
        app.logRepository.append(
            "INFO",
            "结果入库：scene=$scene title=$title total=${panelResults.size} success=$successCount fail=$failCount"
        )
        return Summary(successCount = successCount, failCount = failCount)
    }

    private fun collapseTransientFrequencyFailures(results: List<ReservationResult>): List<ReservationResult> {
        val successKeys = results
            .asSequence()
            .filter { it.success }
            .map { panelKey(it) }
            .toSet()
        if (successKeys.isEmpty()) return results

        return results.filter { result ->
            if (result.success) return@filter true
            val message = result.message.lowercase()
            val isFrequencyFailure = message.contains("请求频繁") ||
                message.contains("操作频繁") ||
                message.contains("请稍后") ||
                message.contains("too many") ||
                message.contains("too frequent") ||
                message.contains("rate")
            if (!isFrequencyFailure) return@filter true
            panelKey(result) !in successKeys
        }
    }

    private fun panelKey(result: ReservationResult): String {
        return "${result.date}|${result.seatCode}|${result.start}|${result.end}"
    }
}

