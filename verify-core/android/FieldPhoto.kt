package com.sangwolnongsan.farmwork.data

/**
 * 영농활동 기록 사진 (직불금 이행점검 대비 **보조 소명자료**).
 *
 * ⚠️ 공식 증빙이 아니며 직불금 지급·점검 통과를 보장하지 않는다. 실제 신청·심사·
 * 이행점검은 국립농산물품질관리원·농림사업정보시스템(Agrix)·읍면동 등 관계기관 절차에 따른다.
 *
 * 위변조 방지(소급 조작 차단)는 **자체 서버 없이** 유지:
 * - 앱 내장 카메라(CameraX)로만 촬영 (갤러리 불러오기 금지)
 * - 촬영 시점 NTP 신뢰시각 + GPS 좌표·정확도 + 원본 SHA-256 해시 기록
 * - 이미지는 앱 전용 내부 저장소에만 보관 (갤러리/공유 저장소에 원본 미노출)
 */
data class FieldPhoto(
    val id: String,
    /** 대상 필지 (FarmLand.id) */
    val fieldId: String,
    val lat: Double,
    val lng: Double,
    /** 측위 정확도 (m). Location.getAccuracy(). 음수면 미상. */
    val accuracyM: Float,
    /** 촬영 시각 (epoch millis). NTP 성공 시 NTP 시각, 실패 시 기기 시각. */
    val capturedAt: Long,
    /** 촬영 시각 출처 (신뢰도 표시). */
    val timeSource: TimeSource = TimeSource.DEVICE,
    /** 원본 이미지 SHA-256 (hex). 사후 변조 검증용. */
    val hashSha256: String,
    val verifyResult: PhotoVerifyResult = PhotoVerifyResult.UNKNOWN,
    /** 원본 이미지 로컬 URI (file://...). 사용자가 원본 보관을 끄면 비어있을 수 있음. */
    val originalUri: String,
    /** 워터마크 합성본 로컬 URI (file://...). */
    val watermarkedUri: String,
    /** 로컬 기준 백업/공유 상태 (서버 업로드 아님). */
    val backupState: BackupState = BackupState.NONE,
    /** 촬영자 uid (감사 추적용). */
    val capturedByUid: String = "",
    val capturedByName: String = "",
    /**
     * 서버(Firestore)가 기록한 시각 (epoch millis). 저장 시 serverTimestamp 로 채워지고
     * 동기화 후 read 시 반영. 0 = 아직 미동기화. 단말 시계와 무관한 신뢰 앵커.
     */
    val serverRecordedAt: Long = 0,
    /**
     * 원본 EXIF 촬영시각 (epoch millis, 단말 시계 기준). 0 = EXIF 시각 없음.
     * NTP capturedAt 과 교차검증 — 큰 차이는 시계 조작 의심 신호.
     */
    val exifDateTime: Long = 0,
    /**
     * 외부 TTP 앵커 상태 (OpenTimestamps Phase 1). 촬영 완료와 무관 — 기본 NONE.
     * 오프라인 촬영 후에도 큐로 재시도한다.
     */
    val anchorStatus: AnchorStatus = AnchorStatus.NONE,
    /** base64 인코딩된 OpenTimestamps .ots 증명 (stub 또는 완성본). */
    val otsProof: String = "",
    /** true 면 캘린더 stub 이 비트코인 확정본으로 업그레이드됨. */
    val otsUpgraded: Boolean = false,
    /** 앵커된 비트코인 블록 높이. 0 = 미확정. */
    val otsBtcBlock: Long = 0,
    /** 캘린더 제출 시각 (epoch millis). 0 = 없음. 촬영시각(capturedAt)과 별개. */
    val anchoredAt: Long = 0,
)

/** EXIF(단말) 촬영시각과 NTP capturedAt 교차검증 결과 라벨. 5분 이내면 일치. */
fun exifCheckLabel(photo: FieldPhoto): String = when {
    photo.exifDateTime == 0L -> "EXIF 시각 없음"
    kotlin.math.abs(photo.exifDateTime - photo.capturedAt) <= 5 * 60 * 1000 -> "EXIF 시각 일치"
    else -> "EXIF 시각 불일치(시계 의심)"
}

/**
 * 필지 영역 대비 촬영 위치 판정. 내부·주변 15m(+측위오차)는 INSIDE.
 * NEAR_BOUNDARY 는 구기록 호환용(신규 촬영은 사용하지 않음).
 */
