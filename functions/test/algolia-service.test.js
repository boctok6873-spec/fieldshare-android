const test = require("node:test");
const assert = require("node:assert/strict");
const { syncDocument, searchIndex, reindexDocuments } = require("../algolia-service");

function snapshot(id, data, exists = true) {
  return { id, exists, data: () => data };
}

test("sync saves a normal document with the v5 indexName/body contract", async () => {
  const calls = [];
  await syncDocument({ saveObject: async (value) => calls.push(value) }, "fieldshare_documents", "a", snapshot("a", { title: "TV" }));
  assert.deepEqual(calls, [{ indexName: "fieldshare_documents", body: { objectID: "a", title: "TV", category: "", detail: "", description: "", content: "", searchableText: "", createdAt: 0 } }]);
});

test("sync deletes deleted or pending-deletion documents", async () => {
  const deleted = [];
  const client = { deleteObject: async (value) => deleted.push(value) };
  await syncDocument(client, "fieldshare_documents", "gone", snapshot("gone", {}, false));
  await syncDocument(client, "fieldshare_documents", "pending", snapshot("pending", { pendingDeletion: true }));
  assert.deepEqual(deleted, [
    { indexName: "fieldshare_documents", objectID: "gone" },
    { indexName: "fieldshare_documents", objectID: "pending" },
  ]);
});

test("search keeps Algolia hit order and returns IDs only", async () => {
  const hits = await searchIndex({ searchSingleIndex: async () => ({ hits: [{ objectID: "b", title: "B", content: "secret", searchableText: "ocr" }, { objectID: "a", title: "A" }] }) }, "fieldshare_documents", "에어컨");
  assert.deepEqual(hits, [{ id: "b" }, { id: "a" }]);
  assert.equal("title" in hits[0], false);
  assert.equal("content" in hits[0], false);
  assert.equal("searchableText" in hits[0], false);
});

test("reindex configures searchable fields and skips pending documents", async () => {
  const calls = [];
  const client = {
    setSettings: async (value) => calls.push(["settings", value]),
    saveObjects: async (value) => calls.push(["objects", value]),
  };
  const count = await reindexDocuments(client, "fieldshare_documents", [snapshot("a", { title: "A" }), snapshot("pending", { pendingDeletion: true })]);
  assert.equal(count, 1);
  assert.equal(calls[0][1].indexName, "fieldshare_documents");
  assert.deepEqual(calls[1][1].objects.map((object) => object.objectID), ["a"]);
});
