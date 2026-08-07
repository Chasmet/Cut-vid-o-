import express from "express";
import { randomUUID } from "node:crypto";
import { createClient } from "redis";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "5mb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
  res.setHeader(
    "Access-Control-Allow-Headers",
    "Content-Type, Accept, Authorization, Mcp-Session-Id, Last-Event-ID",
  );
  res.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");
  if (req.method === "OPTIONS") {
    res.sendStatus(204);
    return;
  }
  next();
});

const PORT = Number(process.env.PORT ?? 3000);
const MAX_METADATA_CHARS = 100;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL
  ?? "https://cut-video-chatgpt-mcp.onrender.com").replace(/\/+$/, "");
const REDIS_URL = process.env.REDIS_URL ?? "";
const LIBRARY_KEY = "cutvideo:library:v1";
const DEVICE_TOKEN_KEY = "cutvideo:device-token:v1";
const PACK_PREFIX = "cutvideo:pack:";
const PACK_TTL_SECONDS = 7 * 24 * 60 * 60;

const redis = REDIS_URL ? createClient({ url: REDIS_URL }) : null;
let redisConnecting: Promise<void> | null = null;
if (redis) redis.on("error", (error) => console.error("Redis error", error));

async function getRedis() {
  if (!redis) throw new Error("Cut Vidéo storage is not configured.");
  if (!redis.isOpen) {
    if (!redisConnecting) {
      redisConnecting = redis.connect().then(() => undefined).finally(() => {
        redisConnecting = null;
      });
    }
    await redisConnecting;
  }
  return redis;
}

const platformSchema = z.enum(["youtube", "tiktok", "instagram", "x"]);
const accountSchema = z.enum(["chknoirshadow", "qg"]);
const statusSchema = z.enum(["a_programmer", "programme", "a_publier", "publie"]);

type Platform = z.infer<typeof platformSchema>;
type Account = z.infer<typeof accountSchema>;
type SessionRecord = { transport: StreamableHTTPServerTransport; server: McpServer };

const ALLOWED: Record<Account, Platform[]> = {
  chknoirshadow: ["youtube", "tiktok", "instagram", "x"],
  qg: ["youtube", "tiktok"],
};
const sessions = new Map<string, SessionRecord>();

const libraryVideoSchema = z.object({
  name: z.string().trim().min(1).max(300),
  duration_ms: z.number().int().nonnegative().default(0),
  size_bytes: z.number().int().nonnegative().default(0),
  date_added_seconds: z.number().int().nonnegative().default(0),
});
const libraryProjectSchema = z.object({
  name: z.string().trim().min(1).max(200),
  videos: z.array(libraryVideoSchema).max(2000),
});
const libraryScheduleSchema = z.object({
  id: z.string().trim().min(1).max(100),
  project: z.string().trim().max(200).default(""),
  video_name: z.string().trim().min(1).max(300),
  platform: z.string().trim().max(40),
  scheduled_at_millis: z.number().int().nonnegative(),
  title: z.string().max(200).default(""),
  description: z.string().max(1000).default(""),
  hashtags: z.string().max(500).default(""),
  visibility: z.string().max(80).default(""),
  account: z.string().max(80).default(""),
  published: z.boolean().default(false),
});
const librarySchema = z.object({
  synced_at_millis: z.number().int().nonnegative(),
  app_version: z.string().max(40).default(""),
  projects: z.array(libraryProjectSchema).max(500),
  schedules: z.array(libraryScheduleSchema).max(10000).default([]),
});
type LibrarySnapshot = z.infer<typeof librarySchema>;

const publicationSchema = z.object({
  order: z.number().int().min(1).max(9999),
  video_name: z.string().trim().min(1).max(300),
  account: accountSchema,
  platform: platformSchema,
  date: z.string().trim().min(8).max(20),
  time: z.string().trim().min(4).max(10),
  status: statusSchema.default("a_programmer"),
  visibility: z.string().trim().max(80).default(""),
  title: z.string().trim().min(1).max(70),
  description: z.string().trim().max(90).default(""),
  hashtags: z.array(z.string()).max(5).default([]),
});
type PublicationInput = z.infer<typeof publicationSchema>;

