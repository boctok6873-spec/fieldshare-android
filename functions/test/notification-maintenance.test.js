const test = require("node:test");
const assert = require("node:assert/strict");
const {
  FCM_INITIAL_RETRY_DELAY_MS,
  FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS,
  PENDING_DELETION_MIN_AGE_MS,
  isRateLimitRetryableMessagingError,
  isStorageObjectNotFound,
  pendingDeletionIsOldEnough,
  pendingDeletionRetryDelayMillis,
  selectPendingDeletionCandidates,
  sendMulticastWithRetry,
  storagePathsForPendingDocument,
} = require("../notification-maintenance");

test("pending deletion cleanup requires an old deletion timestamp", () => {
  const cutoff = 1_000_000;
  assert.equal(pendingDeletionIsOldEnough({ pendingDeletion: true, deletedAt: cutoff }, cutoff), true);
  assert.equal(pendingDeletionIsOldEnough({ pendingDeletion: true, deletedAt: cutoff + 1 }, cutoff), false);
  assert.equal(pendingDeletionIsOldEnough({ pendingDeletion: false, deletedAt: cutoff - PENDING_DELETION_MIN_AGE_MS }, cutoff), false);
  assert.equal(pendingDeletionIsOldEnough({ pendingDeletion: true }, cutoff), false);
});

test("cleanup only accepts Storage objects beneath its document path", () => {
  assert.deepEqual(
    storagePathsForPendingDocument("doc-1", {
      imagePaths: ["documents/doc-1/images/one.jpg", "documents/doc-1/images/one.jpg"],
      pdfPath: "documents/doc-1/pdf/file.pdf",
    }),
    ["documents/doc-1/images/one.jpg", "documents/doc-1/pdf/file.pdf"]
  );
  assert.throws(
    () => storagePathsForPendingDocument("doc-1", { imagePaths: ["documents/other/images/no.jpg"] }),
    /unexpected Storage path/
  );
  assert.equal(isStorageObjectNotFound({ code: 404 }), true);
});

test("FCM resends only retryable failed tokens after a 10-second exponential backoff", async () => {
  const requests = [];
  const waits = [];
  const messaging = {
    sendEachForMulticast: async ({ tokens }) => {
      requests.push(tokens);
      if (requests.length === 1) {
        return {
          responses: [
            { success: true },
            { success: false, error: { code: "messaging/server-unavailable" } },
            { success: false, error: { code: "messaging/registration-token-not-registered" } },
          ],
        };
      }
      return { responses: [{ success: true }] };
    },
  };
  const devices = [{ token: "sent" }, { token: "retry" }, { token: "invalid" }];

  const result = await sendMulticastWithRetry(
    messaging,
    devices,
    { data: { deliveryId: "doc-1" } },
    { wait: async (delay) => waits.push(delay), random: () => 0 }
  );

  assert.deepEqual(requests, [["sent", "retry", "invalid"], ["retry"]]);
  assert.deepEqual(waits, [FCM_INITIAL_RETRY_DELAY_MS]);
  assert.deepEqual(result.invalidDevices, [devices[2]]);
});

test("quota and rate-limit errors retry only failed tokens after at least one minute", async () => {
  for (const code of ["messaging/quota-exceeded", "messaging/device-message-rate-exceeded", "messaging/message-rate-exceeded"]) {
    const requests = [];
    const waits = [];
    const invalidDevice = { token: "invalid" };
    const messaging = {
      sendEachForMulticast: async ({ tokens }) => {
        requests.push(tokens);
        if (requests.length === 2) return { responses: [{ success: true }] };
        return {
          responses: [
            { success: false, error: { code: "messaging/invalid-registration-token" } },
            { success: false, error: { code } },
            { success: true },
          ],
        };
      },
    };

    const result = await sendMulticastWithRetry(
      messaging,
      [invalidDevice, { token: "limited" }, { token: "sent" }],
      {},
      { wait: async (delay) => waits.push(delay), random: () => 0 }
    );
    assert.equal(isRateLimitRetryableMessagingError({ code }), true);
    assert.deepEqual(requests, [["invalid", "limited", "sent"], ["limited"]]);
    assert.deepEqual(waits, [FCM_RATE_LIMIT_INITIAL_RETRY_DELAY_MS]);
    assert.deepEqual(result.invalidDevices, [invalidDevice]);
  }
});