enum class PhotoVerifyResult(val ko: String) {
    /** 등록 영역 내부, 또는 경계에서 (측위오차 + 15m) 이내 */
    INSIDE("필지 내 촬영"),
    /** 경계에서 (측위정확도 + 여유) 이내 */
    NEAR_BOUNDARY("경계 인접 (GPS 오차 가능)"),
    /** 그 외 */
    OUTSIDE("필지 외 촬영"),
    /** 영역 미등록 등으로 판정 불가 */
    UNKNOWN("판정 불가 (필지 경계 미등록)");
    val label: String get() = com.sangwolnongsan.farmwork.core.i18n.I18n.t("photoverify.$name", ko)
}

/** 촬영 시각 출처. NTP 신뢰시각이 시계 조작에 강함. */
enum class TimeSource(val ko: String) {
    /** 인터넷 표준시각(NTP) — 단말 시계 조작과 무관 */
    NTP("인터넷 표준시각(NTP)"),
    /** 단말 시각 — 시계 조작 가능, 신뢰도 낮음 */
    DEVICE("기기 시각");
    val label: String get() = com.sangwolnongsan.farmwork.core.i18n.I18n.t("timesource.$name", ko)
}

/**
 * 외부 독립 제3자(TTP) 해시 앵커 상태.
 * 촬영 성공과 분리 — 앵커 실패해도 기록은 유효.
 */
enum class AnchorStatus(val ko: String) {
    NONE("미요청"),
    PENDING("독립 인증 대기"),
    ANCHORED("독립 인증 완료"),
    FAILED("독립 인증 실패");
    val label: String get() = com.sangwolnongsan.farmwork.core.i18n.I18n.t("anchorstatus.$name", ko)
}

/** 로컬 기준 백업/공유 상태 (자체 서버 미경유). */
enum class BackupState {
    /** 단말 로컬에만 보관 */
    NONE,
    /** 카카오톡 등으로 공유함 (기록 시각 증거·백업) */
    SHARED_KAKAO,
    /** 사용자 본인 구글 드라이브 백업 */
    BACKED_UP_DRIVE,
}

/**
 * 영농일지 한 건. 영농기록 사진 1건 = 영농일지 항목 1건으로 자동 생성된다.
 * 필지·일시·좌표·판정결과·사진참조는 촬영 시 자동 채움.
 */
data class FarmLog(
    val id: String,
    val fieldId: String,
    val workType: WorkType,
    /** 귀속된 작업 id — 특정 작업에서 촬영한 영농기록. 작업무관(전역) 촬영이면 빈 문자열. */
    val workId: String = "",
    /** 귀속 작업명(표시용 스냅샷). 작업 삭제·이름변경과 무관하게 기록 시점 이름 보존. */
    val workName: String = "",
    /** 작업 일시 (epoch millis) — 보통 사진 촬영(NTP) 시각. */
    val dateTime: Long,
    /** 연계된 사진 (FieldPhoto.id). 사진 없는 수동 기록이면 null. */
    val photoId: String? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 동의 기록 한 건. 직불 면책·개인정보·위치·제3자제공 등 유형별로 분리 관리.
 * 동의 일시는 NTP 시각 우선. 문구/방침 버전이 바뀌면 재동의 요구.
 */
data class ConsentRecord(
    val version: Int,
    /** 동의 일시 (NTP 시각 우선). */
    val agreedAt: Long,
    val agreed: Boolean,
    val type: ConsentType = ConsentType.LIABILITY_DISCLAIMER,
)

/**
 * 동의 유형 (PIPA 분리 동의). 필수/선택 구분.
 * - 필수: 서비스 핵심(계정·농지·작업).
 * - 선택: 직불금 면책(사진기록), 위치정보 처리, 사진 소명 기록, 대행업자 제3자 제공.
 *   (직불금 면책은 사진 기능 진입 시 [FieldPhotoConsentScreen] 에서도 별도 확인.)
 */
enum class ConsentType(
    val title: String,
    val required: Boolean,
    val purpose: String,
) {
    PRIVACY_REQUIRED(
        "개인정보 수집·이용 (필수)", true,
        "계정 식별, 농지·작업 관리 등 서비스 핵심 기능 제공",
    ),
    LIABILITY_DISCLAIMER(
        "직불금 면책 동의 (선택·사진기록)", false,
        "영농기록 사진이 공식 증빙이 아닌 보조 소명자료임을 확인",
    ),
    LOCATION(
        "위치정보 처리 (선택)", false,
        "촬영 위치 기록 및 필지 영역 내부 판정",
    ),
    PHOTO_EVIDENCE(
        "사진 소명 기록 (선택)", false,
        "GPS 사진 촬영·영농일지 자동 기록",
    ),
    THIRD_PARTY_AGENT(
        "제3자 제공 — 영농대행업자 (선택)", false,
        "위탁한 영농대행업자에게 농지·작업 데이터 제공(관계 해제 시 철회·차단)",
    ),
}