function accountLabel(account: Account): string {
  return account === "chknoirshadow" ? "CHKNOIRSHADOW" : "QG";
}
function assertAllowed(account: Account, platform: Platform): void {
  if (!ALLOWED[account].includes(platform)) {
    throw new Error(`${accountLabel(account)} n'est pas autorisé sur ${platform.toUpperCase()}.`);
  }
}
function normalizeName(value: string): string {
  return value.trim().toLocaleLowerCase("fr-FR");
}
function normalizeHashtags(values: string[]): string[] {
  const unique = new Set<string>();
  for (const raw of values) {
    for (const token of raw.split(/[\s,]+/)) {
      const cleaned = token.trim().replace(/^#+/, "").replace(/[^\p{L}\p{N}_-]/gu, "");
      if (cleaned) unique.add(`#${cleaned}`);
    }
  }
  return [...unique].slice(0, 5);
}
function compactMetadata(title: string, description: string, hashtags: string[]): string {
  const lines = [title.trim()];
  if (description.trim()) lines.push(description.trim());
  if (hashtags.length) lines.push(hashtags.join(" "));
  const text = lines.join("\n");
  const length = [...text].length;
  if (length > MAX_METADATA_CHARS) {
    throw new Error(`Les métadonnées font ${length} caractères. Maximum : ${MAX_METADATA_CHARS}.`);
  }
  return text;
}
function statusLabel(status: z.infer<typeof statusSchema>): string {
  if (status === "programme") return "PROGRAMMÉ";
  if (status === "a_publier") return "À PUBLIER";
  if (status === "publie") return "PUBLIÉ";
  return "À PROGRAMMER";
}

async function loadLibrary(): Promise<LibrarySnapshot | null> {
  const client = await getRedis();
  const raw = await client.get(LIBRARY_KEY);
  if (!raw) return null;
  const parsed = librarySchema.safeParse(JSON.parse(raw));
  return parsed.success ? parsed.data : null;
}
function findProject(library: LibrarySnapshot, requested: string) {
  const target = normalizeName(requested);
  const exact = library.projects.find((project) => normalizeName(project.name) === target);
  if (exact) return exact;
  const partial = library.projects.filter((project) => normalizeName(project.name).includes(target));
  return partial.length === 1 ? partial[0] : null;
}

async function deviceAuthorized(req: express.Request): Promise<boolean> {
  const authorization = req.header("authorization") ?? "";
  if (!authorization.toLowerCase().startsWith("bearer ")) return false;
  const incoming = authorization.slice(7).trim();
  if (!incoming) return false;
  const client = await getRedis();
  const stored = await client.get(DEVICE_TOKEN_KEY);
  return stored !== null && stored === incoming;
}

function buildHandoffUrl(p: PublicationInput, hashtags: string[]): string {
  const url = new URL("/handoff", PUBLIC_BASE_URL);
  url.searchParams.set("video_name", p.video_name);
  url.searchParams.set("account", p.account);
  url.searchParams.set("platform", p.platform);
  url.searchParams.set("date", p.date);
  url.searchParams.set("time", p.time);
  url.searchParams.set("title", p.title.trim());
  if (p.description.trim()) url.searchParams.set("description", p.description.trim());
  if (hashtags.length) url.searchParams.set("hashtags", hashtags.join(" "));
  if (p.visibility.trim()) url.searchParams.set("visibility", p.visibility.trim());
  return url.toString();
}
function preparePublication(p: PublicationInput) {
  assertAllowed(p.account, p.platform);
  const tags = normalizeHashtags(p.hashtags);
  const metadata = compactMetadata(p.title, p.description, tags);
  return {
    ...p,
    hashtags: tags,
    account_label: accountLabel(p.account),
    metadata_text: metadata,
    metadata_characters: [...metadata].length,
    source_file: p.video_name,
    handoff: `Ouvrir ${p.platform.toUpperCase()} avec le compte ${accountLabel(p.account)} déjà connecté.`,
    handoff_url: buildHandoffUrl(p, tags),
  };
}
async function savePack(project: string, publications: ReturnType<typeof preparePublication>[]) {
  const id = randomUUID();
  const payload = { id, project, created_at_millis: Date.now(), publications };
  const client = await getRedis();
  await client.set(`${PACK_PREFIX}${id}`, JSON.stringify(payload), { EX: PACK_TTL_SECONDS });
  return { id, import_url: `${PUBLIC_BASE_URL}/handoff-pack/${encodeURIComponent(id)}` };
}

function createServer(): McpServer {
  const server = new McpServer({ name: "cut-video-publication-companion", version: "1.5.1" });

  server.registerTool("list_cutvideo_accounts", {
    title: "Lister les comptes Cut Vidéo",
    description: "Use only when the user has not specified a Cut Vidéo account.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => ({
    structuredContent: {
      accounts: [
        { account: "chknoirshadow", label: "CHKNOIRSHADOW", platforms: ALLOWED.chknoirshadow },
        { account: "qg", label: "QG", platforms: ALLOWED.qg },
      ],
    },
    content: [{ type: "text", text: "CHKNOIRSHADOW : YouTube, TikTok, Instagram, X. QG : YouTube, TikTok." }],
  }));

  server.registerTool("list_cutvideo_library", {
    title: "Voir tous les projets Cut Vidéo",
    description: "Read the complete synchronized Android library before working on a project. It contains every real project/folder and video count.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'a pas encore été synchronisée. Ouvre l'APK une fois.");
    const projects = library.projects.map((project) => ({ name: project.name, video_count: project.videos.length }));
    return {
      structuredContent: {
        synced_at_millis: library.synced_at_millis,
        app_version: library.app_version,
        project_count: projects.length,
        total_videos: projects.reduce((sum, project) => sum + project.video_count, 0),
        projects,
      },
      content: [{ type: "text", text: projects.length ? projects.map((p) => `${p.name} (${p.video_count})`).join("\n") : "Aucun projet vidéo synchronisé." }],
    };
  });

  server.registerTool("get_cutvideo_project", {
    title: "Lire un projet Cut Vidéo",
    description: "Use before generating or scheduling a named project. Returns real ordered filenames and existing schedules. Never invent filenames.",
    inputSchema: { project: z.string().trim().min(1).max(200) },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    const found = findProject(library, project);
    if (!found) throw new Error(`Projet introuvable : ${project}. Utilise list_cutvideo_library.`);
    const schedules = library.schedules.filter((s) => normalizeName(s.project) === normalizeName(found.name));
    return {
      structuredContent: { project: found.name, videos: found.videos, schedules },
      content: [{ type: "text", text: found.videos.map((video, index) => `${index + 1}. ${video.name}`).join("\n") }],
    };
  });

  server.registerTool("prepare_cutvideo_metadata", {
    title: "Créer les métadonnées d'une vidéo",
    description: "Prepare metadata for one real Cut Vidéo file. Total title + description + hashtags must be at most 100 characters and at most 5 hashtags.",
    inputSchema: {
      video_name: z.string().trim().min(1).max(300),
      account: accountSchema,
      platform: platformSchema,
      title: z.string().trim().min(1).max(70),
      description: z.string().trim().max(90).default(""),
      hashtags: z.array(z.string()).max(5).default([]),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ video_name, account, platform, title, description, hashtags }) => {
    assertAllowed(account, platform);
    const tags = normalizeHashtags(hashtags);
    const metadata = compactMetadata(title, description, tags);
    return {
      structuredContent: {
        video_name, account, account_label: accountLabel(account), platform,
        title: title.trim(), description: description.trim(), hashtags: tags,
        metadata_text: metadata, metadata_characters: [...metadata].length,
        max_characters: MAX_METADATA_CHARS,
      },
      content: [{ type: "text", text: metadata }],
    };
  });

  server.registerTool("prepare_cutvideo_publication_pack", {
    title: "Programmer un lot Cut Vidéo",
    description: "Main scheduling tool. First read the synchronized project. Use only real filenames, stop at the requested last video, create different metadata for every video (100 characters max), and return one batch import link for Android.",
    inputSchema: {
      project: z.string().trim().min(1).max(200),
      timezone: z.string().trim().default("Europe/Paris"),
      notes: z.string().trim().max(3000).default(""),
      publications: z.array(publicationSchema).min(1).max(500),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false, idempotentHint: false },
  }, async ({ project, timezone, notes, publications }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("Ouvre d'abord Cut Vidéo afin de synchroniser toute la bibliothèque.");
    const realProject = findProject(library, project);
    if (!realProject) throw new Error(`Projet introuvable dans l'APK : ${project}.`);
    const realNames = new Set(realProject.videos.map((video) => normalizeName(video.name)));
    for (const publication of publications) {
      if (!realNames.has(normalizeName(publication.video_name))) {
        throw new Error(`La vidéo ${publication.video_name} n'existe pas dans ${realProject.name}.`);
      }
    }
    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const pack = await savePack(realProject.name, ordered);
    const fiche = [
      `FICHE PUBLICATION — ${realProject.name}`,
      `Fuseau: ${timezone}`,
      `Total: ${ordered.length}`,
      "",
      ...ordered.flatMap((p) => [
        `${p.order}. ${p.video_name}`,
        `${p.account_label} • ${p.platform.toUpperCase()}`,
        `${p.date} à ${p.time} • ${statusLabel(p.status)}`,
        `META (${p.metadata_characters}/${MAX_METADATA_CHARS})`,
        p.metadata_text,
        "",
      ]),
      notes ? "NOTES" : "", notes, "",
      `IMPORTER TOUT LE LOT DANS CUT VIDÉO: ${pack.import_url}`,
    ].filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();
    return {
      structuredContent: {
        project: realProject.name, timezone, max_metadata_characters: MAX_METADATA_CHARS,
        publications: ordered, fiche_bloc: fiche, batch_id: pack.id, import_all_url: pack.import_url,
      },
      content: [{ type: "text", text: fiche }],
    };
  });

  return server;
}

