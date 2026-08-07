import express from "express";
import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "1mb" }));

const PORT = Number(process.env.PORT ?? 3000);
const platformSchema = z.enum(["youtube", "tiktok", "instagram", "x", "facebook", "autre"]);
const statusSchema = z.enum(["a_programmer", "programme", "a_publier", "publie"]);

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

function createServer(): McpServer {
  const server = new McpServer({ name: "cut-video-publication-companion", version: "1.1.0" });

  server.registerTool(
    "prepare_cutvideo_metadata",
    {
      title: "Préparer les métadonnées Cut Vidéo",
      description:
        "Use this when the user asks ChatGPT to prepare publication metadata for a video. Return only optimized title, description and hashtags in the exact clipboard format understood by the Cut Vidéo Android app.",
      inputSchema: {
        platform: platformSchema,
        title: z.string().trim().min(1).max(300),
        description: z.string().trim().max(5000).default(""),
        hashtags: z.array(z.string()).max(60).default([]),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
    },
    async ({ platform, title, description, hashtags }) => {
      const tags = normalizeHashtags(hashtags);
      const text = clipboardBlock(title, description, tags);
      return {
        structuredContent: { platform, title: title.trim(), description: description.trim(), hashtags: tags, clipboard_text: text },
        content: [{ type: "text", text }],
      };
    },
  );

  server.registerTool(
    "prepare_cutvideo_publication_sheet",
    {
      title: "Créer la fiche de programmation Cut Vidéo",
      description:
        "Use this when the user wants ChatGPT to organize one or more planned social publications for Cut Vidéo. Produce a clean ordered schedule plus a compact fiche that can be pasted into the app's dedicated notes block. Do not edit or cut video files.",
      inputSchema: {
        fiche_title: z.string().trim().min(1).max(120),
        timezone: z.string().trim().default("Europe/Paris"),
        notes: z.string().trim().max(3000).default(""),
        publications: z.array(z.object({
          order: z.number().int().min(1).max(999),
          video_name: z.string().trim().min(1).max(250),
          platform: platformSchema,
          date: z.string().trim().min(8).max(20),
          time: z.string().trim().min(4).max(10),
          visibility: z.string().trim().max(80).default(""),
          status: statusSchema.default("a_programmer"),
          title: z.string().trim().min(1).max(300),
          description: z.string().trim().max(5000).default(""),
          hashtags: z.array(z.string()).max(60).default([]),
        })).min(1).max(100),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
    },
    async ({ fiche_title, timezone, notes, publications }) => {
      const ordered = [...publications]
        .sort((a, b) => a.order - b.order)
        .map((p) => {
          const tags = normalizeHashtags(p.hashtags);
          return { ...p, hashtags: tags, clipboard_text: clipboardBlock(p.title, p.description, tags) };
        });

      const ficheLines = [
        `FICHE PUBLICATION — ${fiche_title}`,
        `Fuseau: ${timezone}`,
        `Total: ${ordered.length} publication(s)`,
        "",
      ];

      for (const p of ordered) {
        ficheLines.push(
          `#${p.order} — ${p.video_name}`,
          `Réseau: ${p.platform.toUpperCase()}`,
          `Date: ${p.date} à ${p.time}`,
          `Statut: ${statusLabel(p.status)}`,
          p.visibility ? `Visibilité: ${p.visibility}` : "",
          `Titre: ${p.title}`,
          p.description ? `Description: ${p.description}` : "",
          p.hashtags.length ? `Hashtags: ${p.hashtags.join(" ")}` : "",
          ""
        );
      }

      if (notes) ficheLines.push("NOTES", notes);
      const fiche = ficheLines.filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();

      return {
        structuredContent: {
          fiche_title,
          timezone,
          count: ordered.length,
          publications: ordered,
          fiche_bloc: fiche,
        },
        content: [{ type: "text", text: fiche }],
      };
    },
  );

  server.registerTool(
    "prepare_cutvideo_publication_pack",
    {
      title: "Préparer métadonnées + programmation + fiche",
      description:
        "Use this as the main Cut Vidéo companion tool when the user asks for a complete publication plan. ChatGPT should decide the copy and schedule from the user's request, then this tool formats everything into an ordered list, per-video clipboard metadata, and one fiche for the app's dedicated block.",
      inputSchema: {
        project: z.string().trim().min(1).max(120),
        timezone: z.string().trim().default("Europe/Paris"),
        publications: z.array(z.object({
          order: z.number().int().min(1).max(999),
          video_name: z.string().trim().min(1).max(250),
          platform: platformSchema,
          date: z.string().trim().min(8).max(20),
          time: z.string().trim().min(4).max(10),
          status: statusSchema.default("a_programmer"),
          visibility: z.string().trim().max(80).default(""),
          title: z.string().trim().min(1).max(300),
          description: z.string().trim().max(5000).default(""),
          hashtags: z.array(z.string()).max(60).default([]),
        })).min(1).max(100),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
    },
    async ({ project, timezone, publications }) => {
      const ordered = [...publications].sort((a, b) => a.order - b.order).map((p) => {
        const tags = normalizeHashtags(p.hashtags);
        return { ...p, hashtags: tags, clipboard_text: clipboardBlock(p.title, p.description, tags) };
      });

      const fiche = [
        `FICHE PUBLICATION — ${project}`,
        `Fuseau: ${timezone}`,
        `Total: ${ordered.length}`,
        "",
        ...ordered.flatMap((p) => [
          `${p.order}. ${p.video_name} — ${p.platform.toUpperCase()}`,
          `${p.date} ${p.time} — ${statusLabel(p.status)}${p.visibility ? ` — ${p.visibility}` : ""}`,
          `Titre: ${p.title}`,
          p.description ? `Description: ${p.description}` : "",
          p.hashtags.length ? `Hashtags: ${p.hashtags.join(" ")}` : "",
          "",
        ]),
      ].filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();

      return {
        structuredContent: { project, timezone, publications: ordered, fiche_bloc: fiche },
        content: [{ type: "text", text: fiche }],
      };
    },
  );

  return server;
}

app.get("/", (_req, res) => {
  res.json({
    name: "Cut Vidéo ChatGPT Publication Companion",
    version: "1.1.0",
    status: "ok",
    mcp: "/mcp",
    purpose: "metadata, publication planning and dedicated fiche only",
    privacy: "No video files are uploaded or processed by this server.",
  });
});

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
