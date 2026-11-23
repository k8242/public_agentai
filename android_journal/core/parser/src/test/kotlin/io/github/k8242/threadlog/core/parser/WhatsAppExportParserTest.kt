// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class WhatsAppExportParserTest {

    private val userSample = """
        8/13/22, 2:11${'\u202F'}PM - Yutong: Dropping off... I'll be on and off next week as I try to catch up.  Feel free to chat me and I'll reply as I can as I pop on and off from the beach!  🏖️
        I'll be:
        0) finishing any pending CL reviews
        1) trying to get a proto CL in
        2) trying to get an executor CL in
        3) learn Go
        4) finishing up still open fixit CLs
        8/19/22, 6:26${'\u202F'}AM - Yutong: Valet 10695
        8/22/22, 3:43${'\u202F'}AM - Yutong: <Media omitted>
        8/22/22, 3:44${'\u202F'}AM - Yutong: Jim, Tarek, Tanoj, ...?
        8/26/22, 3:50${'\u202F'}AM - Yutong: <Media omitted>
        8/26/22, 10:07${'\u202F'}AM - Yutong: Please use the links below to retrieve your mobile boarding pass. Flight UA 1722 https://united.com/example
    """.trimIndent()

    @Test
    fun `parses exact user sample with narrow no-break space multiline body and Media omitted`() {
        val zone = "America/Los_Angeles"
        val result = WhatsAppExportParser.parse(
            rawText = userSample,
            zoneId = zone,
            dateOrder = WhatsAppDateOrder.MDY,
        )

        assertThat(result.dateOrderMismatchCount).isEqualTo(0)
        assertThat(result.messages).hasSize(6)

        val first = result.messages[0]
        assertThat(first.sender).isEqualTo("Yutong")
        assertThat(first.isMediaOmitted).isFalse()
        assertThat(first.isSystemMessage).isFalse()
        assertThat(first.messageBody).startsWith("Dropping off...")
        assertThat(first.messageBody).contains("0) finishing any pending CL reviews")
        assertThat(first.messageBody).endsWith("4) finishing up still open fixit CLs")
        assertThat(first.effectiveBody(includeSenderName = false)).startsWith("Dropping off...")
        assertThat(first.effectiveBody(includeSenderName = true)).startsWith("Yutong: Dropping off...")

        val expectedFirstMs = LocalDateTime.of(2022, 8, 13, 14, 11, 0)
            .atZone(ZoneId.of(zone))
            .toInstant()
            .toEpochMilli()
        assertThat(first.createdAtMs).isEqualTo(expectedFirstMs)
        assertThat(first.zoneId).isEqualTo(zone)

        assertThat(result.messages[1].messageBody).isEqualTo("Valet 10695")
        assertThat(result.messages[2].isMediaOmitted).isTrue()
        assertThat(result.messages[2].messageBody).isEqualTo("<Media omitted>")
        assertThat(result.messages[3].messageBody).isEqualTo("Jim, Tarek, Tanoj, ...?")
        assertThat(result.messages[4].isMediaOmitted).isTrue()
        assertThat(result.messages[5].messageBody).contains("https://united.com/example")
    }

    @Test
    fun `applies requested timezone accurately`() {
        val sample = "8/13/22, 2:11\u202FPM - Yutong: Beach note"
        val la = WhatsAppExportParser.parse(sample, zoneId = "America/Los_Angeles").messages.single()
        val honolulu = WhatsAppExportParser.parse(sample, zoneId = "Pacific/Honolulu").messages.single()
        val utc = WhatsAppExportParser.parse(sample, zoneId = "UTC").messages.single()

        // In August: LA is UTC-7, Honolulu is UTC-10 (3 hours behind LA, 10 hours behind UTC).
        assertThat(la.createdAtMs - utc.createdAtMs).isEqualTo(7 * 3_600_000L)
        assertThat(honolulu.createdAtMs - la.createdAtMs).isEqualTo(3 * 3_600_000L)
        assertThat(honolulu.zoneId).isEqualTo("Pacific/Honolulu")
    }

    @Test
    fun `supports DMY date order and flags mismatches under MDY`() {
        val dmySample = "26/08/22, 10:07 AM - Yutong: Boarding pass"

        val mdyResult = WhatsAppExportParser.parse(dmySample, zoneId = "UTC", dateOrder = WhatsAppDateOrder.MDY)
        assertThat(mdyResult.messages).isEmpty()
        assertThat(mdyResult.dateOrderMismatchCount).isEqualTo(1)

        val dmyResult = WhatsAppExportParser.parse(dmySample, zoneId = "UTC", dateOrder = WhatsAppDateOrder.DMY)
        assertThat(dmyResult.dateOrderMismatchCount).isEqualTo(0)
        assertThat(dmyResult.messages).hasSize(1)
        val zdt = Instant.ofEpochMilli(dmyResult.messages.single().createdAtMs).atZone(ZoneId.of("UTC"))
        assertThat(zdt.year).isEqualTo(2022)
        assertThat(zdt.monthValue).isEqualTo(8)
        assertThat(zdt.dayOfMonth).isEqualTo(26)
    }

    @Test
    fun `preserves ordering for same-minute messages detects system notices and parses iOS brackets`() {
        val sample = """
            8/13/22, 2:11 PM - Messages and calls are end-to-end encrypted.
            8/13/22, 2:11 PM - Yutong: First in minute
            8/13/22, 2:11 PM - Yutong: Second in minute
            [8/14/22, 9:05:12 AM] Yutong: iOS exported line
        """.trimIndent()

        val result = WhatsAppExportParser.parse(sample, zoneId = "UTC")
        assertThat(result.messages).hasSize(4)
        assertThat(result.messages[0].isSystemMessage).isTrue()
        assertThat(result.messages[0].sender).isNull()
        assertThat(result.messages[1].createdAtMs).isGreaterThan(result.messages[0].createdAtMs)
        assertThat(result.messages[2].createdAtMs).isGreaterThan(result.messages[1].createdAtMs)
        assertThat(result.messages[3].messageBody).isEqualTo("iOS exported line")
    }
}
