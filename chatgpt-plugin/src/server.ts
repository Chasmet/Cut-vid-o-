import express from "express";
import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "1mb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
  res.setHeader(
    "Access-Control-Allow-Headers",
    "Content-Type, Accept, Mcp-Session-Id, Last-Event-ID",
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

const platformSchema = z.enum(["youtube", "tiktok", "instagram", "x"]);
const accountSchema = z.enum(["chknoirshadow", "qg"]);
const statusSchema = z.enum(["a_programmer", "programme", "a_publier", "publie"]);

type Platform = z.infer<typeof platformSchema>;
type Account = z.infer<typeof accountSchema>;
type SessionRecord = {
  transport: StreamableHTTPServerTransport;
  server: McpServer;
};

const ALLOWED: Record<Account, Platform[]> = {
  chknoirshadow: ["youtube", "tiktok", "instagram", "x"],
  qg: ["youtube", "tiktok"],
};

const sessions = new Map<string, SessionRecord>();

function accountLabel(account: Account): string {
  return account === "chknoirshadow" ? "CHKNOIRSHADOW" : "QG";
}

function assertAllowed(account: Account, platform: Platform): void {
  if (!ALLOWED[account].includes(platform)) {
    throw new Error(`${accountLabel(account)} n'est pas autorisé sur ${platform.toUpperCase()}.`);
  }
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
  if ([...text].length > MAX_METADATA_CHARS) {
    throw new Error(
      `Les métadonnées font ${[...text].length} caractères. Réduis titre, description ou hashtags à ${MAX_METADATA_CHARS} caractères maximum au total.`,
    );
  }
  return text;
}

function statusLabel(status: z.infer<typeof statusSchema>): string {
  if (status === "programme") return "PROGRAMMÉ";
  if (status === "a_publier") return "À PUBLIER";
  if (status === "publie") return "PUBLIÉ";
  return "À PROGRAMMER";
}

const publicationSchema = z.object({
  order: z.number().int().min(1).max(999),
  video_name: z.string().trim().min(1).max(250),
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

function createServer(): McpServer {
  const server = new McpServer({
    name: "cut-video-publication-companion",
    version: "1.4.0",
  });

  server.registerTool(
    "list_cutvideo_accounts",
    {
      title: "Lister les comptes Cut Vidéo",
      description:
        "Use this only when the user has not specified the Cut Vidéo account. CHKNOIRSHADOW can use YouTube, TikTok, Instagram and X. QG can use YouTube and TikTok.",
      inputSchema: {},
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async () => ({
      structuredContent: {
        accounts: [
          {
            account: "chknoirshadow",
            label: "CHKNOIRSHADOW",
            platforms: ALLOWED.chknoirshadow,
          },
          { account: "qg", label: "QG", platforms: ALLOWED.qg },
        ],
      },
      content: [
        {
          type: "text",
          text: "CHKNOIRSHADOW : YouTube, TikTok, Instagram, X. QG : YouTube, TikTok.",
        },
      ],
    }),
  );

  server.registerTool(
    "prepare_cutvideo_metadata",
    {
      title: "Créer les métadonnées d'une vidéo",
      description:
        "Use this when the user asks ChatGPT to prepare metadata for one Cut Vidéo file. The metadata must describe the supplied video filename/topic, be suitable for the selected network, use at most 5 hashtags, and the complete title + description + hashtags block must be 100 characters maximum. Do not invent an unrelated subject.",
      inputSchema: {
        video_name: z.string().trim().min(1).max(250),
        account: accountSchema,
        platform: platformSchema,
        title: z.string().trim().min(1).max(70),
        description: z.string().trim().max(90).default(""),
        hashtags: z.array(z.string()).max(5).default([]),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ video_name, account, platform, title, description, hashtags }) => {
      assertAllowed(account, platform);
      const tags = normalizeHashtags(hashtags);
      const metadata = compactMetadata(title, description, tags);
      return {
        structuredContent: {
          video_name,
          account,
          account_label: accountLabel(account),
          platform,
          title: title.trim(),
          description: description.trim(),
          hashtags: tags,
          metadata_text: metadata,
          metadata_characters: [...metadata].length,
          max_characters: MAX_METADATA_CHARS,
          handoff: `Ouvrir ${platform.toUpperCase()} avec le compte ${accountLabel(account)} déjà connecté.`,
        },
        content: [{ type: "text", text: metadata }],
      };
    },
  );

  server.registerTool(
    "prepare_cutvideo_publication_pack",
    {
      title: "Programmer les publications et créer la fiche",
      description:
        "Use this as the main Cut Vidéo tool. For every supplied video file, create network-specific metadata that matches that file, stays within 100 characters total, then organize the requested date/time schedule and return one clean fiche for the application's dedicated block. Always surface each returned handoff_url so the user can open the prepared publication directly in the Cut Vidéo Android app. This tool does not cut videos and does not publish by API.",
      inputSchema: {
        project: z.string().trim().min(1).max(120),
        timezone: z.string().trim().default("Europe/Paris"),
        notes: z.string().trim().max(3000).default(""),
        publications: z.array(publicationSchema).min(1).max(100),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ project, timezone, notes, publications }) => {
      const ordered = [...publications]
        .sort((a, b) => a.order - b.order)
        .map(preparePublication);

      const fiche = [
        `FICHE PUBLICATION — ${project}`,
        `Fuseau: ${timezone}`,
        `Total: ${ordered.length}`,
        "",
        ...ordered.flatMap((p) => [
          `${p.order}. ${p.video_name}`,
          `${p.account_label} • ${p.platform.toUpperCase()}`,
          `${p.date} à ${p.time} • ${statusLabel(p.status)}`,
          p.visibility ? `Visibilité: ${p.visibility}` : "",
          `META (${p.metadata_characters}/${MAX_METADATA_CHARS})`,
          p.metadata_text,
          `Action: ${p.handoff}`,
          `Ouvrir dans Cut Vidéo: ${p.handoff_url}`,
          "",
        ]),
        notes ? "NOTES" : "",
        notes,
      ]
        .filter((line, index, array) => !(line === "" && array[index - 1] === ""))
        .join("\n")
        .trim();

      return {
        structuredContent: {
          project,
          timezone,
          max_metadata_characters: MAX_METADATA_CHARS,
          publications: ordered,
          fiche_bloc: fiche,
          handoff_urls: ordered.map((p) => ({
            video_name: p.video_name,
            platform: p.platform,
            account: p.account,
            url: p.handoff_url,
          })),
        },
        content: [{ type: "text", text: fiche }],
      };
    },
  );

  return server;
}

function sendMcpError(
  res: express.Response,
  status: number,
  message: string,
): void {
  res.status(status).json({
    jsonrpc: "2.0",
    error: { code: -32000, message },
    id: null,
  });
}

function htmlEscape(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

app.get("/", (_req, res) =>
  res.json({
    name: "Cut Vidéo ChatGPT Publication Companion",
    version: "1.4.0",
    status: "ok",
    mcp: "/mcp",
    handoff: "/handoff",
    max_metadata_characters: MAX_METADATA_CHARS,
    accounts: { CHKNOIRSHADOW: ALLOWED.chknoirshadow, QG: ALLOWED.qg },
    purpose: "metadata, scheduling and dedicated publication fiche only",
    privacy:
      "No video files, passwords or social-network credentials are handled by this server.",
  }),
);

app.get("/health", (_req, res) =>
  res.json({ status: "ok", sessions: sessions.size }),
);

app.get("/handoff", (req, res) => {
  const appUrl = new URL("cutvideo://import");
  const allowedKeys = [
    "video_name",
    "account",
    "platform",
    "date",
    "time",
    "title",
    "description",
    "hashtags",
    "visibility",
  ];

  for (const key of allowedKeys) {
    const value = req.query[key];
    if (typeof value === "string" && value.trim()) {
      appUrl.searchParams.set(key, value.trim());
    }
  }

  const target = appUrl.toString();
  const videoName = typeof req.query.video_name === "string"
    ? req.query.video_name
    : "publication";

  res.type("html").send(`<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Ouvrir dans Cut Vidéo</title>
  <style>
    body{font-family:system-ui,sans-serif;background:#101416;color:#fff;margin:0;display:grid;place-items:center;min-height:100vh}
    main{max-width:520px;padding:28px;text-align:center}
    a{display:inline-block;margin-top:18px;padding:15px 22px;border-radius:14px;background:#16b8a6;color:#fff;text-decoration:none;font-weight:700}
    p{color:#b9c3c7;line-height:1.5}
  </style>
</head>
<body>
  <main>
    <h1>Cut Vidéo</h1>
    <p>La programmation « ${htmlEscape(videoName)} » est prête à être importée dans l’application.</p>
    <a id="open" href="${htmlEscape(target)}">Ouvrir dans Cut Vidéo</a>
  </main>
  <script>
    const target = ${JSON.stringify(target)};
    setTimeout(() => { window.location.href = target; }, 250);
  </script>
</body>
</html>`);
});

app.post("/mcp", async (req, res) => {
  const sessionId = req.headers["mcp-session-id"];

  try {
    if (typeof sessionId === "string") {
      const existing = sessions.get(sessionId);
      if (!existing) {
        sendMcpError(res, 404, "MCP session not found");
        return;
      }
      await existing.transport.handleRequest(req, res, req.body);
      return;
    }

    if (!isInitializeRequest(req.body)) {
      sendMcpError(res, 400, "MCP initialization required");
      return;
    }

    const server = createServer();
    let transport: StreamableHTTPServerTransport;
    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id) => {
        sessions.set(id, { transport, server });
        console.log(`MCP session initialized: ${id}`);
      },
    });

    transport.onclose = () => {
      const id = transport.sessionId;
      if (id) {
        sessions.delete(id);
        console.log(`MCP session closed: ${id}`);
      }
      void server.close().catch(() => undefined);
    };

    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("MCP POST failed", error);
    if (!res.headersSent) {
      sendMcpError(res, 500, "MCP request failed");
    }
  }
});

async function handleExistingSession(
  req: express.Request,
  res: express.Response,
): Promise<void> {
  const sessionId = req.headers["mcp-session-id"];
  if (typeof sessionId !== "string") {
    sendMcpError(res, 400, "Missing Mcp-Session-Id header");
    return;
  }

  const existing = sessions.get(sessionId);
  if (!existing) {
    sendMcpError(res, 404, "MCP session not found");
    return;
  }

  try {
    await existing.transport.handleRequest(req, res);
  } catch (error) {
    console.error(`MCP ${req.method} failed`, error);
    if (!res.headersSent) {
      sendMcpError(res, 500, "MCP request failed");
    }
  }
}

app.get("/mcp", handleExistingSession);
app.delete("/mcp", handleExistingSession);

app.listen(PORT, "0.0.0.0", () =>
  console.log(`Cut Vidéo publication MCP listening on port ${PORT}`),
);
