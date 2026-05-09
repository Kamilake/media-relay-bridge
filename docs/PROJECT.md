# Media Relay Bridge — 프로젝트 컨텍스트

> 한 줄 요약: **공유 인텐트로 받은 HEIC/HEIF/AVIF/HEVC 미디어를 즉석에서
> 레거시 형식(JPEG·PNG·H.264 MP4)으로 변환해 다시 공유 인텐트로 던져주는
> 백엔드성 안드로이드 앱.**

## 1. 왜 만드는가

안드로이드/iOS의 표준 사진·동영상 포맷이 HEIC/HEIF/HEVC로 옮겨갔지만,
일부 안드로이드 앱들은 이 포맷을 받으면 깨지거나 크래시함:

- **Discord** — HEIC를 공유 인텐트로 받으면 강제 종료.
- **여러 레거시 정부/공공 앱** — 같은 증상.

이 앱은 그 사이를 가운데서 메우는 **포맷 어댑터**다. 사용자는 갤러리에서
HEIC를 공유 → 앱 선택 시 "Media Relay Bridge" → 자동 변환 → 시스템 공유 시트가
다시 떠서 진짜 원하는 앱(Discord 등)에 JPEG로 전달.

UI라고 부를 게 거의 없는 **paste-through 도구**. 메인 액티비티는
"공유로 호출하세요" 라는 안내 문구 한 장. 본체는 `ShareReceiverActivity`.

니즈는 작성자 1인이지만 코드는 오픈소스로 공개 예정.

## 2. 무엇을 하지 않는가 (스코프 경계)

- 원본 파일을 영구 저장하지 않음 — 변환 결과는 `cacheDir/converted/`에 만들고
  공유 후 OS가 회수하도록 둠.
- 사용자가 포맷·품질을 고르지 않음 — 자동 결정.
  - 알파 채널 있는 이미지 → PNG
  - 그 외 이미지 → JPEG-95
  - 동영상 → H.264 + AAC (MP4, 1280×720 cap, 6 Mbps)
- 클라우드 업로드, 편집, EXIF 편집 같은 부가 기능 없음.
- 갤러리 브라우저, 미디어 라이브러리 인덱싱 없음.

## 3. 사용자 흐름

```
[갤러리 / 파일 앱]
        │  (Share)
        ▼
android.intent.action.SEND / SEND_MULTIPLE
        │
        ▼
ShareReceiverActivity
   ├─ MediaType.detect()        ← MIME / 확장자 / ftyp brand 합산 분류
   ├─ ImageConverter.convert()  ← 4단계 디코더 폴백
   │     or
   │  VideoConverter.convert()  ← otaliastudios Transcoder
   ├─ FileProvider URI 발급
   └─ Intent.createChooser()    ← 결과를 다시 공유
```

다중 항목(예: 25장)이 들어오면 `Semaphore(2)`로 동시성 제한하며 병렬 처리.
한 항목이 실패해도 다른 항목은 계속 진행 (per-item try/catch).

## 4. 프로젝트 구조

```
MediaRelayBridge/
├── build.gradle.kts              # 루트 빌드 스크립트
├── settings.gradle.kts
├── gradle/
│   ├── libs.versions.toml        # 버전 카탈로그 (단일 진실의 원천)
│   └── wrapper/                  # gradle-wrapper
├── docs/
│   ├── PROJECT.md                # ← 지금 이 문서
│   └── worklog-2026-05-08.md     # 작업 일지 (8K HEIC 안정화)
├── diag/                         # ★ VCS 제외 (.gitignore)
│   ├── dump-isobmff.ps1          # ISO BMFF 박스 트리 덤퍼
│   └── sample.heic               # 분석용 샘플
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   # 공유 인텐트 필터, FileProvider
        ├── res/
        │   ├── values/strings.xml
        │   ├── values-ko/strings.xml
        │   └── xml/file_paths.xml
        └── java/com/kamilake/mediaconverter/
            ├── MainActivity.kt              # 안내문 한 장 (Compose)
            ├── ShareReceiverActivity.kt     # 본체 — 인텐트 진입점
            ├── ui/theme/                    # Compose 테마
            └── convert/
                ├── MediaType.kt             # MIME/확장자/ftyp 합산 분류
                ├── ConversionResult.kt      # sealed: Success/Skipped/Failed
                ├── ImageConverter.kt        # 4단계 폴백 디코더 → JPEG/PNG
                ├── VideoConverter.kt        # Transcoder → H.264/AAC MP4
                ├── HeifParser.kt            # 자체 ISO BMFF/HEIF 파서
                ├── HevcStillDecoder.kt      # MediaCodec 직접 구동
                └── CodecDiagnostics.kt      # debug 빌드 전용 카탈로그 로그
```

