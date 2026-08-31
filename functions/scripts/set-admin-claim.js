/*
 * One-time local administration tool. Do not commit service-account JSON files.
 * Usage: GOOGLE_APPLICATION_CREDENTIALS=/secure/path/service-account.json node scripts/set-admin-claim.js <uid>
 */
const admin = require("firebase-admin");

const uid = process.argv[2];
if (!uid) {
  throw new Error("A Firebase Auth UID argument is required.");
}

admin.initializeApp({ credential: admin.credential.applicationDefault() });

admin.auth().setCustomUserClaims(uid, { admin: true })
  .then(() => console.log("Admin custom claim was set. The user must sign out and sign in again to refresh the token."))
  .catch((error) => {
    console.error("Unable to set the admin custom claim:", error.message);
    process.exitCode = 1;
  });
