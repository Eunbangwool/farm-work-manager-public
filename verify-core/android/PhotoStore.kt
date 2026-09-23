package com.sangwolnongsan.farmwork.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 검증 사진 로컬 저장 + 무결성 해시 + 워터마크 합성.
 *
 * 이미지는 단말 로컬 (`getExternalFilesDir("field_photos")`) 에 보관 (MVP — 서버 업로드는 후속).
 * 원본 + 워터마크본 모두 저장, 메타데이터는 Firestore (FieldPhoto) 로 별도 동기화.
 */
object PhotoStore {

    /**
     * 앱 전용 내부 저장소(filesDir). 갤러리/공유 저장소에 원본을 노출하지 않아 사후 편집 차단.
     * 공유는 FileProvider(files-path) 경유로만 일시 권한 부여.
     */
    private fun dir(context: Context): File =
        File(context.filesDir, "field_photos").apply { mkdirs() }

    /** 원본 바이트의 SHA-256 (hex 소문자). 사후 변조 검증용. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 원본 JPEG 바이트를 로컬에 **암호화(EncryptedFile, AES256-GCM)** 저장.
     * 원본은 증거 보관용으로만 두고 화면 표시/공유에 쓰지 않으므로 복호화 경로가 필요 없다.
     * 키스토어 불가 등으로 암호화 실패 시 평문 fallback (촬영 자체는 절대 깨지지 않게).
     */
    fun saveOriginal(context: Context, photoId: String, bytes: ByteArray): File {
        val f = File(dir(context), "${photoId}_original.jpg")
        if (f.exists()) f.delete()  // EncryptedFile.openFileOutput 은 기존 파일이 있으면 throw
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedFile.Builder(
                context, f, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            encrypted.openFileOutput().use { it.write(bytes) }
            f
        } catch (e: Exception) {
            android.util.Log.w("PhotoStore", "원본 암호화 실패 → 평문 저장: ${e.message}")
            FileOutputStream(f).use { it.write(bytes) }
            f
        }
    }

    /**
     * 원본 바이트 → EXIF 회전 보정 비트맵 디코딩.
     * 큰 이미지로 인한 OOM 방지: 최대 변 2048px 로 다운샘플.
     */
    fun decodeUpright(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxEdge / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("이미지 디코딩 실패")
        val orientation = try {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return raw
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    /**
     * 이미지 하단에 정보 띠를 합성. [lines] 는 굵게/일반 순으로 그려진다 (최대 4줄 권장).
     * 반환 비트맵은 원본 크기 그대로 + 하단 띠가 이미지 위에 오버레이.
     */
    fun composeWatermark(src: Bitmap, lines: List<String>, qr: Bitmap? = null): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        // 텍스트 크기를 이미지 폭에 비례 (가독성).
        val textSize = (w / 28f).coerceIn(20f, 60f)
        val pad = textSize * 0.5f
        val lineH = textSize * 1.35f
        val bandH = lineH * lines.size + pad * 2
        val top = out.height - bandH

        val bgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        canvas.drawRect(0f, top, w, out.height.toFloat(), bgPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        var y = top + pad + textSize
        lines.forEach { line ->
            canvas.drawText(line, pad, y, textPaint)
            y += lineH
        }
        // 검증 QR — 띠 바로 위 우측. 흰 배경으로 스캔 안정성 확보.
        qr?.let { q ->
            val qrSize = (w * 0.16f).coerceIn(120f, 360f)
            val left = w - qrSize - pad
            val topY = (top - qrSize - pad).coerceAtLeast(pad)
            val bgPad = qrSize * 0.06f
            canvas.drawRect(left - bgPad, topY - bgPad, left + qrSize + bgPad, topY + qrSize + bgPad,
                Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(q, null,
                android.graphics.RectF(left, topY, left + qrSize, topY + qrSize), null)
        }
        return out
    }

    /** 검증 URL 등을 QR 비트맵으로. 실패 시 null (QR 없이 진행). */
    fun makeQr(text: String, sizePx: Int = 480): Bitmap? = try {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until sizePx) for (y in 0 until sizePx) {
                setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("PhotoStore", "QR 생성 실패: ${e.message}"); null
    }

    /** 워터마크 비트맵을 JPEG 로 로컬 저장. */
    fun saveWatermarked(context: Context, photoId: String, bitmap: Bitmap): File {
        val f = File(dir(context), "${photoId}_wm.jpg")
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    /** FieldPhoto 의 originalUri/watermarkedUri (file://...) 에서 File 로 환원 후 삭제. */
    fun deleteFiles(vararg uris: String) {
        uris.forEach { uri ->
            try {
                val path = android.net.Uri.parse(uri).path ?: return@forEach
                File(path).takeIf { it.exists() }?.delete()
            } catch (_: Exception) { /* 무시 */ }
        }
    }
}
