package dev.pbou.stt.data

import android.content.Context
import android.text.format.Formatter
import java.text.NumberFormat

object Format {
    /** "487 MB" in English, "487 Mo" in French. */
    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** One decimal, in the user's locale ("7.0" / "7,0"). */
    fun decimal(value: Double): String = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

    fun integer(value: Long): String = NumberFormat.getIntegerInstance().format(value)
}
