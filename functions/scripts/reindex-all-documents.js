/*
 * Local one-time reindex utility. Required environment variables:
 * GOOGLE_APPLICATION_CREDENTIALS, ALGOLIA_APP_ID, ALGOLIA_SERVER_API_KEY, ALGOLIA_INDEX_NAME.
 * Never commit service-account files or export these values in logs.
 */
const admin = require("firebase-admin");
const { searchClient } = require("@algolia/client-search");
const { reindexDocuments } = require("../algolia-service");

const required = ["ALGOLIA_APP_ID", "ALGOLIA_SERVER_API_KEY", "ALGOLIA_INDEX_NAME"];
if (required.some((name) => !process.env[name])) {
  throw new Error("Required Algolia environment variables are missing.");
}
if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
  throw new Error("GOOGLE_APPLICATION_CREDENTIALS must point to a local service-account file.");
}

admin.initializeApp({ credential: admin.credential.applicationDefault() });

async function run() {
  const snapshot = await admin.firestore().collection("documents").get();
  const count = await reindexDocuments(
    searchClient(process.env.ALGOLIA_APP_ID, process.env.ALGOLIA_SERVER_API_KEY),
    process.env.ALGOLIA_INDEX_NAME,
    snapshot.docs,
  );
  console.log(`Reindexed ${count} documents.`);
}

run().catch((error) => {
  console.error("Reindex failed:", error.message);
  process.exitCode = 1;
});
