const test = require("node:test");
const assert = require("node:assert/strict");

test("pending deletion scheduler exposes supported v2 retry metadata", () => {
  const { cleanupPendingDeletions, notifyNewDocument } = require("../index");
  const retryConfig = cleanupPendingDeletions.__endpoint.scheduleTrigger.retryConfig;

  assert.equal(retryConfig.retryCount, 3);
  assert.equal(retryConfig.maxRetrySeconds, 3600);
  assert.equal(retryConfig.minBackoffSeconds, 300);
  assert.equal("maxRetryDuration" in retryConfig, false);
  assert.equal("minBackoffDuration" in retryConfig, false);
  assert.equal(notifyNewDocument.__endpoint.timeoutSeconds, 540);
});
