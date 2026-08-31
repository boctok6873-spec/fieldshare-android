# Firebase 설정

1. Firebase Console에서 Android 앱 패키지 이름 `com.youngsu.fieldshare`를 등록하고, 내려받은 `google-services.json`을 `app/google-services.json`에 둡니다. 이 파일은 소스나 로그에 넣지 않습니다.
2. Authentication에서 **익명 로그인** 제공업체를 사용 설정합니다.
3. Firestore Database, Storage, **Realtime Database**, **Cloud Messaging**을 생성·사용 설정합니다. `firestore.rules`, `storage.rules`, `database.rules.json`의 규칙을 각 Firebase Console Rules 탭에 붙여 넣어 **Publish**합니다. 로컬 파일만 추가하는 것으로는 규칙이 배포되지 않습니다. 자료 수정·삭제 권한을 변경한 뒤에도 변경된 `firestore.rules`와 `storage.rules`를 반드시 Publish해야 합니다.
4. 실제 기기에서 앱을 실행해 익명 사용자가 생성된 뒤 표시 이름을 등록할 수 있는지, 등록 완료 후에만 자료와 공유 현황에 접근할 수 있는지, 등록한 파일이 `documents/{documentId}/images|pdf`에 저장되는지 확인합니다.

## 데이터 구조

- Firestore: `users/{uid}` (`displayName`, `createdAt`, `updatedAt`). 표시 이름은 앞뒤 공백을 제거한 1~20자 값이며, 프로필 등록이 완료된 인증 사용자만 일반 기능을 사용할 수 있습니다.
- Firestore: `documents/{documentId}`
- 문서 필드: `id`, `title`, `category`, `createdAt`, `updatedAt`, `createdBy`, `createdByName`, `source`, `content`, `description`, `imagePaths`, `pdfPath`, `ocrStatus`, `searchableText`, `pendingDeletion`, `deletedAt`. `createdByName`은 자료 등록 당시 표시 이름 스냅샷입니다. 홈 설정의 “모든 자료 불러오기”(ALL)는 `createdAt` 조건·정렬·제한 없이 `documents` 전체를 수신하고 앱 내부 정렬을 사용하므로, 기존 `createdAt` 없는 자료도 표시합니다. 기본 “최근 등록 자료만 표시”(RECENT_ONLY)는 카테고리와 10/5개 제한만 Firestore에 적용합니다.
- Storage: `documents/{documentId}/images/{fileName}`, `documents/{documentId}/pdf/{fileName}`. 각 객체에는 등록자 UID를 나타내는 `ownerUid` custom metadata가 저장됩니다. 문서 스캔 자료는 PDF를 생성·업로드하지 않고, 각 페이지를 최적화된 이미지로 `images/` 경로에 저장합니다.
- Storage 경로는 Firestore에 저장하고, 앱은 표시할 때만 다운로드 URL로 변환합니다. 날짜는 Firestore의 서버 타임스탬프가 기준이며 화면 표시 변환은 앱에서 분리합니다.
- 삭제는 먼저 `pendingDeletion=true` 및 `deletedAt`을 기록하고 Storage 객체를 삭제한 뒤 Firestore 문서를 제거합니다. 실패한 문서는 앱의 수동 재시도 화면에 남습니다. `cleanupPendingDeletions` Scheduled Function은 **`deletedAt`이 7일 이상 지난** 문서만 하루 한 번(03:17 KST, 한 번에 최대 100개) 처리합니다. 최대 50건은 재시도 시각이 지난 실패 자료, 나머지는 문서 ID cursor 순회 자료에 배정해 오래된 실패 자료가 이후 자료를 막지 못하게 합니다. 실패 시 `deletionFailureCount`와 `deletionNextRetryAt`을 기록하며 다음 시도는 1일, 2일, 4일, 최대 7일 뒤입니다. 기존에 이 필드가 없는 자료도 cursor 순회로 처리합니다. 같은 문서의 Storage 경로(`documents/{documentId}/images|pdf/...`)만 삭제하고, 이미 없는 객체는 성공으로 취급합니다. 자동 처리 중 문서의 `deletedAt` 또는 재시도 시각이 바뀌면(수동 재시도 등) Firestore 문서 삭제를 건너뜁니다.
- 활동 로그: Firestore `activities/{activityId}`. 문서 생성·텍스트 수정·삭제 완료를 `CREATED`, `UPDATED`, `DELETED` 불변 기록으로 저장하며, `actorUid`와 당시의 `actorLabel`(표시 이름 스냅샷)을 보관합니다. 공유현황의 최근 활동 및 전체 보기는 `createdAt >= 현재 시각 - 30일`, `createdAt` 최신순 Firestore 쿼리만 사용하므로 `createdAt`이 없는 활동은 안전하게 제외됩니다. 이 단일 필드 범위/정렬에는 추가 복합 인덱스가 필요하지 않습니다.
- Presence: Realtime Database `presence/{uid}/{installationId}`. 설치별 무작위 ID와 온라인 여부, 서버 시각, 최소 기기 유형만 저장하며 하드웨어 식별자·전화번호·광고 ID는 수집하지 않습니다.
- 알림 수신 기기: Firestore `users/{uid}/devices/{installationId}`. 사용자가 홈의 종 아이콘으로 알림을 켠 기기만 FCM 토큰을 저장하며, 끄면 문서와 로컬 FCM 토큰을 정리합니다. Android 13 이상에서는 `POST_NOTIFICATIONS` 권한도 있어야 하며, 토큰 갱신 시에도 opt-in·권한·로그인 UID가 모두 있을 때만 즉시 갱신됩니다. `fieldshare_notifications` SharedPreferences(알림 opt-in, 설치 ID, 중복 전달 방지 ID)는 Android Auto Backup 및 기기 전송에서 제외되므로 새 설치·복원 기기의 기본값은 OFF입니다. 새 자료 등록 시 등록자 본인을 제외한 수신 기기에 푸시 알림을 보냅니다.

