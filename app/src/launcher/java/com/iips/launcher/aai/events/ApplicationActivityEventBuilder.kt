package com.iips.launcher.aai.events

import com.iips.launcher.aai.store.AaiEventEntity
import java.util.UUID

object ApplicationActivityEventBuilder {

    fun buildEvent(
        deviceId: String,
        packageName: String,
        eventType: String,
        eventSource: String,
        confidence: String,
        clockOffsetMs: Long = 0L
    ): AaiEventEntity {
        return AaiEventEntity(
            eventId = UUID.randomUUID().toString(),
            correlationId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            packageName = packageName,
            eventType = eventType,
            eventSource = eventSource,
            deviceTimestamp = System.currentTimeMillis(),
            clockOffsetMs = clockOffsetMs,
            confidence = confidence
        )
    }
}
