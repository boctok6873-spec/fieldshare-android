const INVALID_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

const IMMEDIATE_RETRY_MESSAGING_CODES = new Set([
  "messaging/internal-error",
  "messaging/server-unavailable",
  "messaging/unknown-error",
]);

const RATE_LIMIT_RETRY_MESSAGING_CODES = new Set([
  "messaging/quota-exceeded",
  "messaging/device-message-rate-exceeded",
  "messaging/message-rate-exceeded",
]);

const PENDING_DELETION_MIN_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const PENDING_DELETION_RETRY_BASE_MS = 24 * 60 * 60 * 1000;
const PENDING_DELETION_RETRY_MAX_MS = 7 * 24 * 60 * 60 * 1000;
const PENDING_DELETION_RETRY_BATCH_SIZE = 50;
const PENDING_DELETION_SCAN_BATCH_SIZE = 50;
const PENDING_DELETION_SCAN_QUERY_LIMIT = PENDING_DELETION_RETRY_BATCH_SIZE + PENDING_DELETION_SCAN_BATCH_SIZE;
const MAX_FCM_ATTEMPTS = 3;
const FCM_INITIAL_RETRY_DELAY_MS = 10_000;
const FCM_MAX_RETRY_DELAY_MS = 60_000;
const FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS = 60_000;
const FCM_RATE_LIMIT_MAX_RETRY_DELAY_MS = 240_000;

function isInvalidTokenError(error) {
  return INVALID_TOKEN_CODES.has(error?.code);
}

function isImmediateRetryableMessagingError(error) {
  return IMMEDIATE_RETRY_MESSAGING_CODES.has(error?.code);
}

function isRateLimitRetryableMessagingError(error) {
  return RATE_LIMIT_RETRY_MESSAGING_CODES.has(error?.code);
}

function fcmRetryDelayMillis(
  attempt,
  random = Math.random,
  initialDelayMillis = FCM_INITIAL_RETRY_DELAY_MS,
  maxDelayMillis = FCM_MAX_RETRY_DELAY_MS
) {
  const exponentialDelay = Math.min(initialDelayMillis * 2 ** (attempt - 1), maxDelayMillis);
  // Keep the required minimum and spread concurrent retries with positive jitter.
  return Math.floor(exponentialDelay * (1 + random()));
}

function pendingDeletionIsOldEnough(document, cutoffMillis) {
  if (document?.pendingDeletion !== true) return false;
  const deletedAt = timestampMillis(document.deletedAt);
  return deletedAt !== null && deletedAt <= cutoffMillis;
}

function pendingDeletionIsRetryDue(document, nowMillis) {
  const nextRetryAt = timestampMillis(document?.deletionNextRetryAt);
  return nextRetryAt === null || nextRetryAt <= nowMillis;
}

function pendingDeletionRetryDelayMillis(failureCount) {
  const exponent = Math.max(0, Math.min(Number(failureCount) - 1, 6));
  return Math.min(PENDING_DELETION_RETRY_BASE_MS * 2 ** exponent, PENDING_DELETION_RETRY_MAX_MS);
}

function selectPendingDeletionCandidates(dueDocuments, scannedDocuments, cutoffMillis, nowMillis) {
  const selected = [];
  const selectedIds = new Set();
  const addEligible = (document, allowScheduledRetry) => {
    if (selected.length >= PENDING_DELETION_RETRY_BATCH_SIZE + PENDING_DELETION_SCAN_BATCH_SIZE) return;
    const data = document.data();
    if (!allowScheduledRetry && timestampMillis(data.deletionNextRetryAt) !== null) return;
    if (!pendingDeletionIsOldEnough(data, cutoffMillis) || !pendingDeletionIsRetryDue(data, nowMillis) || selectedIds.has(document.id)) return;
    selectedIds.add(document.id);
    selected.push(document);
  };
  // Reserve half the daily capacity for cursor scanning so repeatedly failing old documents
  // cannot monopolize all 100 slots and starve existing or later pending deletions.
  dueDocuments.slice(0, PENDING_DELETION_RETRY_BATCH_SIZE).forEach((document) => addEligible(document, true));
  scannedDocuments.forEach((document) => addEligible(document, false));
  return selected;
}

function timestampMillis(value) {
  if (typeof value === "number") return value;
  if (value instanceof Date) return value.getTime();
  if (typeof value?.toMillis === "function") return value.toMillis();
  if (typeof value?._seconds === "number") return value._seconds * 1000 + Math.floor((value._nanoseconds || 0) / 1e6);
  return null;
}

