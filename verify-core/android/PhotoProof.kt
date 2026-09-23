package com.sangwolnongsan.farmwork.data.photo

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.sangwolnongsan.farmwork.BuildConfig
import com.sangwolnongsan.farmwork.data.FarmLand
import com.sangwolnongsan.farmwork.data.FieldPhoto
import com.sangwolnongsan.farmwork.data.FirebaseAvailability
import kotlinx.coroutines.tasks.await

/**
 * 영농기록 사진 공개 검증(소명) — top-level /photoProofs/{code} 에 비민감 메타만 1회 기록.
 *
 * 점검기관 등 제3자가 워터마크의 검증코드를 sangwolnongsan.com/verify 에 입력(또는 QR 스캔)하면
 * 발급주체(농작이) 서버에 같은 기록이 있는지 독립 대조 → 워터마크만으로 못 믿는 신뢰를 보강.
 * 원본 이미지는 저장하지 않고 해시·시각·좌표·판정만 보관. 문서는 불변(update/delete 불가).
 * 외부 앵커(OTS)는 하위 컬렉션 photoProofs/{code}/anchors/ots 에 append (Cloud Function).
 */
object PhotoProof {
    const val VERIFY_URL = "https://farm-work-manager-prod.web.app/verify"

    /** 검증코드 = 원본 SHA-256 앞 12자(대문자). photoProofs 문서 id 이자 사람이 대조하는 코드. */
    fun code(hashSha256: String): String = hashSha256.take(12).uppercase()

    /** 표시용 — 4자씩 묶음 (예: A1B2-C3D4-E5F6). */
    fun codeDisplay(hashSha256: String): String = code(hashSha256).chunked(4).joinToString("-")

    /**
     * best-effort 공개 기록. 실패해도 촬영/저장에는 영향 없음.
     * 같은 코드 문서가 이미 있으면(동일 원본) create 거부되며 무시.
     */
    suspend fun publish(photo: FieldPhoto, field: FarmLand?) {
        if (!FirebaseAvailability.isAvailable) return
        val data = hashMapOf(
            "hashSha256" to photo.hashSha256,
            "verifyCode" to code(photo.hashSha256),
            "capturedAt" to photo.capturedAt,
            "timeSource" to photo.timeSource.name,
            "lat" to photo.lat,
            "lng" to photo.lng,
            "accuracyM" to photo.accuracyM,
            "verdict" to photo.verifyResult.label,
            "exifDateTime" to photo.exifDateTime,
            "exifCheck" to com.sangwolnongsan.farmwork.data.exifCheckLabel(photo),
            "fieldAddress" to (field?.address ?: ""),
            "issuer" to "농작이",
            "appVersion" to BuildConfig.VERSION_NAME,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        try {
            Firebase.firestore.collection("photoProofs")
                .document(code(photo.hashSha256))
                .set(data)
                .await()
        } catch (e: Exception) {
            android.util.Log.w("PhotoProof", "publish 실패: ${e.message}")
        }
    }
}