## 보안 및 운영

- `google-services.json`, API 키, 서비스 계정 키는 저장소·로그·문서 출력에 포함하지 않습니다. `.gitignore`의 `/app/google-services.json` 항목을 유지합니다.
- `users/{uid}`는 본인만 읽기·생성·수정할 수 있습니다. 공용 자료와 활동 로그는 유효한 표시 이름 프로필이 있는 인증 사용자만 읽고 쓸 수 있습니다. 자료 생성 시에만 `createdBy`와 `createdByName`을 현재 사용자 정보로 기록하고, 이후 모든 등록 완료 사용자가 자료를 수정·삭제할 수 있습니다. 최초 등록자 필드는 변경할 수 없으며, 활동 생성은 실제 작업자의 현재 UID 및 프로필 표시 이름과 일치해야 합니다. Storage 접근도 프로필 등록 완료 사용자로 제한하고 기존 `ownerUid` 메타데이터는 변경할 수 없게 유지합니다. 업로드는 20MB 이하의 `image/*` 또는 `application/pdf`만 허용합니다. `firestore.rules`와 `storage.rules`를 수정한 뒤에는 반드시 Firebase Console Rules 탭에서 각각 **Publish**합니다.
- Firebase Console에서 App Check를 설정하고 Android 제공업체로 **Play Integrity**를 사용 설정한 뒤, 충분한 테스트 기간을 거쳐 강제를 적용합니다.
- Google Cloud/Firebase Billing에서 예산 및 예산 알림을 설정해 Storage·Firestore 사용량 급증을 감시합니다.
- Realtime Database Rules에는 인증된 사용자의 Presence 읽기와 본인 UID 노드 쓰기만 허용합니다. 앱이 백그라운드로 전환되면 offline으로 갱신하고, 비정상 종료·네트워크 단절은 `.info/connected`와 `onDisconnect()`로 정리됩니다. 단절 감지는 네트워크 상태에 따라 약간 지연될 수 있습니다.

## 현재 공유 범위

현재 익명 로그인은 설치/사용자별 UID를 만들지만, 자료는 공용 `documents` 컬렉션과 Storage 경로에 저장됩니다. 따라서 프로필 등록을 완료한 모든 사용자는 다른 사용자가 등록한 자료를 목록·검색·상세·이미지에서 조회·수정·삭제할 수 있습니다. 자료의 `createdBy`와 `createdByName`은 최초 등록자 표시용으로 보존됩니다.

기존 `users/{uid}/documents` 및 `users/{uid}/documents/...` 데이터는 자동으로 이전하거나 삭제하지 않습니다. 공용 구조로 사용하려면 별도의 마이그레이션 작업이 필요합니다.

## Algolia 서버 검색 배포 순서

검색은 클라이언트 필터가 아니라 Firebase Cloud Functions 2세대와 Algolia를 사용합니다. Android 앱과 저장소에는 Algolia 키를 넣지 않습니다.

