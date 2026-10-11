import Fastify from "fastify";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import Database from "better-sqlite3";
import argon2 from "argon2";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { createHash, createHmac, randomBytes } from "node:crypto";

const host = process.env.HOST || "0.0.0.0";
const port = Number(process.env.PORT || 8080);
const dataDir = process.env.DATA_DIR || "./data";
const pepper = process.env.SESSION_PEPPER || "";
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
if (process.env.NODE_ENV === "production" && pepper.length < 32) throw new Error("SESSION_PEPPER must be at least 32 characters");
if (!pepper) console.warn("SESSION_PEPPER unset; configure a long random secret before exposing the server.");
mkdirSync(dataDir, { recursive: true });
const db = new Database(join(dataDir, "sticky-notes.sqlite"));
db.pragma("journal_mode = WAL");
db.pragma("foreign_keys = ON");
db.pragma("busy_timeout = 5000");
db.exec(`
CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT NOT NULL UNIQUE COLLATE NOCASE,password_hash TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS categories(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,name TEXT NOT NULL,color TEXT,updated_at INTEGER NOT NULL,deleted_at INTEGER,UNIQUE(user_id,name));
CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,title TEXT NOT NULL DEFAULT '',text TEXT NOT NULL DEFAULT '',category_id TEXT,color TEXT,favorite INTEGER NOT NULL DEFAULT 0,pinned INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,trashed INTEGER NOT NULL DEFAULT 0,reminder_at INTEGER,attachment_json TEXT NOT NULL DEFAULT '[]',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER,FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE SET NULL);
CREATE INDEX IF NOT EXISTS notes_user_updated_idx ON notes(user_id,updated_at);
CREATE TABLE IF NOT EXISTS todo_lists(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,title TEXT NOT NULL DEFAULT '',category_id TEXT,archived INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER,FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE SET NULL);
CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,list_id TEXT NOT NULL REFERENCES todo_lists(id) ON DELETE CASCADE,title TEXT NOT NULL DEFAULT '',completed INTEGER NOT NULL DEFAULT 0,reminder_at INTEGER,position INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER);
CREATE TABLE IF NOT EXISTS import_map(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,source TEXT NOT NULL,source_id TEXT NOT NULL,entity_id TEXT NOT NULL,PRIMARY KEY(user_id,source,source_id));
CREATE TABLE IF NOT EXISTS change_log(cursor INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,operation TEXT NOT NULL,changed_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS change_log_user_cursor_idx ON change_log(user_id,cursor);
`);
db.prepare("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(1,?)").run(new Date().toISOString());