function sendMcpError(res: express.Response, status: number, message: string): void {
  res.status(status).json({ jsonrpc: "2.0", error: { code: -32000, message }, id: null });
}
function htmlEscape(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

app.get("/", (_req, res) => res.json({
  name: "Cut Vidéo ChatGPT Publication Companion", version: "1.5.1", status: "ok", mcp: "/mcp",
  library_sync: "/api/library/sync", max_metadata_characters: MAX_METADATA_CHARS,
  accounts: { CHKNOIRSHADOW: ALLOWED.chknoirshadow, QG: ALLOWED.qg },
  privacy: "Only project names, video metadata and schedules are synchronized. Video files are never uploaded.",
}));
app.get("/health", async (_req, res) => {
  let storage = "unavailable";
  try { const client = await getRedis(); await client.ping(); storage = "ok"; } catch (error) { console.error("Storage health failed", error); }
  res.json({ status: "ok", sessions: sessions.size, storage });
});

app.post("/api/library/pair", async (req, res) => {
  const token = typeof req.body?.device_token === "string" ? req.body.device_token.trim() : "";
  if (token.length < 24 || token.length > 200) {
    res.status(400).json({ error: "Invalid device token" });
    return;
  }
  try {
    const client = await getRedis();
    await client.setNX(DEVICE_TOKEN_KEY, token);
    const stored = await client.get(DEVICE_TOKEN_KEY);
    if (stored !== token) {
      res.status(403).json({ error: "Another Cut Vidéo device is already paired" });
      return;
    }
    res.json({ ok: true, paired: true });
  } catch (error) {
    console.error("Pairing failed", error);
    res.status(503).json({ error: "Cut Vidéo storage unavailable" });
  }
});

app.post("/api/library/sync", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Vidéo device" });
      return;
    }
    const parsed = librarySchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid Cut Vidéo library snapshot", details: parsed.error.issues });
      return;
    }
    const client = await getRedis();
    await client.set(LIBRARY_KEY, JSON.stringify(parsed.data));
    res.json({
      ok: true, synced_at_millis: parsed.data.synced_at_millis, projects: parsed.data.projects.length,
      videos: parsed.data.projects.reduce((sum, project) => sum + project.videos.length, 0),
      schedules: parsed.data.schedules.length,
    });
  } catch (error) {
    console.error("Library sync failed", error);
    res.status(503).json({ error: "Cut Vidéo storage unavailable" });
  }
});

