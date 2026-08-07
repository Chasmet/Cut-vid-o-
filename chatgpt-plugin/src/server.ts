import express from "express";
import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "1mb" }));
const PORT = Number(process.env.PORT ?? 3000);

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
  return [...unique];
}

function clipboardBlock(title: string, description: string, hashtags: string[]): string {
  const lines = [`Titre: ${title.trim()}`];
  if (description.trim()) lines.push(`Description: ${description.trim()}`);
  if (hashtags.length) lines.push(`Hashtags: ${hashtags.join(" ")}`);
  return lines.join("\n");
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
  title: z.string().trim().min(1).max(300),
  description: z.string().trim().max(5000).default(""),
  hashtags: z.array(z.string()).max(60).default([]),
});

function preparePublication(p: z.infer<typeof publicationSchema>) {
  assertAllowed(p.account, p.platform);
  const tags = normalizeHashtags(p.hashtags);
  return {
    ...p,
    hashtags: tags,
    account_label: accountLabel(p.account),
    account_confirmation: `Avant d'ouvrir ${p.platform.toUpperCase()}, vérifie que le compte ${accountLabel(p.account)} est actif.`,
    clipboard_text: clipboardBlock(p.title, p.description, tags),
  };
}

function createServer(): McpServer {
  const server = new McpServer({ name: "cut-video-publication-companion", version: "1.2.0" });

  server.registerTool("list_cutvideo_accounts", {
    title: "Lister les comptes autorisés",
    description: "Use this before preparing a publication when the user has not specified which Cut Vidéo account to use. Only these configured accounts and networks may be selected.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => ({
    structuredContent: {
      accounts: [
        { account: "chknoirshadow", label: "CHKNOIRSHADOW", platforms: ALLOWED.chknoirshadow },
        { account: "qg", label: "QG", platforms: ALLOWED.qg },
      ],
      rule: "If the account is not explicit, ask the user to choose the account before preparing or handing off a publication.",
    },
    content: [{ type: "text", text: "Comptes autorisés : CHKNOIRSHADOW (YouTube, TikTok, Instagram, X) ; QG (YouTube, TikTok)." }],
  }));

  server.registerTool("prepare_cutvideo_metadata", {
    title: "Préparer les métadonnées Cut Vidéo",
    description: "Use this when the user asks for publication metadata. The account must be explicit. If it is missing, ask the user which configured account to use before calling this tool.",
    inputSchema: {
      account: accountSchema,
      platform: platformSchema,
      title: z.string().trim().min(1).max(300),
      description: z.string().trim().max(5000).default(""),
      hashtags: z.array(z.string()).max(60).default([]),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ account, platform, title, description, hashtags }) => {
    assertAllowed(account, platform);
    const tags = normalizeHashtags(hashtags);
    const text = clipboardBlock(title, description, tags);
    return {
      structuredContent: {
        account,
        account_label: accountLabel(account),
        platform,
        title: title.trim(),
        description: description.trim(),
        hashtags: tags,
        clipboard_text: text,
        account_confirmation: `Avant d'ouvrir ${platform.toUpperCase()}, vérifie que le compte ${accountLabel(account)} est actif.`,
      },
      content: [{ type: "text", text: `Compte: ${accountLabel(account)}\nRéseau: ${platform.toUpperCase()}\n${text}` }],
    };
  });

  server.registerTool("prepare_cutvideo_publication_pack", {
    title: "Préparer métadonnées, programmation et fiche",
    description: "Use this as the main Cut Vidéo publication tool. It prepares an ordered schedule and dedicated fiche. Every item must use either CHKNOIRSHADOW or QG and only their allowed networks. Before opening a social app, instruct the user to make the required account active.",
    inputSchema: {
      project: z.string().trim().min(1).max(120),
      timezone: z.string().trim().default("Europe/Paris"),
      notes: z.string().trim().max(3000).default(""),
      publications: z.array(publicationSchema).min(1).max(100),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project, timezone, notes, publications }) => {
    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const fiche = [
      `FICHE PUBLICATION — ${project}`,
      `Fuseau: ${timezone}`,
      `Total: ${ordered.length}`,
      "",
      ...ordered.flatMap((p) => [
        `${p.order}. ${p.video_name}`,
        `Compte: ${p.account_label}`,
        `Réseau: ${p.platform.toUpperCase()}`,
        `Date: ${p.date} à ${p.time}`,
        `Statut: ${statusLabel(p.status)}`,
        p.visibility ? `Visibilité: ${p.visibility}` : "",
        `Titre: ${p.title}`,
        p.description ? `Description: ${p.description}` : "",
        p.hashtags.length ? `Hashtags: ${p.hashtags.join(" ")}` : "",
        `Action: ${p.account_confirmation}`,
        "",
      ]),
      notes ? "NOTES" : "",
      notes,
    ].filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();

    return {
      structuredContent: { project, timezone, publications: ordered, fiche_bloc: fiche },
      content: [{ type: "text", text: fiche }],
    };
  });

  return server;
}

app.get("/", (_req, res) => res.json({
  name: "Cut Vidéo ChatGPT Publication Companion",
  version: "1.2.0",
  status: "ok",
  mcp: "/mcp",
  accounts: { CHKNOIRSHADOW: ALLOWED.chknoirshadow, QG: ALLOWED.qg },
  purpose: "metadata, publication planning and dedicated fiche only",
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
