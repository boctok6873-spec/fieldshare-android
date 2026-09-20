# FieldShare 개인 자료 Google Drive 설정

개인 자료는 연결된 Google Drive에 저장됩니다.

## 계정 경계

- 공유 자료는 기존 Firebase 익명 인증, 사용자 이름, Firestore/Storage/Functions/Algolia 경로를 유지합니다.
- Google 연결은 Drive 권한 요청 전용입니다. Firebase Google 공급자 활성화, GoogleAuthProvider, Firebase 로그인/계정 연결, Firebase 로그아웃을 사용하지 않습니다.
- 토큰은 메모리에만 보관하며 Google API HTTPS Authorization 헤더로만 전달합니다. 서버 인증 코드나 refresh token을 요청하지 않습니다.
- AuthorizationClient의 계정 선택 후 동일 토큰으로 Google userinfo의 `sub`와 이메일을 확인합니다. SDK가 계정 ID를 반환하면 `sub`와 일치해야 합니다. AuthorizationResult는 계정 정보를 생략할 수 있으므로 그 경우 Google의 안정적인 OpenID `sub`를 사용합니다. 표시 이름, 이메일, Firebase UID는 캐시 키로 사용하지 않습니다.
- 이메일과 계정 `sub`는 `noBackupFilesDir/private-drive/connection.json`에만 저장합니다. 계정별 데이터 경로는 `sub`의 SHA-256입니다. 이 해시는 암호화나 익명화 보장이 아니라 경로 구분입니다.
- Google 이메일/ID/토큰/개인 OCR/검색어/본문을 Firestore, Functions, Algolia, 접속 상태, 푸시 등록, 분석 이벤트로 보내는 경로는 없습니다.

## 개발용 Google Cloud Console

1. 이 Android 앱에 사용할 Google Cloud 프로젝트를 선택합니다. 기존 Firebase의 Google Cloud 프로젝트를 사용해도 Firebase 인증과 Drive 권한은 별개입니다.
2. APIs & Services → Library에서 **Google Drive API**를 활성화합니다.
3. Google Auth Platform의 Branding / Audience / Data Access를 설정합니다. 앱 이름, 지원 이메일, 개발자 연락처를 입력합니다. 외부 사용자 개발 테스트는 Testing으로 두고 명시적인 테스트 계정만 등록합니다.
4. 요청 범위는 `https://www.googleapis.com/auth/drive.file`, `openid`, `email`입니다. 전체 Drive 범위, 서버 offline access, 서비스 계정은 필요하지 않습니다.
5. Clients에서 **Android OAuth 클라이언트**를 생성합니다. 패키지명은 `com.youngsu.fieldshare`, SHA-1은 현재 APK의 서명 인증서 값입니다. 로컬에서는 `./gradlew.bat :app:signingReport`로 debug SHA-1을 확인합니다. 다른 개발 PC의 debug 키는 다를 수 있습니다.
6. 같은 패키지명·인증서 조합을 올바른 프로젝트에 등록합니다. 이 구현에는 웹 클라이언트 시크릿이나 서비스 계정 JSON을 넣지 않습니다. Firebase `google-services.json`은 기존 공유 기능의 설정 파일이며 Drive 사용자를 Firebase에 로그인시키는 수단이 아닙니다.
7. Google Play services가 있는 테스트 기기에서 내 정보 → Google Drive 연결을 실행하고 계정을 선택합니다. 요청한 Drive 범위를 허용한 후 표시 이메일과 Drive 계정이 일치하는지 확인합니다. 취소하면 공유 자료는 그대로 사용할 수 있어야 합니다.
8. 이름을 바꾼 `FieldShare 내 자료` 폴더, 다른 기기/재설치, 계정 변경, 외부 권한 철회, 네트워크 차단, 용량 부족을 별도로 검증합니다. 실제 계정 연결 검증은 이 코드 작업에서 수행하지 않았습니다.

## 배포 전 별도 조건

이번 빌드의 debug SHA-1은 `17:83:24:E2:7F:94:3F:D1:90:58:46:2D:75:16:57:31:FA:F7:95:B3`입니다. 2026-09-08 `:app:signingReport`로 확인했습니다. release 서명은 현재 Gradle 설정에 없습니다.

운영 배포는 수행하지 않았습니다. 배포 시 Audience 게시 상태, 개인정보처리방침·브랜딩·도메인 및 Console에서 요구하는 검증을 완료해야 합니다. `drive.file`은 권장되는 파일 단위 범위이지만 앱 전체의 게시 요건을 생략할 수 있다는 뜻은 아닙니다. 릴리스 및 Google Play App Signing 인증서의 SHA-1을 등록하고 배포 APK/AAB 서명으로 다시 권한 동의를 검증합니다. 개발용 테스트 사용자 등록은 운영 배포 조건을 대신하지 않습니다.

