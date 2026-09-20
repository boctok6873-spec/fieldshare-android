# FieldShare 개인 자료 구현·검증 보고

작성일: 2026-09-08. 운영 배포, Google Cloud 설정 변경, 실제 Google 사용자 연결, 운영 자료 생성·수정·삭제는 수행하지 않았습니다.

## 구현 결과

### 이번 결함 수정

1. **등록 재생성**: URI만 저장하면서 `onDispose`에서 초안/카메라 파일을 삭제하던 수명을 수정했습니다. 계정별 UUID 초안과 AtomicFile 스냅샷으로 첨부·OCR 상태/텍스트를 함께 복원합니다. dispose는 사용 참조만 해제하고 성공/명시적 취소에서만 파일을 지웁니다. 누락 파일·중단 OCR은 재첨부 안내와 저장 차단으로 처리합니다. 공유 등록에도 동일하게 적용됩니다. 저장 실패에는 파일이 유지됩니다.
2. **최초 완료 오판**: checkpoint와 `initialSyncComplete`를 분리했습니다. 전체 목록과 변경 재생의 마지막 페이지가 모두 반영되어야 완료/lastSync를 갱신합니다. 부분 실패 시 원자 저장한 캐시/재개 토큰을 사용하며, 410은 완료를 해제하고 재동기화합니다. 완료 필드 없는 기존 캐시는 전체 재동기화하고 검색 캐시/이전 성공 시간을 유지합니다.
3. **과거 버전 재등장**: 메타데이터 삭제 시 revision을 제거하던 대신 계보와 MISSING/TRASHED 상태를 보존합니다. 새 저장은 스키마 2 메타데이터와 별도의 내용 없는 원격 계보 기록을 남겨 새 기기도 삭제된 최신 버전의 존재를 판별합니다. 살아 있는 동시 버전은 보존하며 외부 삭제만으로 첨부를 정리하지 않습니다. 스키마 1/증거 불일치는 복구 확인 상태로 표시하고 명시적 선택으로 새 버전을 생성합니다.

호환성 및 한계: 과거 완료 필드는 추정하지 않습니다. 기존 자료는 읽을 수 있지만 확인되지 않은 최신성은 정상으로 단정하지 않습니다. 메타데이터와 별도 계보까지 모두 영구 삭제되고 이를 본 캐시도 없다면 삭제 이력을 복원할 수 없습니다. `removed`는 영구 삭제 외에 접근권 상실도 의미합니다. 자세한 동작과 초안 정리 제한은 `GOOGLE_DRIVE_SETUP.md`에 기록했습니다.

- 홈 카테고리: 전체 → 내 자료 → 냉장고 → 기존 순서. 내 자료는 공유 카테고리 문자열로 저장하지 않는 전용 UI/저장소입니다.
- 내 정보: 기존 이름 수정 유지, Google Drive 연결·계정 선택·동기화·마지막 동기화·연결 해제 메뉴 추가.
- 등록: 공유/개인 저장 위치 선택, 진입 위치에 맞는 기본값, 텍스트·이미지·스캔 OCR 재사용, 개인 PDF 첨부.
- 개인 상세: 제목·본문 수정, URL 링크, 원본 첨부 열기, 휴지통 삭제, 실패 재시도, 명시적인 충돌 버전 선택.
- 개인 검색: 공백 정리, 대소문자 무시 부분 문자열 검색. 제목/본문/제품 카테고리/OCR/모델명/숫자/URL 지원. 공유 검색어와 개인 검색어는 카테고리 전환 시 분리합니다.
- 동기화: 전체 페이지 메타데이터 동기화, changes 증분 동기화, 변경 토큰 만료 복구, 오프라인 캐시 검색, 필요 시 원본 다운로드.
- 저장 안전성: 파일 ID 선발급과 영속 작업 기록, 첨부 완료 후 메타데이터 게시, 부분 업로드·삭제 재시도, 명시적 취소 시 고아 첨부 정리.
- 충돌: 불변 버전 메타데이터와 부모 버전 ID를 사용해 동시 수정을 보존합니다. Drive 서버 내부에서 버전별 첨부를 복사하므로 매 수정 시 전체 원본을 다운로드하지 않습니다. 삭제는 본문 없는 삭제 기록과 정확한 정리 대상 ID를 남깁니다.
- 계정 캐시: Google의 안정적인 OpenID subject를 기준으로 격리하며 SDK ID가 있으면 일치를 검증합니다. 정보는 기기 내에서만 사용합니다. noBackupFilesDir와 계정별 cacheDir를 사용합니다.
- 설정: 신규 기본값 ALL, 기존 RECENT_ONLY/HIDDEN의 최초 1회 ALL 전환, 설정값과 완료 표시의 동시 commit, 이후 사용자 선택 유지.

## 변경 파일

