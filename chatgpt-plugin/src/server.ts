import express from "express";
import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "1mb" }));
const PORT = Number(process.env.PORT ?? 3000);
const MAX_METADATA_CHARS = 100;

const platformSchema = z.enum(["youtube", "tiktok", "instagram", "x"]);
const accountSchema = z.enum(["chknoirshadow", "qg"]);
const statusSchema = z.enum(["a_programmer", "programme", "a_publier", "publie"]);

type Platform = z.infer<typeof platformSchema>;
type Account = z.infer<typeof accountSchema>;

const ALLOWED: Record<Account, Platform[]> = {
  chknoirshadow: ["youtube", "tiktok", "instagram", "x"],
  qg: ["youtube", "tiktok"],
};

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
      `Les métadonnées font ${[...text].length} caractères. Réduis titre, description ou hashtags à ${MAX_METADATA_CHARS} caractères maximum au total.`
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

function preparePublication(p: z.infer<typeof publicationSchema>) {
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
  };
}

function createServer(): McpServer {
  const server = new McpServer({ name: "cut-video-publication-companion", version: "1.3.0" });

  server.registerTool("list_cutvideo_accounts", {
    title: "Lister les comptes Cut Vidéo",
    description: "Use this only when the user has not specified the Cut Vidéo account. CHKNOIRSHADOW can use YouTube, TikTok, Instagram and X. QG can use YouTube and TikTok.",
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

  server.registerTool("prepare_cutvideo_metadata", {
    title: "Créer les métadonnées d'une vidéo",
    description: "Use this when the user asks ChatGPT to prepare metadata for one Cut Vidéo file. The metadata must describe the supplied video filename/topic, be suitable for the selected network, use at most 5 hashtags, and the complete title + description + hashtags block must be 100 characters maximum. Do not invent an unrelated subject.",
    inputSchema: {
      video_name: z.string().trim().min(1).max(250),
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
  });

  server.registerTool("prepare_cutvideo_publication_pack", {
    title: "Programmer les publications et créer la fiche",
    description: "Use this as the main Cut Vidéo tool. For every supplied video file, create network-specific metadata that matches that file, stays within 100 characters total, then organize the requested date/time schedule and return one clean fiche for the application's dedicated block. This tool does not cut videos and does not publish by API.",
    inputSchema: {
      project: z.string().trim().min(1).max(120),
      timezone: z.string().trim().default("Europe/Paris"),
      notes: z.string().trim().max(3000).default(""),
      publications: z.array(publicationSchema).min(1).max(100),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project, timezone, notes, publications }) => {
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
        "",
      ]),
      notes ? "NOTES" : "",
      notes,
    ].filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();

    return {
      structuredContent: {
        project,
        timezone,
        max_metadata_characters: MAX_METADATA_CHARS,
        publications: ordered,
        fiche_bloc: fiche,
      },
      content: [{ type: "text", text: fiche }],
    };
  });

  return server;
}

app.get("/", (_req, res) => res.json({
  name: "Cut Vidéo ChatGPT Publication Companion",
  version: "1.3.0",
  status: "ok",
  mcp: "/mcp",
  max_metadata_characters: MAX_METADATA_CHARS,
  accounts: { CHKNOIRSHADOW: ALLOWED.chknoirshadow, QG: ALLOWED.qg },
  purpose: "metadata, scheduling and dedicated publication fiche only",
  privacy: "No video files, passwords or social-network credentials are handled by this server.",
}));

app.get("/health", (_req, res) => res.json({ status: "ok" }));
app.post("/mcp", async (req, res) => {
  const server = createServer();
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: () => randomUUID() });
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("MCP request failed", error);
    if (!res.headersSent) res.status(500).json({ error: "MCP request failed" });
  } finally {
    await transport.close().catch(() => undefined);
    await server.close().catch(() => undefined);
  }
});

app.listen(PORT, "0.0.0.0", () => console.log(`Cut Vidéo publication MCP listening on port ${PORT}`));