app.get("/api/import-pack/:id", async (req, res) => {
  try {
    const client = await getRedis();
    const raw = await client.get(`${PACK_PREFIX}${req.params.id}`);
    if (!raw) { res.status(404).json({ error: "Publication pack not found or expired" }); return; }
    res.type("application/json").send(raw);
  } catch (error) {
    console.error("Pack fetch failed", error);
    res.status(503).json({ error: "Cut Vidéo storage unavailable" });
  }
});

app.get("/handoff", (req, res) => {
  const appUrl = new URL("cutvideo://import");
  const allowedKeys = ["video_name", "account", "platform", "date", "time", "title", "description", "hashtags", "visibility"];
  for (const key of allowedKeys) {
    const value = req.query[key];
    if (typeof value === "string" && value.trim()) appUrl.searchParams.set(key, value.trim());
  }
  const target = appUrl.toString();
  const videoName = typeof req.query.video_name === "string" ? req.query.video_name : "publication";
  res.type("html").send(`<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cut Vidéo</title><style>body{font-family:system-ui;background:#101416;color:#fff;display:grid;place-items:center;min-height:100vh;margin:0}main{text-align:center;padding:28px}a{display:inline-block;padding:15px 22px;background:#16b8a6;color:#fff;border-radius:14px;text-decoration:none;font-weight:700}</style></head><body><main><h1>Cut Vidéo</h1><p>${htmlEscape(videoName)}</p><a href="${htmlEscape(target)}">Ouvrir dans Cut Vidéo</a></main><script>setTimeout(()=>location.href=${JSON.stringify(target)},250)</script></body></html>`);
});
app.get("/handoff-pack/:id", (req, res) => {
  const id = String(req.params.id ?? "").trim();
  const target = `cutvideo://import-pack?id=${encodeURIComponent(id)}`;
  res.type("html").send(`<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Importer le lot Cut Vidéo</title><style>body{font-family:system-ui;background:#101416;color:#fff;display:grid;place-items:center;min-height:100vh;margin:0}main{max-width:520px;text-align:center;padding:28px}a{display:inline-block;padding:15px 22px;background:#16b8a6;color:#fff;border-radius:14px;text-decoration:none;font-weight:700}p{color:#b9c3c7}</style></head><body><main><h1>Cut Vidéo</h1><p>Le lot de programmation est prêt.</p><a href="${htmlEscape(target)}">Importer tout le lot</a></main><script>setTimeout(()=>location.href=${JSON.stringify(target)},250)</script></body></html>`);
});

