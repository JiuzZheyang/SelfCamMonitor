package com.hpu.selfcammonitor.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/** 用 ZXing 生成二维码 PNG（纯本地，无需网络）。 */
object QrUtil {

    /** 生成二维码 PNG 字节；失败返回 null（例如文本过长） */
    fun png(text: String, size: Int = 512): ByteArray? {
        val bmp = bitmap(text, size) ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 生成二维码 Bitmap；失败返回 null */
    fun bitmap(text: String, size: Int = 512): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val dark = Color.BLACK
            val light = Color.WHITE
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) dark else light)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
