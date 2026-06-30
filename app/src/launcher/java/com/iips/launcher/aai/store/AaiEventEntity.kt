package com.iips.launcher.aai.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "aai_events")
data class AaiEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "correlation_id")
    val correlationId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "event_source")
    val eventSource: String,

    @ColumnInfo(name = "device_timestamp")
    val deviceTimestamp: Long,

    @ColumnInfo(name = "clock_offset_ms")
    val clockOffsetMs: Long,

    @ColumnInfo(name = "confidence")
    val confidence: String,

    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false
)
