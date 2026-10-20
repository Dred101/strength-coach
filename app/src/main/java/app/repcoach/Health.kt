package app.repcoach

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.repcoach.core.Recovery
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Reads recovery signals (sleep, resting heart rate, HRV, steps) from Health Connect. */
class Health(private val context: Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun grantedCount(): Int =
        if (!available) 0 else client.permissionController.getGrantedPermissions().intersect(permissions).size

    suspend fun recovery(): Recovery {
        if (!available) return Recovery()
        val granted = client.permissionController.getGrantedPermissions()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()

        fun has(p: String) = p in granted

        val sleepHours = if (has(HealthPermission.getReadPermission(SleepSessionRecord::class))) runCatching {
            // Last night: sessions ending between 6pm yesterday and now.
            val from = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, now))).records
            sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.takeIf { it > 0 }?.div(60.0)
        }.getOrNull() else null

        var restingHr: Double? = null
        var restingBaseline: Double? = null
        if (has(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) runCatching {
            val records = client.readRecords(
                ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now))
            ).records.sortedBy { it.time }
            restingHr = records.lastOrNull()?.beatsPerMinute?.toDouble()
            restingBaseline = records.map { it.beatsPerMinute.toDouble() }.takeIf { it.size >= 5 }?.average()
        }

        var hrv: Double? = null
        var hrvBaseline: Double? = null
        if (has(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))) runCatching {
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now))
            ).records.sortedBy { it.time }
            hrv = records.lastOrNull()?.heartRateVariabilityMillis
            hrvBaseline = records.map { it.heartRateVariabilityMillis }.takeIf { it.size >= 5 }?.average()
        }

        val steps = if (has(HealthPermission.getReadPermission(StepsRecord::class))) runCatching {
            val from = today.minusDays(1).atStartOfDay(zone).toInstant()
            val to = today.atStartOfDay(zone).toInstant()
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull() else null

        return Recovery(sleepHours, restingHr, restingBaseline, hrv, hrvBaseline, steps)
    }

    /** Latest heart-rate sample from the last few minutes, if the band has synced one. */
    suspend fun latestHeartRate(): Pair<Long, Instant>? {
        if (!available || HealthPermission.getReadPermission(HeartRateRecord::class) !in client.permissionController.getGrantedPermissions()) return null
        val now = Instant.now()
        return runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofMinutes(10)), now)))
                .records.flatMap { it.samples }.maxByOrNull { it.time }?.let { it.beatsPerMinute to it.time }
        }.getOrNull()
    }
}
