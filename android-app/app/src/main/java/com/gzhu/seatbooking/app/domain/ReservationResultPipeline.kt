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
        app.reservationResultRepository.append(results)
        val successCount = results.count { it.success }
        val failCount = results.size - successCount
        results.forEach { result ->
            val level = if (result.success) "SUCCESS" else "ERROR"
            app.logRepository.append(
                level,
                "结果明细：scene=$scene time=${result.requestAt} date=${result.date} seat=${result.seatCode} range=${result.start}-${result.end} code=${result.code} message=${result.message}"
            )
        }
        app.logRepository.append(
            "INFO",
            "结果入库：scene=$scene title=$title total=${results.size} success=$successCount fail=$failCount"
        )
        return Summary(successCount = successCount, failCount = failCount)
    }
}