| 파일 | 역할 |
| --- | --- |
| `app/src/main/java/com/youngsu/fieldshare/MainActivity.kt` | 홈·내 정보·등록 경로 연결, 개인 검색의 공용 경로 차단, 저장 위치 UI |
| `app/src/main/java/com/youngsu/fieldshare/DriveConnectionRepository.kt` | Firebase와 독립된 권한·세션·계정 캐시·작업 직렬화 |
| `app/src/main/java/com/youngsu/fieldshare/DriveApi.kt` | 기기에서 Google API 직접 호출, drive.file 파일 처리 |
| `app/src/main/java/com/youngsu/fieldshare/PrivateDriveModels.kt` | 메타데이터 스키마, 계정 검증, 로컬 검색, 버전 판정 |
| `app/src/main/java/com/youngsu/fieldshare/PrivateDriveStore.kt` | AtomicFile 로컬 저장소, 폴더 발견, 페이지·증분 동기화 |
| `app/src/main/java/com/youngsu/fieldshare/PrivateDriveUploads.kt` | 재시도 가능한 업로드·복사 트랜잭션 |
| `app/src/main/java/com/youngsu/fieldshare/PrivateDriveUi.kt` | 연결 UI, 개인 목록·상세·수정·삭제·충돌 UI |
| `app/src/main/java/com/youngsu/fieldshare/PrivateRegistrationContext.kt` | 등록 임시파일을 계정별 캐시에 격리 |
| `app/src/main/java/com/youngsu/fieldshare/AppSettingsRepository.kt` | 1회 ALL 마이그레이션 |
| `app/src/main/java/com/youngsu/fieldshare/ImageOptimizer.kt` | 캐시 FileProvider 접근, 민감 경로가 포함될 수 있는 오류 로그 정리 |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | noBackup/cache 보관 정책 명시 |
| `app/build.gradle.kts` | AuthorizationClient, OkHttp, JSON 단위 테스트 의존성 |
| `app/src/test/java/com/youngsu/fieldshare/PrivateDriveTest.kt` | API 대역 기반 검색·동기화·업로드·충돌·설정 및 최신 버전 삭제 회귀 검사 16개 |
| `app/src/androidTest/java/com/youngsu/fieldshare/PrivateStorageInstrumentedTest.kt` | 영속성·계정 격리·FileProvider·동기화 후 연결 해제 검사 4개 |
| `app/src/androidTest/java/com/youngsu/fieldshare/PrivateDriveUiTest.kt` | 미연결 안내와 기존 URL 클릭 회귀 검사 2개 |
| `app/src/androidTest/java/com/youngsu/fieldshare/PrivateDriveRecoveryTest.kt` | 실제 AtomicFile 캐시·페이지별 실패·재시작·휴지통/영구 삭제·새 기기·계보 충돌 검사 6개 |
| `app/src/androidTest/java/com/youngsu/fieldshare/RegistrationLifecycleTest.kt` | 실제 등록 화면 recreate, 공유/개인 첨부·OCR·카메라 복원, 성공/실패/취소 및 정리 검사 5개 |
| `app/src/debug/AndroidManifest.xml`, `app/src/debug/java/com/youngsu/fieldshare/RegistrationLifecycleTestActivity.kt` | Firebase 시작/실계정 연결 없는 debug 전용 격리 테스트 Activity |
| `GOOGLE_DRIVE_SETUP.md` | Console 설정, 서명 지문, 저장·동기화 상세 및 제한 |

기존 `FirebaseDocumentRepository`, `UserProfileRepository`, `FirebasePresenceRepository`, `PushNotificationManager`, `OcrTextExtractor`, `LinkedDocumentText`, `functions` 소스는 변경하지 않았습니다. 기존 빌드 산출물 변경을 git restore/reset하지 않았으며 빌드로 생성된 산출물은 작업 트리에 남아 있습니다. 커밋하지 않았습니다.

## 실제 실행한 검증

| 검사 | 최종 결과 |
| --- | --- |
| `:app:testDebugUnitTest` | 15개 테스트 클래스, 총 63개 성공, 실패/오류 0 |
| `:app:connectedDebugAndroidTest` | SM-G973N / Android 12에서 총 18개 성공, 실패/오류/건너뜀 0 |
| `:app:lintDebug` | 오류 0, 경고 54, 힌트 6 |
| `:app:assembleDebug` | 성공 |
| `:app:compileDebugAndroidTestKotlin` | 성공, 기기 검사에서도 재컴파일 확인 |
| 소스 범위 `git diff --check` | 통과 |
| `:app:signingReport` | 성공, debug 인증서 SHA-1 문서화 |
| 금지 경로 정적 검사 | GoogleAuthProvider/linkWithCredential/signInWithCredential 없음. Drive/Private 구현에 Firebase·공용 검색·접속 상태·푸시·로그 호출 없음 |

