package com.gzhu.seatbooking.app.domain

object SeatMapper {
    private const val ROOM_103_BASE_DEV_ID_OFFSET = 101267043

    fun mapSeatCodeToDevId(seatCode: String): Int? {
        val regex = Regex("^103-(\\d{3})$")
        val match = regex.find(seatCode) ?: return null
        val seatNumber = match.groupValues[1].toIntOrNull() ?: return null
        return ROOM_103_BASE_DEV_ID_OFFSET + seatNumber
    }
}

