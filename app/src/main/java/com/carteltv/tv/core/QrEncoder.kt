package com.carteltv.tv.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.util.EnumMap

/**
 * QR encoding for pairing screens, backed by ZXing (the standard QR library):
 * spec-compliant codes at any version, UTF-8 payloads, ECC level M so a phone
 * can read the code off a TV screen. Returns the module matrix (true = dark),
 * without quiet zone — the composable adds that. Null only when ZXing cannot
 * encode the payload at all.
 */
object QrEncoder {
    fun encode(text: String): Array<BooleanArray>? =
        runCatching {
            if (text.isEmpty()) return null
            val hints =
                EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                    put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                    // An ECI segment trips up some phone decoders, so only ask for UTF-8 when
                    // the payload is not plain ISO-8859-1 (every pairing URL here is ASCII).
                    if (!Charsets.ISO_8859_1.newEncoder().canEncode(text)) {
                        put(EncodeHintType.CHARACTER_SET, "UTF-8")
                    }
                    put(EncodeHintType.MARGIN, 0)
                }
            val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix ?: return null
            Array(matrix.height) { r ->
                BooleanArray(matrix.width) { c -> matrix.get(c, r) == 1.toByte() }
            }
        }.getOrNull()
}