## 5. 모듈별 책임

### 5-1. `ShareReceiverActivity` — 진입점

- `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE`을 받아 `Uri` 목록 추출.
- 진행률 다이얼로그(Compose `AlertDialog` + `LinearProgressIndicator`)를 띄움.
- `lifecycleScope`에서 `coroutineScope { ... awaitAll() }`로 병렬 변환.
- 동시성: `min(2, cpu/2)` — 8K 디코더는 무거우므로 보수적.
- 실패는 항목 단위로 격리 (`try/catch`). 형제 코루틴을 안 죽임.
- 모두 끝나면 `Intent.createChooser()`로 재공유, 본 액티비티는 `finish()`.
- `excludeFromRecents` + `noHistory` + 반투명 테마 → 사용자 입장에서는
  거의 보이지 않는 통로.

### 5-2. `MediaType` — 분류기

MIME 신뢰가 어려움(특히 HEIC가 `image/jpeg`로 위장되는 사례). 그래서:

1. `ContentResolver.getType()` 으로 MIME 조회
2. URI 마지막 세그먼트 확장자 확인
3. 위 둘로 결정 못 하면 **파일 머리 12바이트 읽어 ftyp brand** (`heic`,
   `mif1`, `avif`, `hevc`, …) 식별

세 신호를 합쳐 `MediaKind.IMAGE / VIDEO / UNKNOWN` 결정.

### 5-3. `ImageConverter` — 4단계 폴백

```
1. ImageDecoder (default)
2. ImageDecoder (ALLOCATOR_SOFTWARE)
3. HeifParser → HevcStillDecoder    ← 시스템이 거부한 HEIC 우회
4. BitmapFactory                    ← plain JPEG/PNG 안전망
```

알파 채널이 있고 실제 투명 픽셀이 샘플링에서 잡히면 PNG, 아니면 JPEG-95.

### 5-4. `HeifParser` — 자체 ISO BMFF/HEIF 파서

외부 mp4 라이브러리를 끌어오지 않기 위해 필요한 박스만 직접 파싱:

| 박스 | 용도 |
|---|---|
| `ftyp` | 컨테이너 검증 |
| `meta/pitm` | primary item id |
| `meta/iinf/infe` | itemId ↔ itemType (`hvc1`, `grid`, ...) |
| `meta/iloc` | itemId의 파일 내 offset/length (v0/1/2 모두) |
| `meta/iprp/ipco` | property 배열 (`ispe`, `hvcC`) |
| `meta/iprp/ipma` | itemId → property index |
| `meta/iref/dimg` | grid → tile id 매핑 |

출력은 sealed type `PrimaryItem.HevcStill | HevcGrid | Unsupported`.
`HevcGrid` 분기는 파싱까지만 됨 — 디코드는 TODO.

### 5-5. `HevcStillDecoder` — MediaCodec 직접 구동

HEIF 안의 HEVC NAL은 **length-prefixed** 형식이라 MediaCodec에 그대로
못 넣음. 두 단계 변환 후 디코드:

1. `hvcC` → CSD-0 (VPS/SPS/PPS를 Annex-B start code로)
2. payload → Annex-B (lengthSize는 `hvcC[21] & 0x3 + 1`)
3. `MediaCodecList`에서 8K가 fit하는 디코더 선택 (HW 우선)
4. `ImageReader(YUV_420_888)` 출력 → BT.601 limited-range로 ARGB_8888

### 5-6. `VideoConverter` — Transcoder 위임

`com.otaliastudios:transcoder`가 MediaCodec 파이프라인을 처리:

- 비디오: H.264, ≤ 1280×720, 30fps, 6Mbps, GOP 3s
- 오디오: AAC, 2ch, 44.1kHz, 128kbps
- `suspendCancellableCoroutine`으로 코루틴 친화 래핑

### 5-7. `CodecDiagnostics` — 진단 로그

`BuildConfig.DEBUG`일 때만 `MediaCodecList`를 순회해 디바이스의 모든 HEVC
디코더 정보(SW/HW, 해상도 범위, 8K 지원 여부, profile/level/colorFormats)를
logcat에 덤프. release에서는 R8이 dead-code로 제거.