최신 버전 삭제 회귀 테스트는 수정 전 과거 버전이 재등장하여 실패하는 것을 확인했습니다. 구현 중에는 OCR 상태 변경이 SideEffect 내부에서만 읽혀 초안에 저장되지 않는 결함도 생명주기 검사로 드러났습니다. 상태를 composition 단계에서 읽도록 수정 후 해당 검사가 통과했습니다. Compose 화면 미표시 문제는 debug 전용 테스트 Activity의 onCreate에서 showWhenLocked/turnScreenOn을 적용해 해결했습니다. 최종 기기 검사 18개가 모두 통과했으며 사용자 잠금 해제를 수행하지 않았습니다. URL 테스트는 LocalUriHandler 대역이며 실제 브라우저 네트워크 호출은 하지 않았습니다. Windows DEX 파일 잠금으로 중단된 빌드는 데몬 종료 후 단일 작업자로 재실행했습니다.

새 회귀 검사는 실제 파일을 삭제하고 실제 AtomicFile 캐시를 다시 열며, Drive 대역은 별도 메타데이터/계보 파일 목록·휴지통·영구 삭제·페이지별 요청 실패를 재현합니다. ActivityScenario.recreate로 공유/개인 등록의 첨부·스캔 URI·완료 OCR 텍스트·대기 카메라 파일 보존, 저장 실패 재시도, 성공/취소 정리, 누락 파일 안내를 확인했습니다. OCR 텍스트는 고정 fixture이므로 실제 카메라/스캐너 또는 ML Kit 실행 결과를 검증한 것은 아닙니다. OS 프로세스 강제 종료와 캐시 자동 회수는 실행하지 않았습니다.

lint가 발견한 debug 테스트 Activity의 API 27 화면 표시 호출에는 API 26 대체 경로를 추가했습니다. 실기기 검증 환경은 Android 12이며 API 26 기기 실행은 수행하지 않았습니다. 이번 수정으로 OAuth 범위나 Google Cloud 설정을 추가할 필요는 없습니다.

테스트 보고서:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`

## APK

절대 경로: `C:\Users\bocto\FieldShare_v1\app\build\outputs\apk\debug\app-debug.apk`

## 계정 분리 확인과 미검증

Drive 구현은 FirebaseAuth나 UserProfileRepository를 의존하거나 호출하지 않습니다. 공유 자료 작성자/사용자 이름/접속 상태/푸시 데이터 구조를 변경하지 않았습니다. Google 권한 요청은 AuthorizationClient에 한정되며 token은 Google API로만 전달합니다. 캐시 계정은 Firebase UID와 별개입니다.

테스트 대역으로 권한 계정 식별 불일치·범위 미동의 거부, 캐시 계정 격리, 이전 동기화 완료를 기다린 뒤 연결 해제, 원본 삭제 호출 없이 캐시·연결 정보 정리를 확인했습니다. 실제 Google 계정을 연결하지 않았으므로 **실제 연결 전후 Firebase UID·이름 보존, Google 계정 선택/동의, 토큰 갱신·외부 철회, 실제 Drive 생성/복사/동기화, 새 기기 재연결, ML Kit OCR 결과의 Drive 왕복은 미검증**입니다. 외부 API 성공을 실계정 성공으로 보고하지 않습니다.

Google Cloud Console에서는 Drive API 활성화, OAuth 동의 화면/테스트 사용자, Android 클라이언트의 패키지명 `com.youngsu.fieldshare`와 서명 SHA-1 등록이 필요합니다. Firebase Google 공급자, 웹 클라이언트 시크릿, 서비스 계정 키는 필요하지 않습니다. 전체 절차와 실제 debug SHA-1은 `GOOGLE_DRIVE_SETUP.md`를 확인하세요.

## 제한

- 최초 동시 연결의 폴더 생성에는 Drive의 원자적 유일성 제약이 없어 중복 폴더 생성 가능성이 있습니다. 이후 정렬된 기존 폴더 ID를 선택하고 모든 앱 메타데이터를 읽으며 원본 폴더를 자동 삭제하지 않습니다.
- 첨부는 파일당 25MB, 실패 시 전체 파일 재전송입니다. 청크 단위 전송 재개는 없습니다. 이전 버전 첨부 사본은 Drive 공간을 사용합니다.
- PDF 텍스트/OCR 자동 추출은 지원하지 않습니다. 이미지에는 기존 온디바이스 OCR을 사용합니다.
- 작업 기록이 남은 동안 재시도·취소·고아 파일 정리를 지원합니다. 앱 제거로 미완료 작업 기록까지 사라진 경우의 고아 첨부 자동 수거는 구현하지 않았습니다. 불명확한 저장 응답의 취소에도 서버 완료 여부 확인을 위한 네트워크가 필요합니다.
- 대용량 개인 메타데이터의 메모리/목록 성능과 실제 계정·다중 기기 장기 운용은 추가 검증이 필요합니다.
- 별도 암호화는 없습니다. 사용자 안내는 “개인 자료는 연결된 Google Drive에 저장됩니다.”입니다.
