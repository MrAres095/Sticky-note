import Fastify from "fastify";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

const host = process.env.HOST || "0.0.0.0";
const port = Number(process.env.PORT || 8080);
const dataDir = process.env.DATA_DIR || "./data";
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
mkdirSync(dataDir, { recursive: true });
const db = new Database(join(dataDir, "sticky-notes.sqlite"));
db.pragma("journal_mode = WAL");
db.pragma("foreign_keys = ON");
db.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");

const app = Fastify({ logger: true, bodyLimit: 1048576 });
await app.register(helmet);
await app.register(cors, {
  origin: (origin, done) => {
    const allowed = process.env.CORS_ORIGIN || "https://notes.mandocloud.uk";
    if (!origin || origin === allowed) return done(null, true);
    return done(new Error("Origin not allowed"), false);
  },
  methods: ["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
  allowedHeaders: ["content-type", "authorization"]
});
app.get("/api/v1/health", async (_request, reply) => {
  const check = db.prepare("PRAGMA quick_check").get();
  const healthy = check && check.quick_check === "ok";
  if (!healthy) reply.code(503);
  return { status: healthy ? "ok" : "error", service: "sticky-note-api", apiVersion: "v1", database: healthy ? "ok" : "error", time: new Date().toISOString() };
});
app.get("/api/v1", async () => ({ service: "sticky-note-api", apiVersion: "v1", status: "initializing" }));
app.setNotFoundHandler((request, reply) => reply.code(404).send({ error: "not_found", message: "API ruta nije pronađena." }));
app.setErrorHandler((error, request, reply) => {
  request.log.error(error);
  reply.code(500).send({ error: "internal_error", message: "Došlo je do greške na serveru." });
});
app.addHook("onClose", async () => db.close());
try { await app.listen({ host, port }); }
catch (error) { app.log.error(error); db.close(); process.exit(1); }