## 저장·동기화·충돌 처리

- 일반 내 드라이브 폴더를 사용하며 공개 링크나 permissions API를 호출하지 않습니다. 폴더 이름 대신 앱의 `appProperties`와 ID를 사용합니다. 재연결 시 모든 페이지에서 기존 폴더를 찾아 정렬한 ID로 하나를 선택합니다. 메타데이터 검색은 폴더 이름/로컬 폴더 ID에 의존하지 않습니다.
- 최초 동기화 시작 전에 changes 토큰을 얻고 모든 메타데이터 페이지를 읽은 후 그 사이 변경을 재생합니다. 후속 동기화는 changes 페이지와 파일 version을 사용합니다. HTTP 410이면 전체 메타데이터를 다시 동기화합니다. 최초 동기화가 중간 실패하면 완료로 표시하지 않습니다.
- 로컬 저장소는 Android AtomicFile 기반 JSON 스냅샷입니다. 체크포인트, 메타데이터, 버전 계보, `initialSyncComplete`, 마지막 성공 시간을 함께 저장합니다. 목록의 모든 페이지와 changes 마지막 페이지까지 성공해야 최초 완료가 true가 됩니다. 예전 캐시에 완료 필드가 없으면 checkpoint를 신뢰하지 않고 전체 동기화를 다시 수행하며, 기존 검색 캐시와 마지막 성공 시간은 유지합니다. 410 재동기화 중에는 완료가 false이며 일반 증분 실패는 이전 완료 상태를 유지합니다. 계정 정보와 메타데이터는 noBackupFilesDir, 등록 임시파일과 열기용 사본은 계정별 cacheDir에 있습니다.
- 본문/제목/제품 카테고리/OCR을 공백 정리 및 대소문자 무시 부분 문자열로 검색합니다. 최근/숨김 모드에서도 검색 범위는 동기화된 개인 메타데이터 전체입니다. 검색 중 원본 다운로드는 없습니다.
- 이미지 OCR은 기존 기기 내 ML Kit 경로입니다. 이미지 준비는 기존 고해상도 최적화 흐름을 재사용합니다. PDF는 별도 첨부를 지원하지만 PDF OCR/본문 자동 추출은 하지 않습니다.
- 원본은 열 때 내려받으며 계정별 원본 캐시는 100MB를 기준으로 오래된 파일을 정리합니다. 현재 열기용 사본 하나를 별도로 유지합니다. 오프라인에서는 내려받은 원본과 메타데이터만 사용할 수 있습니다.
- 생성은 사전 발급한 Drive 파일 ID와 영속 작업 기록으로 재시도합니다. 첨부 → 스키마 2 메타데이터 → 별도 계보 기록(`*.lineage.json`) 순서로 모두 업로드되어야 저장 성공입니다. 계보에는 자료/버전/부모/메타데이터 ID와 시간·삭제 여부만 있고 본문·계정 정보는 없습니다. 미완료 작업의 스키마 업그레이드와 새 ID도 네트워크 요청 전에 영속 저장합니다. 실패한 저장은 재시도/취소할 수 있습니다. 응답 유실 후 실제 메타데이터가 존재하면 취소 시에도 계보 기록을 마쳐 이미 게시된 자료를 보존합니다. 자동 무제한 재시도는 없습니다.
- 수정은 기존 메타데이터를 덮어쓰지 않는 새 버전 파일입니다. 이전 버전과 현재 내용을 확인하여 변경이 있으면 거절합니다. 동시에 생성된 버전은 모두 보존하고 충돌로 표시합니다. 사용자가 각 내용을 확인한 뒤 한 버전을 명시적으로 선택하면 해당 버전을 기준으로 새 병합 버전을 만듭니다.
- 첨부는 수정 버전별로 Drive 서버 내부 복사합니다. 편집할 때 원본을 전부 내려받지 않으며, 동시 삭제가 다른 버전의 원본을 삭제하지 못하도록 합니다. 충돌 안전성을 위해 이전 버전과 첨부 사본은 자료 삭제 전까지 Drive 용량을 사용합니다.
- 삭제는 내용 없는 삭제 버전을 먼저 기록하고 해당 자료의 기존 메타데이터·첨부파일만 휴지통으로 옮깁니다. 본문/OCR/첨부 없는 삭제 기록은 동시 수정 충돌 감지를 위해 남습니다. 실패한 정리는 정확한 파일 ID 목록으로 재시도합니다. 연결 해제는 원본을 휴지통으로 보내지 않습니다.
- 작업은 Mutex로 직렬화합니다. 저장 중 계정 변경/해제를 막고, 미완료 저장은 완료 또는 명시적 취소를 요구합니다. 연결 해제는 진행 중 동기화를 기다린 뒤 로컬 상태를 지워 이전 요청 결과가 새 계정 화면으로 넘어가지 않게 합니다.

## 알려진 한계 / 실연동 확인 필요