1. Firebase CLI에서 대상 프로젝트를 선택합니다: `firebase use <project-id>`.
2. 다음 Secret 값을 대화형으로 입력합니다. 값은 콘솔·저장소·앱 코드에 기록하지 않습니다.

   ```bash
   firebase functions:secrets:set ALGOLIA_APP_ID
   firebase functions:secrets:set ALGOLIA_SERVER_API_KEY
   firebase functions:secrets:set ALGOLIA_INDEX_NAME
   ```

   `ALGOLIA_INDEX_NAME`에는 `fieldshare_documents`를 입력합니다. 서버 API 키는 이 인덱스에 한정해 `search`, `addObject`, `deleteObject`, `editSettings` 권한을 가져야 합니다. Android용 Search-only API Key와 모든 인덱스에 광범위한 권한을 주는 Admin API Key는 사용하지 않습니다.
3. Functions 의존성을 설치하고 로컬 런타임 테스트를 통과시킨 뒤, Functions와 Firestore 복합 인덱스를 배포합니다. 이 배포에는 새 자료 푸시 알림 함수와 `cleanupPendingDeletions` Scheduled Function이 포함됩니다. `notifyNewDocument`는 540초 timeout으로 실행됩니다. `server-unavailable`·`internal-error`·`unknown-error`인 **실패 토큰만** 최소 10초부터 지수 backoff와 jitter로 재시도합니다. `quota-exceeded`, `device-message-rate-exceeded`, `message-rate-exceeded`도 **실패 토큰만** 최소 60초부터 지수 backoff와 jitter로 재시도하며, 로컬 시도 소진 시에만 Eventarc 재시도로 넘깁니다. 이벤트는 at-least-once이므로 같은 `deliveryId`가 다시 전송될 수 있으며 Android는 이미 표시한 delivery ID를 억제합니다. 등록 해제·무효 토큰은 Firestore 기기 문서에서 제거됩니다.

   ```bash
   cd functions
   npm install
   npm test
   cd ..
   firebase deploy --only functions,firestore:indexes
   ```

4. 기존 자료를 색인하려면, 서비스 계정 JSON을 안전한 로컬 경로에만 두고 저장소에 넣은 뒤 로컬 재색인 스크립트를 실행합니다. 이 스크립트는 Firebase Secret을 읽지 않으므로 동일한 Algolia 값을 현재 셸 환경 변수로만 설정해야 합니다.

   ```bash
   # PowerShell 예시: 환경 변수 값과 UID는 화면 공유·로그에 남기지 마세요.
   $env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\secure\service-account.json'
   $env:ALGOLIA_APP_ID = '<app-id>'
   $env:ALGOLIA_SERVER_API_KEY = '<server-api-key>'
   $env:ALGOLIA_INDEX_NAME = 'fieldshare_documents'
   node functions/scripts/reindex-all-documents.js
   ```

   `reindexAllDocuments` Callable도 유지됩니다. 이를 사용할 관리자에게만 별도 로컬 Claim 스크립트로 권한을 부여합니다.

   ```bash
   node functions/scripts/set-admin-claim.js <firebase-auth-uid>
   ```

   UID는 로그·저장소에 남기지 않으며, 대상 사용자는 다시 로그인해 ID 토큰을 새로 받아야 합니다. 관리자 이외 사용자의 Callable 재색인 요청은 `permission-denied`로 거부됩니다.
5. 실기기에서 등록 완료 사용자로 앱을 실행하고, 두 글자 이상 검색어를 입력합니다. 로딩 후 Algolia 결과가 표시되는지, 검색 목록에서 Storage 다운로드 URL·이미지 썸네일을 자동 요청하지 않는지, 상세 진입 후에만 이미지가 로드되는지, 검색어를 지우면 홈 설정별 목록/숨김 안내로 돌아오는지 확인합니다.
6. 홈 설정의 “모든 자료 불러오기”에서 등록일과 관계없이 모든 자료(기존 `createdAt` 없는 자료 포함)가 표시되고, 기본 “최근 등록 자료만 표시”에서는 카테고리별 10/5개 제한이 유지되는지 확인합니다. 공유현황의 최근 활동과 전체 보기는 최근 30일 자료만 최신순으로 보이고 `createdAt` 없는 활동은 제외되는지, 카드와 상세 화면의 등록일이 `yyyy.MM.dd`로 표시되는지 확인합니다. 활동 조회는 Firestore 복합 인덱스를 추가하지 않습니다.

## 알림·자동 삭제 배포 및 운영