## 6. 의존성 (gradle/libs.versions.toml)

| 카테고리 | 라이브러리 |
|---|---|
| 빌드 | AGP 9.2.1, Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2026.02.01), Material3, Activity-Compose 1.8.0 |
| 비동기 | kotlinx-coroutines-android 1.10.2 |
| 미디어 | otaliastudios `transcoder` 0.11.2 |
| 기타 | androidx core-ktx 1.10.1, lifecycle 2.6.1 |

`compileSdk = 36 (minor 1)`, `targetSdk = 36`, **`minSdk = 35`**.
(현행 OS에서만 돌리는 개인 도구라 호환성 부담 없음. `largeHeap=true`.)

## 7. 빌드/실행

```powershell
# 빌드
.\gradlew.bat assembleDebug
# 또는 release
.\gradlew.bat assembleRelease

# 설치
.\gradlew.bat installDebug
```

테스트:
- 안드로이드 갤러리 → HEIC 한 장(또는 25장 일괄) 선택 → 공유 →
  "Media Relay Bridge".
- logcat 필터:
  `tag:ShareReceiver tag:ImageConverter tag:HevcStill tag:HeifParser`.

## 8. AndroidManifest 핵심

- `MainActivity` — 런처 아이콘(안내문 표시).
- `ShareReceiverActivity` — `SEND` / `SEND_MULTIPLE` 인텐트 필터로
  HEIC/HEIF/AVIF/HEVC MIME 등록. 반투명 테마, recents/history에서 제외.
- `FileProvider` — `${applicationId}.fileprovider` 권한으로 변환된
  캐시 파일을 다른 앱에 안전하게 노출.
- `android:largeHeap="true"` — 8K 비트맵 ≈ 132MB 처리를 위함.

## 9. 디자인 결정 메모

| 결정 | 이유 |
|---|---|
| 외부 mp4 라이브러리 안 씀 | 우리가 읽는 HEIF 박스는 작은 부분집합. 의존성 부풀리기 회피. |
| GPU/EGL 디코드 안 씀 | 이미지 1장만 뽑으면 되므로 `ImageReader(YUV)` 가 단순+예측 가능. |
| BT.601 고정 | HEIC `colr` 박스 파싱은 2차 작업. 안전한 기본값으로 시작. |
| 동시성 = 2 | 8K HEVC 인스턴스가 무거워서 HW 풀이 작음. 25장에서 검증된 안정값. |
| 실패는 격리 | 한 장 실패가 24장을 죽이면 사용자 경험 최악. |
| 진단은 debug 한정 | release에서 logcat 노이즈 제거, 다이어그노스틱 코드는 R8 제거. |

## 10. 알려진 제약 / TODO

- [ ] **HEIC 그리드 디코드**: iPhone 기본 사진은 보통 grid + 다중 hvc1 타일.
      `HeifParser.HevcGrid` 파싱은 됨. 타일별 디코드 + 합성 필요.
- [ ] **EXIF 보존**: 회전/방향이 빠진 채 저장됨.
- [ ] **컬러 매트릭스 정밀화**: BT.709 / BT.2020 / full-range 인지.
- [ ] **YUV→ARGB 가속**: 8K에서 CPU 100MB+ 픽셀 루프. GPU shader 또는
      libyuv NDK 바인딩 검토.
- [ ] **README.md / LICENSE**: 오픈소스 공개 전 작성.
- [ ] **CI**: Gradle build/lint를 GitHub Actions로.

## 11. 새 작업자를 위한 시작점

1. `ShareReceiverActivity.runConversions()` 부터 읽으면 전체 흐름이
   가장 빨리 잡힘.
2. 그 다음 `convert/MediaType.kt` → `convert/ImageConverter.kt` →
   `convert/HeifParser.kt` → `convert/HevcStillDecoder.kt` 순서.
3. HEIC 분석이 필요하면 `pwsh diag/dump-isobmff.ps1 your-file.heic`.
4. 디바이스 디코더 카탈로그가 궁금하면 debug 빌드 설치 후
   `adb logcat -s CodecDiag`.
5. 변경 후에는 25장 일괄 공유 시나리오로 회귀 확인.

## 12. 라이선스

[MIT License](../LICENSE).

---

_Last updated 2026-05-08._