function storagePathsForPendingDocument(documentId, document) {
  const imagePaths = document?.imagePaths;
  const candidates = [
    ...(imagePaths == null ? [] : Array.isArray(imagePaths) ? imagePaths : [imagePaths]),
    ...(document?.pdfPath == null ? [] : [document.pdfPath]),
  ];
  const validPath = new RegExp(`^documents/${escapeRegex(documentId)}/(?:images|pdf)/[^/]+$`);
  const invalid = candidates.filter((path) => typeof path !== "string" || !validPath.test(path));
  if (invalid.length > 0) {
    throw new Error("pendingDeletion document has an unexpected Storage path");
  }
  return [...new Set(candidates)];
}

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isStorageObjectNotFound(error) {
  return error?.code === 404 || error?.code === "404" || error?.code === "storage/object-not-found";
}

async function sendMulticastWithRetry(messaging, devices, message, options = {}) {
  const wait = options.wait || ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const random = options.random || Math.random;
  let remaining = devices;
  const invalidDevices = [];

  for (let attempt = 1; remaining.length > 0 && attempt <= MAX_FCM_ATTEMPTS; attempt += 1) {
    let response;
    try {
      response = await messaging.sendEachForMulticast({ tokens: remaining.map((device) => device.token), ...message });
    } catch (error) {
      // A whole-request failure has no trustworthy per-token success list. Rate limits still
      // require the FCM minimum one-minute backoff, but never produce invalid-token cleanup.
      if (isRateLimitRetryableMessagingError(error)) {
        if (attempt < MAX_FCM_ATTEMPTS) {
          await wait(fcmRetryDelayMillis(
            attempt,
            random,
            FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS,
            FCM_RATE_LIMIT_MAX_RETRY_DELAY_MS
          ));
          continue;
        }
        throw retryDeliveryError(retryExhaustedError(), invalidDevices);
      }
      // Other whole-request failures have no safe per-token retry set; let Eventarc retry them.
      throw retryDeliveryError(error, invalidDevices);
    }

    const retryDevices = [];
    let retryInitialDelayMillis = FCM_INITIAL_RETRY_DELAY_MS;
    let retryMaxDelayMillis = FCM_MAX_RETRY_DELAY_MS;
    response.responses.forEach((result, index) => {
      if (result.success) return;
      const device = remaining[index];
      if (isInvalidTokenError(result.error)) {
        invalidDevices.push(device);
      } else if (isRateLimitRetryableMessagingError(result.error)) {
        retryDevices.push(device);
        retryInitialDelayMillis = FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS;
        retryMaxDelayMillis = FCM_RATE_LIMIT_MAX_RETRY_DELAY_MS;
      } else if (isImmediateRetryableMessagingError(result.error)) {
        retryDevices.push(device);
      } else {
        console.error("FCM delivery failed permanently", { code: result.error?.code, devicePath: device.path });
      }
    });
    remaining = retryDevices;
    if (remaining.length > 0 && attempt < MAX_FCM_ATTEMPTS) {
      await wait(fcmRetryDelayMillis(attempt, random, retryInitialDelayMillis, retryMaxDelayMillis));
    }
  }

  if (remaining.length > 0) {
    throw retryDeliveryError(retryExhaustedError(), invalidDevices);
  }
  return { invalidDevices };
}

function retryExhaustedError() {
  const error = new Error("FCM temporary delivery failures exhausted retry attempts");
  error.code = "messaging/retry-exhausted";
  return error;
}

function retryDeliveryError(error, invalidDevices) {
  error.invalidDevices = invalidDevices;
  return error;
}

module.exports = {
  MAX_FCM_ATTEMPTS,
  PENDING_DELETION_MIN_AGE_MS,
  PENDING_DELETION_RETRY_BATCH_SIZE,
  PENDING_DELETION_RETRY_BASE_MS,
  PENDING_DELETION_RETRY_MAX_MS,
  PENDING_DELETION_SCAN_BATCH_SIZE,
  PENDING_DELETION_SCAN_QUERY_LIMIT,
  isStorageObjectNotFound,
  FCM_INITIAL_RETRY_DELAY_MS,
  FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS,
  fcmRetryDelayMillis,
  isRateLimitRetryableMessagingError,
  isImmediateRetryableMessagingError,
  pendingDeletionIsOldEnough,
  pendingDeletionIsRetryDue,
  pendingDeletionRetryDelayMillis,
  sendMulticastWithRetry,
  selectPendingDeletionCandidates,
  storagePathsForPendingDocument,
  timestampMillis,
};