const app = Fastify({ logger: true, bodyLimit: 25 * 1024 * 1024 });
await app.register(helmet);
await app.register(cors, {
  origin: (origin, done) => {
    const allowed = (process.env.CORS_ORIGIN || "https://notes.mandocloud.uk").split(",").map(s => s.trim());
    if (!origin || allowed.includes(origin)) return done(null, true);
    done(new Error("Origin not allowed"), false);
  },
  methods: ["GET","POST","PATCH","DELETE","OPTIONS"],
  allowedHeaders: ["content-type","authorization"]
});
const now = () => Date.now();
const id = () => randomBytes(16).toString("hex");
const digest = token => createHmac("sha256", pepper || "dev-only-secret").update(token).digest("hex");
const emailOk = value => typeof value === "string" && value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
const sendError = (reply, code, error, message) => reply.code(code).send({ error, message });
function sessionFor(userId) {
  const token = randomBytes(32).toString("base64url");
  const created = now(), expiresAt = created + 30 * 86400000;
  db.prepare("INSERT INTO sessions(token_hash,user_id,created_at,expires_at) VALUES(?,?,?,?)").run(digest(token),userId,created,expiresAt);
  return { token, expiresAt };
}
app.decorateRequest("auth", null);
app.addHook("onRequest", async (request, reply) => {
  if (!request.url.startsWith("/api/v1/") || ["/api/v1/health","/api/v1/auth/register","/api/v1/auth/login"].includes(request.url.split("?")[0])) return;
  const match = /^Bearer ([A-Za-z0-9_-]{30,})$/.exec(request.headers.authorization || "");
  if (!match) { sendError(reply,401,"unauthorized","Prijava je potrebna."); return; }
  const tokenHash = digest(match[1]);
  const session = db.prepare("SELECT s.user_id,s.expires_at,u.email FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?").get(tokenHash);
  if (!session || session.expires_at <= now()) {
    if (session) db.prepare("DELETE FROM sessions WHERE token_hash=?").run(tokenHash);
    sendError(reply,401,"session_expired","Sesija je istekla. Prijavite se ponovno."); return;
  }
  request.auth = { userId: session.user_id, email: session.email, tokenHash };
});
app.get("/api/v1/health", async (_req,reply) => {
  const check = db.prepare("PRAGMA quick_check").get()?.quick_check === "ok";
  if (!check) reply.code(503);
  return {status:check?"ok":"error",service:"sticky-note-api",apiVersion:"v1",database:check?"ok":"error",time:new Date().toISOString()};
});
app.get("/api/v1", async () => ({service:"sticky-note-api",apiVersion:"v1",status:"ready"}));
app.post("/api/v1/auth/register", async (request,reply) => {
  const email = typeof request.body?.email === "string" ? request.body.email.trim().toLowerCase() : "";
  const password = request.body?.password;
  if (!emailOk(email)) return sendError(reply,400,"invalid_email","Unesite valjanu e-mail adresu.");
  if (typeof password !== "string" || password.length < 12 || password.length > 128) return sendError(reply,400,"invalid_password","Lozinka mora imati 12–128 znakova.");
  const userId = id(), createdAt = now();
  try { db.prepare("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)").run(userId,email,await argon2.hash(password,{type:argon2.argon2id}),createdAt); }
  catch(e) { if (String(e.code).includes("CONSTRAINT")) return sendError(reply,409,"email_exists","Račun s tom e-mail adresom već postoji."); throw e; }
  return reply.code(201).send({user:{id:userId,email,createdAt},...sessionFor(userId)});
});
app.post("/api/v1/auth/login", async (request,reply) => {
  const email = typeof request.body?.email === "string" ? request.body.email.trim().toLowerCase() : "";
  const password = request.body?.password;
  if (!emailOk(email) || typeof password !== "string" || password.length > 128) return sendError(reply,400,"invalid_credentials","E-mail ili lozinka nisu valjani.");
  const user = db.prepare("SELECT id,email,password_hash,created_at FROM users WHERE email=?").get(email);
  if (!user || !(await argon2.verify(user.password_hash,password))) return sendError(reply,401,"invalid_credentials","E-mail ili lozinka nisu točni.");
  return {user:{id:user.id,email:user.email,createdAt:user.created_at},...sessionFor(user.id)};
});
app.get("/api/v1/auth/me", async request => ({user:{id:request.auth.userId,email:request.auth.email}}));
app.post("/api/v1/auth/logout", async (request,reply) => { db.prepare("DELETE FROM sessions WHERE token_hash=?").run(request.auth.tokenHash); return reply.code(204).send(); });