app.post("/mcp", async (req, res) => {
  const sessionId = req.headers["mcp-session-id"];
  try {
    if (typeof sessionId === "string") {
      const existing = sessions.get(sessionId);
      if (!existing) { sendMcpError(res, 404, "MCP session not found"); return; }
      await existing.transport.handleRequest(req, res, req.body);
      return;
    }
    if (!isInitializeRequest(req.body)) { sendMcpError(res, 400, "MCP initialization required"); return; }
    const server = createServer();
    let transport: StreamableHTTPServerTransport;
    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id) => { sessions.set(id, { transport, server }); console.log(`MCP session initialized: ${id}`); },
    });
    transport.onclose = () => {
      const id = transport.sessionId;
      if (id) sessions.delete(id);
      void server.close().catch(() => undefined);
    };
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("MCP POST failed", error);
    if (!res.headersSent) sendMcpError(res, 500, "MCP request failed");
  }
});
async function handleExistingSession(req: express.Request, res: express.Response): Promise<void> {
  const sessionId = req.headers["mcp-session-id"];
  if (typeof sessionId !== "string") { sendMcpError(res, 400, "Missing Mcp-Session-Id header"); return; }
  const existing = sessions.get(sessionId);
  if (!existing) { sendMcpError(res, 404, "MCP session not found"); return; }
  try { await existing.transport.handleRequest(req, res); }
  catch (error) { console.error(`MCP ${req.method} failed`, error); if (!res.headersSent) sendMcpError(res, 500, "MCP request failed"); }
}
app.get("/mcp", handleExistingSession);
app.delete("/mcp", handleExistingSession);

app.listen(PORT, "0.0.0.0", () => console.log(`Cut Vidéo MCP v1.5.1 listening on port ${PORT}`));
