package com.sangwolnongsan.farmwork.data.photo

import com.sangwolnongsan.farmwork.data.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * NTP(SNTP) 신뢰시각 직접 조회 — 자체 서버 없이 단말 시계 조작에 강한 촬영 시각 확보.
 *
 * 외부 라이브러리 없이 표준 SNTP(UDP/123) 직접 구현. 여러 서버를 순차 시도.
 * 성공 시 NTP epoch millis, 전부 실패 시 null (호출부가 기기 시각으로 fallback).
 */
object TrustedTime {

    private val SERVERS = listOf("time.google.com", "time.kriss.re.kr", "time.cloudflare.com")

    data class Result(val millis: Long, val source: TimeSource)

    /** NTP 시각 조회 후 출처와 함께 반환. 실패 시 기기 시각 + DEVICE. */
    suspend fun now(): Result = withContext(Dispatchers.IO) {
        for (host in SERVERS) {
            try {
                val ntp = requestTime(host)
                if (ntp > 0) return@withContext Result(ntp, TimeSource.NTP)
            } catch (_: Exception) { /* 다음 서버 시도 */ }
        }
        Result(System.currentTimeMillis(), TimeSource.DEVICE)
    }

    private const val NTP_PACKET_SIZE = 48
    private const val NTP_PORT = 123
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    private const val OFFSET_1900_TO_1970 = 2208988800L

    private fun requestTime(host: String): Long {
        val socket = DatagramSocket()
        socket.soTimeout = 3000
        try {
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)
            // LI=0, VN=3, Mode=3 (client)
            buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            // Transmit Timestamp: 초(40) + 소수부(44)
            val seconds = readUInt32(buffer, 40)
            val fraction = readUInt32(buffer, 44)
            if (seconds == 0L) return -1L
            return (seconds - OFFSET_1900_TO_1970) * 1000L + (fraction * 1000L) / 0x100000000L
        } finally {
            socket.close()
        }
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 4) {
            result = (result shl 8) or (buf[offset + i].toLong() and 0xff)
        }
        return result
    }
}