function logChange(userId,type,entityId,operation="upsert") {
  db.prepare("INSERT INTO change_log(user_id,entity_type,entity_id,operation,changed_at) VALUES(?,?,?,?,?)").run(userId,type,entityId,operation,now());
}
function getOrCreateCategory(userId,name,color=null) {
  const label = String(name || "").trim().slice(0,80);
  if (!label || label.toLowerCase() === "sve") return null;
  const existing = db.prepare("SELECT id FROM categories WHERE user_id=? AND name=? AND deleted_at IS NULL").get(userId,label);
  if (existing) return existing.id;
  const categoryId=id(), at=now();
  db.prepare("INSERT INTO categories(id,user_id,name,color,updated_at) VALUES(?,?,?,?,?)").run(categoryId,userId,label,color,at);
  logChange(userId,"category",categoryId);
  return categoryId;
}
const takeoutId = note => String(note?.id || note?.noteId || createHash("sha256").update(JSON.stringify({title:note?.title||"",text:note?.textContent||note?.text||"",list:note?.listContent||note?.todos||[],created:note?.createdTimestampUsec||note?.createdTime||"",edited:note?.userEditedTimestampUsec||note?.updatedTime||""})).digest("hex"));
const takeoutText = note => [note?.textContent || note?.text || "", ...(Array.isArray(note?.listContent) ? note.listContent.map(x => typeof x === "string" ? x : x?.text || "") : [])].filter(Boolean).join("\n");
app.post("/api/v1/import/keep/takeout", async (request,reply) => {
  const userId = request.auth.userId;
  const payload = request.body || {};
  const notes = Array.isArray(payload.notes) ? payload.notes : Array.isArray(payload) ? payload : null;
  if (!notes || notes.length > 5000) return sendError(reply,400,"invalid_takeout","Pošaljite JSON niz Keep bilješki ili objekt { notes: [...] } (najviše 5000 po zahtjevu).");
  const result = {importedNotes:0,importedLists:0,importedTasks:0,skippedDuplicates:0,skippedInvalid:0};
  const importOne = db.transaction(note => {
    if (!note || typeof note !== "object" || Array.isArray(note)) { result.skippedInvalid++; return; }
    const sourceId = takeoutId(note);
    if (!sourceId || sourceId === "undefined") { result.skippedInvalid++; return; }
    if (db.prepare("SELECT 1 FROM import_map WHERE user_id=? AND source='google_keep' AND source_id=?").get(userId,sourceId)) { result.skippedDuplicates++; return; }
    const labels = Array.isArray(note.labels) ? note.labels.map(x => typeof x === "string" ? x : x?.name).filter(Boolean) : [];
    const categoryId = labels.length ? getOrCreateCategory(userId,labels[0],note.color || null) : null;
    const title = String(note.title || "").slice(0,500);
    const text = String(note.textContent || note.text || "").slice(0,100000);
    const todoItems = Array.isArray(note.listContent) ? note.listContent : Array.isArray(note.todos) ? note.todos : null;
    const at=now();
    let entityId;
    if (todoItems) {
      entityId=id();
      db.prepare("INSERT INTO todo_lists(id,user_id,title,category_id,archived,created_at,updated_at) VALUES(?,?,?,?,?,?,?)").run(entityId,userId,title,categoryId,note.isArchived?1:0,at,at);
      logChange(userId,"todo_list",entityId);
      let position=0;
      for (const item of todoItems) {
        const taskTitle=String(typeof item === "string" ? item : item?.text || item?.textContent || "").trim().slice(0,2000);
        if (!taskTitle) continue;
        const taskId=id();
        db.prepare("INSERT INTO tasks(id,user_id,list_id,title,completed,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)").run(taskId,userId,entityId,taskTitle,(item?.isChecked || item?.checked || item?.isCompleted)?1:0,position++,at,at);
        logChange(userId,"task",taskId); result.importedTasks++;
      }
      result.importedLists++;
    } else {
      entityId=id();
      const isTrashed=!!note.isTrashed;
      db.prepare("INSERT INTO notes(id,user_id,title,text,category_id,color,favorite,pinned,archived,trashed,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
        .run(entityId,userId,title,text,categoryId,note.color||null,0,note.isPinned?1:0,note.isArchived?1:0,isTrashed?1:0,at,at);
      logChange(userId,"note",entityId); result.importedNotes++;
    }
    db.prepare("INSERT INTO import_map(user_id,source,source_id,entity_id) VALUES(?,?,?,?)").run(userId,"google_keep",sourceId,entityId);
  });
  for (const note of notes) importOne(note);
  return {source:"google_keep_takeout",...result,note:"Ponovljeni uvoz preskače već uvezene stavke. Provjerite rezultat prije brisanja izvozne kopije."};
});

app.get("/api/v1/sync", async request => {
  const since=Number(request.query?.since ?? 0);
  if (!Number.isSafeInteger(since)||since<0) throw Object.assign(new Error("Invalid cursor"),{statusCode:400});
  const changes=db.prepare("SELECT cursor,entity_type,entity_id,operation,changed_at FROM change_log WHERE user_id=? AND cursor>? ORDER BY cursor LIMIT 1000").all(request.auth.userId,since);
  return {changes:changes.map(change=>({cursor:change.cursor,type:change.entity_type,id:change.entity_id,operation:change.operation,changedAt:change.changed_at})),nextCursor:changes.length?changes[changes.length-1].cursor:since,hasMore:changes.length===1000};
});

function ownNote(userId, noteId) {
  return db.prepare("SELECT * FROM notes WHERE id=? AND user_id=? AND deleted_at IS NULL").get(noteId,userId);
}
function ownList(userId, listId) {
  return db.prepare("SELECT * FROM todo_lists WHERE id=? AND user_id=? AND deleted_at IS NULL").get(listId,userId);
}
function ownTask(userId, taskId) {
  return db.prepare("SELECT * FROM tasks WHERE id=? AND user_id=? AND deleted_at IS NULL").get(taskId,userId);
}
function textField(value, max, field) {
  if (typeof value !== "string") throw Object.assign(new Error(`\${field} must be text`),{statusCode:400});
  return value.trim().slice(0,max);
}
function categoryForUser(userId, categoryId) {
  if (categoryId == null || categoryId === "") return null;
  if (typeof categoryId !== "string") throw Object.assign(new Error("Invalid categoryId"),{statusCode:400});
  const category = db.prepare("SELECT id FROM categories WHERE id=? AND user_id=? AND deleted_at IS NULL").get(categoryId,userId);
  if (!category) throw Object.assign(new Error("Category not found"),{statusCode:400});
  return category.id;
}
const boolField = (value, fallback=0) => value === undefined ? fallback : (value === true || value === 1 ? 1 : value === false || value === 0 ? 0 : null);

// Categories
app.get("/api/v1/categories", async request => ({
  categories: db.prepare("SELECT id,name,color,updated_at AS updatedAt FROM categories WHERE user_id=? AND deleted_at IS NULL ORDER BY name COLLATE NOCASE").all(request.auth.userId)
}));
app.post("/api/v1/categories", async (request,reply) => {
  const userId=request.auth.userId, name=textField(request.body?.name,80,"name");
  if (!name) return sendError(reply,400,"invalid_name","Naziv kategorije je obavezan.");
  const categoryId=id(), at=now();
  try { db.prepare("INSERT INTO categories(id,user_id,name,color,updated_at) VALUES(?,?,?,?,?)").run(categoryId,userId,name,typeof request.body?.color==="string"?request.body.color.slice(0,32):null,at); }
  catch(e) { if (String(e.code).includes("CONSTRAINT")) return sendError(reply,409,"category_exists","Kategorija već postoji."); throw e; }
  logChange(userId,"category",categoryId);
  return reply.code(201).send({category:{id:categoryId,name,color:request.body?.color??null,updatedAt:at}});
});
app.patch("/api/v1/categories/:id", async (request,reply) => {
  const userId=request.auth.userId, old=db.prepare("SELECT * FROM categories WHERE id=? AND user_id=? AND deleted_at IS NULL").get(request.params.id,userId);
  if (!old) return sendError(reply,404,"not_found","Kategorija nije pronađena.");
  const name=request.body?.name===undefined?old.name:textField(request.body.name,80,"name");
  if (!name) return sendError(reply,400,"invalid_name","Naziv kategorije je obavezan.");
  const color=request.body?.color===undefined?old.color:(request.body.color===null?null:String(request.body.color).slice(0,32));
  const at=now();
  try { db.prepare("UPDATE categories SET name=?,color=?,updated_at=? WHERE id=? AND user_id=?").run(name,color,at,old.id,userId); }
  catch(e) { if (String(e.code).includes("CONSTRAINT")) return sendError(reply,409,"category_exists","Kategorija već postoji."); throw e; }
  logChange(userId,"category",old.id);
  return {category:{id:old.id,name,color,updatedAt:at}};
});
app.delete("/api/v1/categories/:id", async (request,reply) => {
  const userId=request.auth.userId, old=db.prepare("SELECT id FROM categories WHERE id=? AND user_id=? AND deleted_at IS NULL").get(request.params.id,userId);
  if (!old) return sendError(reply,404,"not_found","Kategorija nije pronađena.");
  const at=now(); db.prepare("UPDATE categories SET deleted_at=?,updated_at=? WHERE id=? AND user_id=?").run(at,at,old.id,userId);
  db.prepare("UPDATE notes SET category_id=NULL,updated_at=? WHERE user_id=? AND category_id=?").run(at,userId,old.id);
  db.prepare("UPDATE todo_lists SET category_id=NULL,updated_at=? WHERE user_id=? AND category_id=?").run(at,userId,old.id);
  logChange(userId,"category",old.id,"delete");
  return reply.code(204).send();
});

// Notes
app.get("/api/v1/notes", async request => {
  const query=request.query||{}, includeArchived=query.archived==="true", includeTrashed=query.trashed==="true";
  const notes=db.prepare("SELECT id,title,text,category_id AS categoryId,color,favorite,pinned,archived,trashed,reminder_at AS reminderAt,attachment_json AS attachments,created_at AS createdAt,updated_at AS updatedAt FROM notes WHERE user_id=? AND deleted_at IS NULL AND archived=? AND trashed=? ORDER BY pinned DESC,updated_at DESC")
    .all(request.auth.userId,includeArchived?1:0,includeTrashed?1:0).map(n=>({...n,favorite:!!n.favorite,pinned:!!n.pinned,archived:!!n.archived,trashed:!!n.trashed,attachments:JSON.parse(n.attachments)}));
  return {notes};
});
app.post("/api/v1/notes", async (request,reply) => {
  const userId=request.auth.userId, body=request.body||{}, noteId=id(), at=now();
  const title=body.title===undefined?"":textField(body.title,500,"title");
  const text=body.text===undefined?"":textField(body.text,100000,"text");
  const categoryId=categoryForUser(userId,body.categoryId);
  const color=body.color==null?null:String(body.color).slice(0,32);
  const favorite=boolField(body.favorite), pinned=boolField(body.pinned), archived=boolField(body.archived), trashed=boolField(body.trashed);
  if ([favorite,pinned,archived,trashed].includes(null)) return sendError(reply,400,"invalid_boolean","Status mora biti true ili false.");
  const reminderAt=body.reminderAt==null?null:Number(body.reminderAt);
  if (reminderAt!==null&&!Number.isSafeInteger(reminderAt)) return sendError(reply,400,"invalid_reminder","Neispravan podsjetnik.");
  db.prepare("INSERT INTO notes(id,user_id,title,text,category_id,color,favorite,pinned,archived,trashed,reminder_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").run(noteId,userId,title,text,categoryId,color,favorite,pinned,archived,trashed,reminderAt,at,at);
  logChange(userId,"note",noteId);
  return reply.code(201).send({note:{id:noteId,title,text,categoryId,color,favorite:!!favorite,pinned:!!pinned,archived:!!archived,trashed:!!trashed,reminderAt,attachments:[],createdAt:at,updatedAt:at}});
});
app.get("/api/v1/notes/:id", async (request,reply) => {
  const n=ownNote(request.auth.userId,request.params.id);
  if (!n) return sendError(reply,404,"not_found","Bilješka nije pronađena.");
  return {note:{id:n.id,title:n.title,text:n.text,categoryId:n.category_id,color:n.color,favorite:!!n.favorite,pinned:!!n.pinned,archived:!!n.archived,trashed:!!n.trashed,reminderAt:n.reminder_at,attachments:JSON.parse(n.attachment_json),createdAt:n.created_at,updatedAt:n.updated_at}};
});
app.patch("/api/v1/notes/:id", async (request,reply) => {
  const userId=request.auth.userId, n=ownNote(userId,request.params.id);
  if (!n) return sendError(reply,404,"not_found","Bilješka nije pronađena.");
  const b=request.body||{}, title=b.title===undefined?n.title:textField(b.title,500,"title"), text=b.text===undefined?n.text:textField(b.text,100000,"text");
  const categoryId=b.categoryId===undefined?n.category_id:categoryForUser(userId,b.categoryId);
  const color=b.color===undefined?n.color:(b.color===null?null:String(b.color).slice(0,32));
  const favorite=boolField(b.favorite,n.favorite), pinned=boolField(b.pinned,n.pinned), archived=boolField(b.archived,n.archived), trashed=boolField(b.trashed,n.trashed);
  if ([favorite,pinned,archived,trashed].includes(null)) return sendError(reply,400,"invalid_boolean","Status mora biti true ili false.");
  const reminderAt=b.reminderAt===undefined?n.reminder_at:(b.reminderAt===null?null:Number(b.reminderAt));
  if (reminderAt!==null&&!Number.isSafeInteger(reminderAt)) return sendError(reply,400,"invalid_reminder","Neispravan podsjetnik.");
  const at=now();
  db.prepare("UPDATE notes SET title=?,text=?,category_id=?,color=?,favorite=?,pinned=?,archived=?,trashed=?,reminder_at=?,updated_at=? WHERE id=? AND user_id=?").run(title,text,categoryId,color,favorite,pinned,archived,trashed,reminderAt,at,n.id,userId);
  logChange(userId,"note",n.id);
  return {note:{id:n.id,title,text,categoryId,color,favorite:!!favorite,pinned:!!pinned,archived:!!archived,trashed:!!trashed,reminderAt,attachments:JSON.parse(n.attachment_json),createdAt:n.created_at,updatedAt:at}};
});
app.delete("/api/v1/notes/:id", async (request,reply) => {
  const userId=request.auth.userId,n=ownNote(userId,request.params.id);
  if (!n) return sendError(reply,404,"not_found","Bilješka nije pronađena.");
  const at=now(); db.prepare("UPDATE notes SET deleted_at=?,updated_at=? WHERE id=? AND user_id=?").run(at,at,n.id,userId); logChange(userId,"note",n.id,"delete");
  return reply.code(204).send();
});

// To-do lists and tasks
app.get("/api/v1/todos", async request => {
  const archived=request.query?.archived==="true";
  const lists=db.prepare("SELECT id,title,category_id AS categoryId,archived,created_at AS createdAt,updated_at AS updatedAt FROM todo_lists WHERE user_id=? AND deleted_at IS NULL AND archived=? ORDER BY updated_at DESC").all(request.auth.userId,archived?1:0);
  return {lists:lists.map(list=>({...list,archived:!!list.archived,tasks:db.prepare("SELECT id,title,completed,position,reminder_at AS reminderAt,created_at AS createdAt,updated_at AS updatedAt FROM tasks WHERE user_id=? AND list_id=? AND deleted_at IS NULL ORDER BY position,created_at").all(request.auth.userId,list.id).map(task=>({...task,completed:!!task.completed}))}))};
});
app.post("/api/v1/todos", async (request,reply) => {
  const userId=request.auth.userId,b=request.body||{}, listId=id(),at=now(),title=b.title===undefined?"":textField(b.title,500,"title"),categoryId=categoryForUser(userId,b.categoryId),archived=boolField(b.archived);
  if (archived===null) return sendError(reply,400,"invalid_boolean","archived mora biti true ili false.");
  db.prepare("INSERT INTO todo_lists(id,user_id,title,category_id,archived,created_at,updated_at) VALUES(?,?,?,?,?,?,?)").run(listId,userId,title,categoryId,archived,at,at);
  logChange(userId,"todo_list",listId);
  return reply.code(201).send({list:{id:listId,title,categoryId,archived:!!archived,createdAt:at,updatedAt:at,tasks:[]}});
});
app.patch("/api/v1/todos/:id", async (request,reply) => {
  const userId=request.auth.userId,list=ownList(userId,request.params.id);
  if (!list) return sendError(reply,404,"not_found","Todo lista nije pronađena.");
  const b=request.body||{},title=b.title===undefined?list.title:textField(b.title,500,"title"),categoryId=b.categoryId===undefined?list.category_id:categoryForUser(userId,b.categoryId),archived=boolField(b.archived,list.archived);
  if (archived===null) return sendError(reply,400,"invalid_boolean","archived mora biti true ili false.");
  const at=now(); db.prepare("UPDATE todo_lists SET title=?,category_id=?,archived=?,updated_at=? WHERE id=? AND user_id=?").run(title,categoryId,archived,at,list.id,userId);
  logChange(userId,"todo_list",list.id);
  return {list:{id:list.id,title,categoryId,archived:!!archived,createdAt:list.created_at,updatedAt:at}};
});
app.delete("/api/v1/todos/:id", async (request,reply) => {
  const userId=request.auth.userId,list=ownList(userId,request.params.id);
  if (!list) return sendError(reply,404,"not_found","Todo lista nije pronađena.");
  const at=now(); db.prepare("UPDATE todo_lists SET deleted_at=?,updated_at=? WHERE id=? AND user_id=?").run(at,at,list.id,userId);
  db.prepare("UPDATE tasks SET deleted_at=?,updated_at=? WHERE user_id=? AND list_id=? AND deleted_at IS NULL").run(at,at,userId,list.id);
  logChange(userId,"todo_list",list.id,"delete");
  return reply.code(204).send();
});
app.post("/api/v1/todos/:id/tasks", async (request,reply) => {
  const userId=request.auth.userId,list=ownList(userId,request.params.id);
  if (!list) return sendError(reply,404,"not_found","Todo lista nije pronađena.");
  const b=request.body||{},title=textField(b.title,2000,"title");
  if (!title) return sendError(reply,400,"invalid_title","Naziv zadatka je obavezan.");
  const taskId=id(),at=now(),position=Number.isSafeInteger(b.position)&&b.position>=0?b.position:(db.prepare("SELECT COALESCE(MAX(position),-1)+1 AS p FROM tasks WHERE user_id=? AND list_id=? AND deleted_at IS NULL").get(userId,list.id).p);
  const reminderAt=b.reminderAt==null?null:Number(b.reminderAt);
  if (reminderAt!==null&&!Number.isSafeInteger(reminderAt)) return sendError(reply,400,"invalid_reminder","Neispravan podsjetnik.");
  db.prepare("INSERT INTO tasks(id,user_id,list_id,title,completed,reminder_at,position,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)").run(taskId,userId,list.id,title,0,reminderAt,position,at,at);
  db.prepare("UPDATE todo_lists SET updated_at=? WHERE id=?").run(at,list.id);
  logChange(userId,"task",taskId); logChange(userId,"todo_list",list.id);
  return reply.code(201).send({task:{id:taskId,title,completed:false,position,reminderAt,createdAt:at,updatedAt:at,listId:list.id}});
});
app.patch("/api/v1/tasks/:id", async (request,reply) => {
  const userId=request.auth.userId,task=ownTask(userId,request.params.id);
  if (!task) return sendError(reply,404,"not_found","Zadatak nije pronađen.");
  const b=request.body||{},title=b.title===undefined?task.title:textField(b.title,2000,"title"),completed=boolField(b.completed,task.completed);
  if (completed===null) return sendError(reply,400,"invalid_boolean","completed mora biti true ili false.");
  const reminderAt=b.reminderAt===undefined?task.reminder_at:(b.reminderAt===null?null:Number(b.reminderAt));
  if (reminderAt!==null&&!Number.isSafeInteger(reminderAt)) return sendError(reply,400,"invalid_reminder","Neispravan podsjetnik.");
  const position=b.position===undefined?task.position:Number(b.position);
  if (!Number.isSafeInteger(position)||position<0) return sendError(reply,400,"invalid_position","Neispravan redoslijed.");
  const at=now(); db.prepare("UPDATE tasks SET title=?,completed=?,reminder_at=?,position=?,updated_at=? WHERE id=? AND user_id=?").run(title,completed,reminderAt,position,at,task.id,userId);
  db.prepare("UPDATE todo_lists SET updated_at=? WHERE id=?").run(at,task.list_id);
  logChange(userId,"task",task.id); logChange(userId,"todo_list",task.list_id);
  return {task:{id:task.id,title,completed:!!completed,position,reminderAt,createdAt:task.created_at,updatedAt:at,listId:task.list_id}};
});
app.delete("/api/v1/tasks/:id", async (request,reply) => {
  const userId=request.auth.userId,task=ownTask(userId,request.params.id);
  if (!task) return sendError(reply,404,"not_found","Zadatak nije pronađen.");
  const at=now(); db.prepare("UPDATE tasks SET deleted_at=?,updated_at=? WHERE id=? AND user_id=?").run(at,at,task.id,userId);
  db.prepare("UPDATE todo_lists SET updated_at=? WHERE id=?").run(at,task.list_id);
  logChange(userId,"task",task.id,"delete"); logChange(userId,"todo_list",task.list_id);
  return reply.code(204).send();
});

app.setNotFoundHandler((_request,reply)=>reply.code(404).send({error:"not_found",message:"API ruta nije pronađena."}));
app.setErrorHandler((error,request,reply)=>{request.log.error(error);if(!reply.sent)reply.code(error.statusCode&&error.statusCode<500?error.statusCode:500).send({error:"internal_error",message:"Došlo je do greške na serveru."});});
app.addHook("onClose",async()=>db.close());
try { await app.listen({host,port}); } catch(error) { app.log.error(error); db.close(); process.exit(1); }
