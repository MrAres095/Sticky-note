import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

test("account registration and Keep Takeout import are authenticated and idempotent", async t => {
  const dataDir = await mkdtemp(join(tmpdir(), "sticky-note-api-"));
  const port = 18000 + Math.floor(Math.random() * 10000);
  const child = spawn(process.execPath, ["src/server.js"], {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, NODE_ENV: "test", HOST: "127.0.0.1", PORT: String(port), DATA_DIR: dataDir, SESSION_PEPPER: "test-secret-long-enough-for-local-integration" },
    stdio: "ignore"
  });
  const base = `http://127.0.0.1:${port}/api/v1`;
  t.after(async () => { child.kill("SIGTERM"); await rm(dataDir, { recursive: true, force: true }); });
  let ready = false;
  for (let i = 0; i < 80; i++) {
    if (child.exitCode !== null) break;
    try { const r = await fetch(base + "/health"); if (r.ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.equal(ready, true, "server should start and report healthy");

  const register = await fetch(base + "/auth/register", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: "keep-import@example.test", password: "a-secure-test-password-123" })
  });
  assert.equal(register.status, 201);
  const auth = await register.json();
  assert.ok(auth.token);

  const sample = { notes: [
    { id: "takeout-note-1", title: "Shopping", textContent: "Milk", color: "BLUE", labels: [{ name: "Home" }], isArchived: true },
    { id: "takeout-list-1", title: "Tasks", isArchived: false, listContent: [{ text: "First", isChecked: true }, { text: "Second", isChecked: false }] }
  ] };
  const headers = { "content-type": "application/json", authorization: `Bearer ${auth.token}` };
  const first = await fetch(base + "/import/keep/takeout", { method: "POST", headers, body: JSON.stringify(sample) });
  assert.equal(first.status, 200);
  const firstResult = await first.json();
  assert.equal(firstResult.importedNotes, 1);
  assert.equal(firstResult.importedLists, 1);
  assert.equal(firstResult.importedTasks, 2);

  const second = await fetch(base + "/import/keep/takeout", { method: "POST", headers, body: JSON.stringify(sample) });
  assert.equal(second.status, 200);
  const secondResult = await second.json();
  assert.equal(secondResult.skippedDuplicates, 2);
  assert.equal(secondResult.importedNotes, 0);
  assert.equal(secondResult.importedTasks, 0);

  const sync = await fetch(base + "/sync?since=0", { headers });
  assert.equal(sync.status, 200);
  const syncBody = await sync.json();
  assert.ok(syncBody.changes.some(change => change.type === "note"));
  assert.ok(syncBody.changes.some(change => change.type === "todo_list"));
});
