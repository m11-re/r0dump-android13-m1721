package com.android.r0dumpmanager.ui

import java.math.BigDecimal
import java.math.RoundingMode

private val bytesPerMiB = BigDecimal(1024L * 1024L)
private val mibInputPattern = Regex("(?:\\d+(?:[.,]\\d{0,6})?|[.,]\\d{1,6})")

fun mibTextOf(bytes: Long): String = BigDecimal(bytes)
    .divide(bytesPerMiB, 6, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()

fun bytesFromMiBInput(raw: String): Long? {
    val normalized = raw.trim()
    if (!mibInputPattern.matches(normalized)) return null
    return runCatching {
        BigDecimal(normalized.replace(',', '.'))
            .multiply(bytesPerMiB)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}
