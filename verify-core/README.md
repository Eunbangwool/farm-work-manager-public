# 농사진이 · 농작이 — 영농기록 사진 검증 핵심 코드 (공개)

영농기록 촬영 앱 **농사진이(농작이)** 로 촬영·제출된 사진이 **촬영 이후 위·변조되지 않았음**을,
그리고 그 사실을 **발급주체와 무관한 제3자(접수·점검 기관)** 가 직접 확인할 수 있음을 뒷받침하는
**검증 핵심 코드**를 투명성 목적으로 공개합니다.

> 이 폴더는 위·변조 방지·검증에 직접 관련된 부분만 발췌한 것입니다. 전체 앱 소스는 비공개입니다.

## 무결성 모델 (요약)

촬영 1장마다 원본의 **SHA-256 해시(디지털 지문)** 를 계산해, 서로 **독립된 3곳**에 동시에 고정합니다.

1. **사진 워터마크·QR** — 대상 필지 주소·좌표·표준시각·판정·검증코드·QR을 사진 위에 각인
2. **발급주체 서버 기록** — `photoProofs/{검증코드}` 문서에 해시·시각·좌표·판정을 1회 기록하며, **생성 후 수정·삭제 불가(불변)**
3. **외부 블록체인 앵커** — 해시를 **OpenTimestamps**(비트코인) 에 제출·고정. 발급주체조차 사후 변경 불가

검증은 이 **3자를 교차대조**하며, 셋이 모두 일치해야 진본으로 성립합니다(하나라도 다르면 조작 탐지).
시각은 단말 시계가 아닌 **인터넷 표준시각(NTP)** 을 사용하고, 위치는 **대상 필지 폴리곤과 대조(필지 내/외 판정)** 합니다.

## 파일 안내

### android/
| 파일 | 역할 |
|---|---|
| `PhotoStore.kt` | 원본 SHA-256 해시 계산, 워터마크·검증 QR 합성, 저장 |
| `PhotoProof.kt` | 비민감 메타(해시·시각·좌표·판정)를 `photoProofs/{code}` 에 불변 기록(공개 검증용) |
| `TrustedTime.kt` | 인터넷 표준시각(NTP) 획득 — 단말 시계 조작과 무관한 촬영시각 |
| `FieldPhoto.kt` | 사진 메타 모델 + EXIF 촬영시각과 NTP 시각 교차검증 |
| `GeoUtils.kt` | GPS 좌표와 대상 필지 경계(폴리곤) 대조 — 필지 내/외 판정 |

### functions/
| 파일 | 역할 |
|---|---|
| `ots.js` | OpenTimestamps 제출·업그레이드·검증 (독립 제3자 앵커) |

## `photoProofs` 불변성 (Firestore 보안 규칙 발췌)

```
match /photoProofs/{proofId} {
  allow get:    if request.auth != null;
  allow create: if request.auth != null;
  allow update, delete: if false;          // 생성 후 변경·삭제 금지(불변)

  match /anchors/{provider} {              // OTS/TSA 앵커 증명(append-only)
    allow get, list: if request.auth != null;
    allow create, update, delete: if false; // 클라 쓰기 금지, Cloud Functions(Admin)만 기록
  }
}
```

## 검증 방법 (누구나)

사진의 QR을 스캔하거나 워터마크의 **검증코드**(예: `A1B2-C3D4-E5F6`)를 아래에 입력하면
서버·앵커 기록과 대조됩니다.

- 신규 사진: <https://farm-work-manager-prod.web.app/verify>
- 기존 사진: <https://www.sangwolnongsan.com/verify>
- 원리 설명서(PDF): <https://farm-work-manager-prod.web.app/verify-guide.pdf>

## 표준·근거

- **SHA-256**: 미국 NIST 표준 FIPS 180-4
- **OpenTimestamps**: 공개 블록체인 기반 타임스탬프 <https://opentimestamps.org>
- 발급주체: 아그로이드 (문의: dbstjdwo28@gmail.com)
