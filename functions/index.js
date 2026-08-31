const { onDocumentCreated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { searchClient } = require("@algolia/client-search");
const { syncDocument, searchIndex, reindexDocuments } = require("./algolia-service");
const {
  PENDING_DELETION_MIN_AGE_MS,
  PENDING_DELETION_RETRY_BATCH_SIZE,
  PENDING_DELETION_SCAN_QUERY_LIMIT,
  isStorageObjectNotFound,
  pendingDeletionIsOldEnough,
  pendingDeletionIsRetryDue,
  pendingDeletionRetryDelayMillis,
  sendMulticastWithRetry,
  selectPendingDeletionCandidates,
  storagePathsForPendingDocument,
  timestampMillis,
} = require("./notification-maintenance");

admin.initializeApp();
const appId = defineSecret("ALGOLIA_APP_ID");
const serverKey = defineSecret("ALGOLIA_SERVER_API_KEY");
const indexName = defineSecret("ALGOLIA_INDEX_NAME");

function client() { return searchClient(appId.value(), serverKey.value()); }

exports.syncDocumentToAlgolia = onDocumentWritten({ document: "documents/{documentId}", secrets: [appId, serverKey, indexName] }, async (event) => {
  const id = event.params.documentId;
  return syncDocument(client(), indexName.value(), id, event.data.after);
});

exports.notifyNewDocument = onDocumentCreated({
  document: "documents/{documentId}",
  retry: true,
  timeoutSeconds: 540,
}, async (event) => {
  const document = event.data?.data();
  if (!document) return;

  const creatorUid = typeof document.createdBy === "string" ? document.createdBy : "";
  const title = String(document.title || "제목 없는 자료").trim().slice(0, 80);
  const category = String(document.category || "").trim().slice(0, 40);
  const creatorName = String(document.createdByName || "사용자").trim().slice(0, 20);
  const body = [category, `${creatorName}님이 새 자료를 등록했습니다.`].filter(Boolean).join(" · ");

  const invalidDevices = [];
  try {
    const deviceSnapshot = await admin.firestore()
      .collectionGroup("devices")
      .where("notificationsEnabled", "==", true)
      .get();
    const recipients = deviceSnapshot.docs.filter((device) => device.ref.parent.parent.id !== creatorUid);

    for (let index = 0; index < recipients.length; index += 500) {
      const devices = recipients.slice(index, index + 500).map((device) => ({
        token: device.get("token"),
        ref: device.ref,
        path: device.ref.path,
      }));
      const response = await sendMulticastWithRetry(admin.messaging(), devices, {
        data: {
          title: "새 자료 등록: " + title,
          body,
          documentId: event.params.documentId,
          // A retried event uses the same ID; Android suppresses an already shown delivery.
          deliveryId: event.params.documentId,
        },
        android: { priority: "high" },
      });
      invalidDevices.push(...response.invalidDevices);
    }
  } catch (error) {
    invalidDevices.push(...(error.invalidDevices || []));
    await Promise.all([...new Map(invalidDevices.map((device) => [device.path, device])).values()].map(({ ref }) => ref.delete()));
    console.error("New document notification failed", error);
    // retry:true gives transient FCM failures a bounded CloudEvents retry window.
    throw error;
  }
  await Promise.all(invalidDevices.map(({ ref }) => ref.delete()));
});

exports.cleanupPendingDeletions = onSchedule({
  schedule: "every day 03:17",
  timeZone: "Asia/Seoul",
  timeoutSeconds: 540,
  memory: "256MiB",
  retryCount: 3,
  maxRetrySeconds: 3600,
  minBackoffSeconds: 300,
}, async () => {
  const firestore = admin.firestore();
  const nowMillis = Date.now();
  const cutoffMillis = nowMillis - PENDING_DELETION_MIN_AGE_MS;
  const cutoff = admin.firestore.Timestamp.fromMillis(cutoffMillis);
  const now = admin.firestore.Timestamp.fromMillis(nowMillis);
  const maintenanceState = firestore.collection("_maintenance").doc("pendingDeletionCleanup");
  const cursorSnapshot = await maintenanceState.get();
  const scanCursor = cursorSnapshot.get("pendingDeletionScanCursor");
  const dueQuery = firestore.collection("documents")
    .where("pendingDeletion", "==", true)
    .where("deletionNextRetryAt", "<=", now)
    .orderBy("deletionNextRetryAt", "asc")
    .orderBy(admin.firestore.FieldPath.documentId())
    .limit(PENDING_DELETION_RETRY_BATCH_SIZE);
  let scanQuery = firestore.collection("documents")
    .where("pendingDeletion", "==", true)
    .orderBy(admin.firestore.FieldPath.documentId())
    .limit(PENDING_DELETION_SCAN_QUERY_LIMIT);
  if (typeof scanCursor === "string" && scanCursor.length > 0) scanQuery = scanQuery.startAfter(scanCursor);
  const [dueSnapshot, scanSnapshot] = await Promise.all([dueQuery.get(), scanQuery.get()]);
  const candidates = selectPendingDeletionCandidates(
    dueSnapshot.docs,
    scanSnapshot.docs,
    cutoffMillis,
    nowMillis
  );
  await maintenanceState.set({
    pendingDeletionScanCursor: scanSnapshot.size === PENDING_DELETION_SCAN_QUERY_LIMIT ? scanSnapshot.docs.at(-1).id : null,
    pendingDeletionScanUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, { merge: true });

  const outcomes = await Promise.all(candidates.map((documentSnapshot) =>
    cleanupPendingDeletionDocument({ firestore, bucket: admin.storage().bucket(), documentSnapshot, cutoffMillis, nowMillis })
  ));
  console.log("Pending deletion cleanup completed", {
    dueCandidates: dueSnapshot.size,
    scannedCandidates: scanSnapshot.size,
    examined: candidates.length,
    deleted: outcomes.filter((outcome) => outcome === "deleted").length,
    skipped: outcomes.filter((outcome) => outcome === "skipped").length,
    failed: outcomes.filter((outcome) => outcome === "failed").length,
  });
});

async function cleanupPendingDeletionDocument({ firestore, bucket, documentSnapshot, cutoffMillis, nowMillis }) {
  const data = documentSnapshot.data();
  if (!pendingDeletionIsOldEnough(data, cutoffMillis) || !pendingDeletionIsRetryDue(data, nowMillis)) return "skipped";
  const deletedAtMillis = timestampMillis(data.deletedAt);
  try {
    const paths = storagePathsForPendingDocument(documentSnapshot.id, data);
    await Promise.all(paths.map(async (path) => {
      try {
        await bucket.file(path).delete();
      } catch (error) {
        if (!isStorageObjectNotFound(error)) throw error;
      }
    }));
    const result = await firestore.runTransaction(async (transaction) => {
      const current = await transaction.get(documentSnapshot.ref);
      const currentData = current.data();
      if (!current.exists || !pendingDeletionIsOldEnough(currentData, cutoffMillis) || !pendingDeletionIsRetryDue(currentData, nowMillis) || timestampMillis(currentData.deletedAt) !== deletedAtMillis) {
        return "skipped";
      }
      transaction.delete(documentSnapshot.ref);
      return "deleted";
    });
    return result;
  } catch (error) {
    console.error("Pending deletion cleanup failed", { documentId: documentSnapshot.id, error });
    await firestore.runTransaction(async (transaction) => {
      const current = await transaction.get(documentSnapshot.ref);
      const currentData = current.data();
      if (!current.exists || !pendingDeletionIsOldEnough(currentData, cutoffMillis) || !pendingDeletionIsRetryDue(currentData, nowMillis) || timestampMillis(currentData.deletedAt) !== deletedAtMillis) return;
      transaction.update(documentSnapshot.ref, {
        deletionLastAttemptAt: admin.firestore.FieldValue.serverTimestamp(),
        deletionLastError: "scheduled_storage_or_firestore_delete_failed",
        deletionFailureCount: (Number(currentData.deletionFailureCount) || 0) + 1,
        deletionNextRetryAt: admin.firestore.Timestamp.fromMillis(
          nowMillis + pendingDeletionRetryDelayMillis((Number(currentData.deletionFailureCount) || 0) + 1)
        ),
      });
    });
    return "failed";
  }
}

exports.searchDocuments = onCall({ secrets: [appId, serverKey, indexName] }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  const profile = await admin.firestore().doc(`users/${request.auth.uid}`).get();
  const displayName = profile.get("displayName");
  if (typeof displayName !== "string" || displayName.trim().length === 0 || displayName.trim().length > 20) {
    throw new HttpsError("permission-denied", "사용자 이름 등록 후 검색할 수 있습니다.");
  }
  const query = String(request.data?.query || "").trim();
  if ([...query].length < 2) throw new HttpsError("invalid-argument", "검색어는 2글자 이상 입력해 주세요.");
  try {
    return { documents: await searchIndex(client(), indexName.value(), query) };
  } catch (e) {
    console.error("Algolia search failed", e);
    throw new HttpsError("internal", "검색 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
  }
});

exports.reindexAllDocuments = onCall({ secrets: [appId, serverKey, indexName] }, async (request) => {
  if (!request.auth?.token?.admin) throw new HttpsError("permission-denied", "관리자 권한이 필요합니다.");
  const snapshot = await admin.firestore().collection("documents").get();
  return { count: await reindexDocuments(client(), indexName.value(), snapshot.docs) };
});
