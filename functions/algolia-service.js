const SEARCHABLE_ATTRIBUTES = ["title", "category", "detail", "description", "content", "searchableText"];

function toAlgoliaRecord(id, data = {}) {
  return { objectID: id, title: data.title || "", category: data.category || "", detail: data.detail || "", description: data.description || "", content: data.content || "", searchableText: data.searchableText || "", createdAt: data.createdAt?.toMillis?.() || 0 };
}

async function syncDocument(client, indexName, documentId, after) {
  if (!after.exists || after.data()?.pendingDeletion === true) {
    return client.deleteObject({ indexName, objectID: documentId });
  }
  return client.saveObject({ indexName, body: toAlgoliaRecord(documentId, after.data()) });
}

async function searchIndex(client, indexName, query) {
  const result = await client.searchSingleIndex({ indexName, searchParams: { query, hitsPerPage: 50 } });
  // Algolia is used only to rank matching IDs. Document fields stay in Firestore.
  return result.hits.map(({ objectID }) => ({ id: objectID }));
}

async function reindexDocuments(client, indexName, snapshots) {
  const objects = snapshots.filter((snapshot) => snapshot.exists && snapshot.data()?.pendingDeletion !== true).map((snapshot) => toAlgoliaRecord(snapshot.id, snapshot.data()));
  await client.setSettings({ indexName, indexSettings: { searchableAttributes: SEARCHABLE_ATTRIBUTES } });
  if (objects.length > 0) await client.saveObjects({ indexName, objects });
  return objects.length;
}

module.exports = { SEARCHABLE_ATTRIBUTES, toAlgoliaRecord, syncDocument, searchIndex, reindexDocuments };