test("rate-limit retries throw after the local attempt budget is exhausted", async () => {
  const waits = [];
  const requests = [];
  const messaging = {
    sendEachForMulticast: async ({ tokens }) => {
      requests.push(tokens);
      return { responses: [{ success: false, error: { code: "messaging/quota-exceeded" } }] };
    },
  };

  await assert.rejects(
    sendMulticastWithRetry(messaging, [{ token: "limited" }], {}, { wait: async (delay) => waits.push(delay), random: () => 0 }),
    (error) => error.code === "messaging/retry-exhausted"
  );
  assert.deepEqual(requests, [["limited"], ["limited"], ["limited"]]);
  assert.deepEqual(waits, [60_000, 120_000]);
});

test("whole-request rate-limit retries the unchanged token set after at least one minute", async () => {
  const waits = [];
  const requests = [];
  const devices = [{ token: "first" }, { token: "second" }];
  const messaging = {
    sendEachForMulticast: async ({ tokens }) => {
      requests.push(tokens);
      if (requests.length < 3) {
        const error = new Error("quota limited");
        error.code = "messaging/quota-exceeded";
        throw error;
      }
      return { responses: [{ success: true }, { success: true }] };
    },
  };

  const result = await sendMulticastWithRetry(
    messaging,
    devices,
    {},
    { wait: async (delay) => waits.push(delay), random: () => 0 }
  );

  assert.deepEqual(requests, [["first", "second"], ["first", "second"], ["first", "second"]]);
  assert.deepEqual(waits, [60_000, 120_000]);
  assert.deepEqual(result.invalidDevices, []);
});

test("whole-request rate-limit throws after three attempts with one-minute backoff", async () => {
  const waits = [];
  const requests = [];
  const messaging = {
    sendEachForMulticast: async ({ tokens }) => {
      requests.push(tokens);
      const error = new Error("device limited");
      error.code = "messaging/device-message-rate-exceeded";
      throw error;
    },
  };

  await assert.rejects(
    sendMulticastWithRetry(messaging, [{ token: "limited" }], {}, { wait: async (delay) => waits.push(delay), random: () => 0 }),
    (error) => error.code === "messaging/retry-exhausted" && error.invalidDevices.length === 0
  );
  assert.deepEqual(requests, [["limited"], ["limited"], ["limited"]]);
  assert.deepEqual(waits, [60_000, 120_000]);
});

test("repeated server failures exhaust local retries and throw for Eventarc retry", async () => {
  const waits = [];
  let calls = 0;
  const messaging = {
    sendEachForMulticast: async () => {
      calls += 1;
      return { responses: [{ success: false, error: { code: "messaging/internal-error" } }] };
    },
  };

  await assert.rejects(
    sendMulticastWithRetry(messaging, [{ token: "retry" }], {}, { wait: async (delay) => waits.push(delay), random: () => 0 }),
    (error) => error.code === "messaging/retry-exhausted"
  );
  assert.equal(calls, 3);
  assert.deepEqual(waits, [10_000, 20_000]);
});

test("cursor scan reserves capacity so failing old documents cannot starve later candidates", () => {
  const now = 1_000_000;
  const cutoff = now - 1;
  const document = (id, data) => ({ id, data: () => data });
  const due = Array.from({ length: 100 }, (_, index) => document(`failed-${index}`, {
    pendingDeletion: true,
    deletedAt: cutoff,
    deletionNextRetryAt: now,
  }));
  const scanned = [
    ...due.slice(0, 50),
    ...Array.from({ length: 50 }, (_, index) => document(`later-${index}`, {
    pendingDeletion: true,
    deletedAt: cutoff,
    })),
  ];

  const selected = selectPendingDeletionCandidates(due, scanned, cutoff, now);

  assert.equal(selected.length, 100);
  assert.equal(selected.filter((item) => item.id.startsWith("failed-")).length, 50);
  assert.equal(selected.filter((item) => item.id.startsWith("later-")).length, 50);
  assert.equal(pendingDeletionRetryDelayMillis(1), 24 * 60 * 60 * 1000);
  assert.equal(pendingDeletionRetryDelayMillis(4), 7 * 24 * 60 * 60 * 1000);
});