- 외부 삭제 감지는 `drive.file`로 접근 가능한 앱 metadata/lineage 파일을 휴지통 포함하여 조회합니다. `trashed=true`는 휴지통 이동, changes의 `removed` 또는 전체 목록에서 사라짐은 영구 삭제 **또는 접근권 상실**로 구분하여 표시합니다. 후자는 영구 삭제라고 확정하지 않습니다. 외부 메타데이터 누락만으로 첨부를 삭제하지 않습니다.
- 새 기기에서도 별도 계보 기록이 남아 있으면 삭제된 B의 부모 A를 현재 자료로 승격하지 않습니다. B를 복구 확인 상태로 남기고 살아 있는 동시 버전 C도 보존합니다. 앱의 삭제 버전과 동시 수정도 충돌로 보존합니다. 휴지통 메타데이터를 복원한 후 동기화하거나 살아 있는 버전을 명시적으로 선택해야 합니다.
- 스키마 1과 계보 증거가 일치하지 않는 자료는 내용을 읽되 **복구 확인 필요**로 처리합니다. 사용자가 명시적으로 내용을 선택하면 스키마 2의 새 병합 버전으로 기록합니다. 구버전 앱은 스키마 2를 지원하지 않으므로 관련 기기를 모두 업데이트해야 합니다. 예전 자료의 알 수 없는 외부 삭제 이력을 자동 복원할 수는 없습니다. 최신 메타데이터와 독립 계보 기록까지 모두 영구 삭제되고 이를 본 로컬 캐시도 없으면 새 기기는 그 삭제 이력을 확정할 수 없습니다.
- 등록은 계정 키와 저장 가능한 UUID로 초안 디렉터리를 재사용합니다. URI·OCR 상태·검색 텍스트는 같은 AtomicFile 초안에 보관하고 Activity 재생성의 dispose에서는 삭제하지 않습니다. 성공 또는 명시적 취소 시 해당 초안만 정리합니다. 복원 때 파일 유효성을 확인하고 누락/중단 OCR은 재첨부 안내를 표시합니다. 실제 프로세스 강제 종료·OS 캐시 회수는 복구를 보장하지 않습니다. 카메라 대기 파일도 유지하지만 외부 카메라 앱 자체의 작업 재개는 해당 앱에 의존합니다.
- 7일 지난 비활성 초안만 정리하며 활성 초안과 `.saving` 작업 표시는 제외합니다. 계정 변경/해제도 다른 계정 또는 활성/저장 중 초안을 삭제하지 않습니다. 프로세스 종료 뒤 남은 저장 표시를 시간만으로 지우지 않으므로 고립된 초안이 디스크에 남을 수 있습니다.

- OAuth 동의, 실제 Drive API의 생성·복사·삭제, 실제 다중 기기 동작은 아직 실계정으로 검증하지 않았습니다.
- 첫 연결을 여러 기기에서 정확히 동시에 수행하면 Drive에는 폴더 이름/메타데이터의 원자적 유일성 제약이 없어 폴더가 둘 생길 가능성이 있습니다. 이후 발견 시 정렬된 ID의 폴더를 사용하며 모든 앱 메타데이터를 읽어 자료가 분리되지 않게 합니다. 자동으로 중복 폴더를 삭제하지 않습니다.
- 첨부 업로드는 파일당 25MB 제한이며 실패 시 동일 ID로 전체 파일을 재전송합니다. 청크 단위 업로드 재개는 없습니다.
- 로컬 스냅샷과 목록은 소규모 자료를 위한 초기 구현입니다. 매우 큰 메타데이터 목록의 성능과 메모리 사용은 실기기 대용량 검증이 필요합니다.
- 기기에서 외부 앱으로 이미 연 파일의 사본은 해당 외부 앱이 자체 보관할 수 있습니다. FieldShare 연결 해제는 다른 앱의 저장소나 기기의 Google 로그인 상태를 지우지 않습니다.
- 별도 암호화를 구현하지 않았으며 종단간 암호화 또는 개발자의 절대적 접근 불가능을 주장하지 않습니다.

## 공식 문서

- Android 권한 요청 및 계정 정보: https://developer.android.com/identity/authorization
- 계정 선택 요청: https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder
- 권장 Drive 범위: https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- 앱 메타데이터: https://developers.google.com/workspace/drive/api/guides/properties
- 변경 동기화: https://developers.google.com/workspace/drive/api/guides/manage-changes
- 휴지통을 포함한 파일 조회: https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list
- removed 의미(삭제 또는 접근권 상실): https://developers.google.com/workspace/drive/api/reference/rest/v3/changes
- 사전 생성 파일 ID(create/copy): https://developers.google.com/workspace/drive/api/reference/rest/v3/files/generateIds
- 업로드 방식: https://developers.google.com/workspace/drive/api/guides/manage-uploads