1. Firebase CLI에서 최신 보안 규칙과 인덱스를 실제 프로젝트에 반영합니다. Functions 서비스 계정은 Firestore 문서 읽기·트랜잭션 삭제/업데이트와 기본 Firebase Storage 버킷 객체 삭제 권한이 필요합니다. 기본 2세대 Functions 서비스 계정을 그대로 쓰는 경우 프로젝트의 Firestore/Storage Admin SDK 권한을 제거하거나 제한하지 마세요. 별도 서비스 계정을 지정했다면 최소 `roles/datastore.user`와 버킷의 `roles/storage.objectAdmin`을 해당 계정에 부여합니다.

   ```bash
   firebase deploy --only firestore:rules,storage,firestore:indexes
   firebase deploy --only functions:notifyNewDocument,functions:cleanupPendingDeletions
   ```

2. Cloud Scheduler API와 Blaze 요금제가 필요합니다. 배포 후 Firebase/Google Cloud Console에서 `cleanupPendingDeletions` Scheduler 작업의 시간대가 `Asia/Seoul`, 스케줄이 매일 03:17인지 확인합니다. 함수는 540초/256MiB, Scheduler 재시도 `retryCount=3`, `maxRetrySeconds=3600`, `minBackoffSeconds=300`으로 배포됩니다.
3. 자동 정리는 `deletedAt`이 7일을 넘긴 자료만 대상으로 하므로, 그 전에는 앱의 기존 수동 재시도 UI가 우선 동작합니다. 실패는 문서의 `deletionLastAttemptAt`, `deletionLastError`, `deletionFailureCount`, `deletionNextRetryAt`와 Functions 로그에서 확인합니다. cursor 순회가 기존 데이터와 이후 자료를 함께 진행하며, 잘못된 Storage 경로가 들어 있는 문서는 안전을 위해 삭제하지 않습니다.
4. Firebase Console에서 알림을 켠 기기의 `users/{uid}/devices/{installationId}` 문서가 토큰 갱신 뒤 갱신되고, 알림을 끄거나 Android 알림 권한을 끈 뒤에는 제거되는지 확인합니다. Firestore 삭제가 일시 실패해도 로컬 opt-out, 설치 ID와 보류 UID/기기 ID는 backup-excluded preferences에 남아 앱 재진입 때 재시도되고, Firestore와 FCM 토큰 삭제는 각각 시도됩니다. 새 기기 복원 후에는 종 아이콘이 OFF이며 권한과 opt-in을 다시 받아야 합니다.

## 테스트 순서

1. 서로 다른 기기 또는 앱 설치 두 개에서 각각 익명 로그인하고 표시 이름을 등록합니다. 등록 전에는 자료·검색·공유 현황에 접근할 수 없고, 등록 후 한 기기에서 등록한 자료가 다른 기기에서 조회·검색·이미지 표시되는지 확인합니다.
2. 서로 다른 등록 완료 사용자 모두가 텍스트 자료를 수정하고 모든 자료를 삭제할 수 있으며, 최초 등록자 표시는 변경되지 않는지 확인합니다.
3. 이미지, 스캔 PDF, 직접 입력 자료를 각각 등록하고 Storage 객체에 `ownerUid` metadata가 기록되는지 확인합니다.
4. Firebase Console에서 Firestore `createdBy`, Storage 경로와 MIME/20MB 제한을 확인합니다.
5. 네트워크를 끄거나 Rules를 새 규칙으로 배포한 뒤, 미등록 사용자 접근은 거부되고 등록 완료 사용자의 다른 UID 자료 수정·삭제는 허용되는지 확인합니다.
6. 서로 다른 설치에서 Presence 인원·기기 수와 기기 유형이 공유 현황에 실시간 반영되는지 확인합니다.
7. 문서 생성·텍스트 수정·삭제 완료 후 `activities`가 최신순으로 표시되고, 수정·삭제를 실행한 실제 사용자가 활동자로 기록되는지 확인합니다.
8. 내 정보에서 표시 이름을 변경한 뒤 새 활동에는 새 이름이 표시되고, 기존 활동에는 당시 이름이 유지되는지 확인합니다.
9. 알림을 켠 두 사용자에서 한 사용자가 자료를 등록했을 때 다른 사용자만 알림을 받고, 등록자 본인은 받지 않는지 확인합니다. FCM 토큰을 Firebase Console에서 무효화한 뒤 다음 발송에서 해당 기기 문서가 제거되는지 확인합니다.
10. 테스트용 자료를 `pendingDeletion=true` 및 7일보다 오래된 `deletedAt`으로 만든 비프로덕션 프로젝트에서 Scheduler를 실행해 이미지/PDF·문서가 정리되는지 확인합니다. 이미 삭제한 객체와 Storage 일부 실패 모두에서 자료가 안전하게 재시도 상태로 남는지 확인합니다.
