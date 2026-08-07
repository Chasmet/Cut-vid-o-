import express from "express";
import { randomUUID } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const app = express();
app.use(express.json({ limit: "1mb" }));

const PORT = Number(process.env.PORT ?? 3000);

const platformSchema = z.enum([
  "youtube",
  "tiktok",
  "instagram",
  "x",
  "facebook",
  "autre",
]);

function secondsToClock(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return h > 0
    ? `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function sanitizeBaseName(value: string): string {
  const cleaned = value
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9_-]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .replace(/_+/g, "_");
  return cleaned || "video";
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

function createServer(): McpServer {
  const server = new McpServer({
    name: "cut-video-companion",
    version: "1.0.0",
  });

  server.registerTool(
    "plan_video_cuts",
    {
      title: "Planifier les découpes Cut Vidéo",
      description:
        "Use this when the user wants to split a video into equal pieces before using the Android Cut Vidéo app. It calculates exact start/end times and MP4 filenames; it never uploads or edits the video.",
      inputSchema: {
        duration_seconds: z.number().positive().max(86400),
        segment_seconds: z.number().int().min(1).max(600),
        base_name: z.string().min(1).max(80).default("video"),
        start_index: z.number().int().min(1).max(9999).default(1),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ duration_seconds, segment_seconds, base_name, start_index }) => {
      const safeBase = sanitizeBaseName(base_name);
      const parts = [];
      let index = start_index;

      for (let start = 0; start < duration_seconds; start += segment_seconds) {
        const end = Math.min(start + segment_seconds, duration_seconds);
        parts.push({
          index,
          filename: `${safeBase}_${String(index).padStart(2, "0")}.mp4`,
          start_seconds: start,
          end_seconds: end,
          duration_seconds: end - start,
          start: secondsToClock(start),
          end: secondsToClock(end),
        });
        index += 1;
      }

      const structuredContent = {
        total_duration_seconds: duration_seconds,
        segment_seconds,
        count: parts.length,
        parts,
      };

      return {
        structuredContent,
        content: [
          {
            type: "text",
            text: `Plan prêt : ${parts.length} morceau(x) de ${segment_seconds} s maximum. La vidéo reste locale sur le téléphone.`,
          },
        ],
      };
    },
  );

  server.registerTool(
    "prepare_social_metadata",
    {
      title: "Préparer les métadonnées pour Cut Vidéo",
      description:
        "Use this when the user has a title, description/caption and hashtags for YouTube, TikTok, Instagram, X, Facebook or another app. It returns text ready to copy into Cut Vidéo's clipboard import.",
      inputSchema: {
        platform: platformSchema,
        title: z.string().trim().min(1).max(300),
        description: z.string().trim().max(5000).default(""),
        hashtags: z.array(z.string()).max(60).default([]),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ platform, title, description, hashtags }) => {
      const normalizedTags = normalizeHashtags(hashtags);
      const lines = [title.trim()];
      if (description.trim()) lines.push(description.trim());
      if (normalizedTags.length) lines.push(normalizedTags.join(" "));
      const clipboardText = lines.join("\n");

      return {
        structuredContent: {
          platform,
          title: title.trim(),
          description: description.trim(),
          hashtags: normalizedTags,
          clipboard_text: clipboardText,
        },
        content: [
          {
            type: "text",
            text: clipboardText,
          },
        ],
      };
    },
  );

  server.registerTool(
    "prepare_publication_pack",
    {
      title: "Préparer un pack multi-réseaux",
      description:
        "Use this when the user wants separate Cut Vidéo metadata blocks for several social networks. The model should write network-specific copy first, then call this tool to normalize it for clipboard import.",
      inputSchema: {
        items: z
          .array(
            z.object({
              platform: platformSchema,
              title: z.string().trim().min(1).max(300),
              description: z.string().trim().max(5000).default(""),
              hashtags: z.array(z.string()).max(60).default([]),
            }),
          )
          .min(1)
          .max(12),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ items }) => {
      const prepared = items.map((item) => {
        const tags = normalizeHashtags(item.hashtags);
        const lines = [item.title.trim()];
        if (item.description.trim()) lines.push(item.description.trim());
        if (tags.length) lines.push(tags.join(" "));
        return {
          platform: item.platform,
          title: item.title.trim(),
          description: item.description.trim(),
          hashtags: tags,
          clipboard_text: lines.join("\n"),
        };
      });

      return {
        structuredContent: { items: prepared },
        content: [
          {
            type: "text",
            text: prepared
              .map((item) => `[${item.platform.toUpperCase()}]\n${item.clipboard_text}`)
              .join("\n\n---\n\n"),
          },
        ],
      };
    },
  );

  server.registerTool(
    "recommend_cut_presets",
    {
      title: "Recommander des durées de découpe",
      description:
        "Use this when the user knows the video duration but is unsure whether to cut it into 15, 30, 60 or 90 second pieces. Returns the number of resulting clips for each Cut Vidéo preset.",
      inputSchema: {
        duration_seconds: z.number().positive().max(86400),
      },
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        openWorldHint: false,
        idempotentHint: true,
      },
    },
    async ({ duration_seconds }) => {
      const presets = [15, 30, 60, 90].map((seconds) => ({
        seconds,
        clips: Math.ceil(duration_seconds / seconds),
      }));
      return {
        structuredContent: { duration_seconds, presets },
        content: [
          {
            type: "text",
            text: presets.map((p) => `${p.seconds} s → ${p.clips} morceau(x)`).join("\n"),
          },
        ],
      };
    },
  );

  return server;
}

app.get("/", (_req, res) => {
  res.json({
    name: "Cut Vidéo ChatGPT Companion",
    status: "ok",
    mcp: "/mcp",
    privacy: "No video files are uploaded or processed by this server.",
  });
});

app.get("/health", (_req, res) => {
  res.json({ status: "ok" });
});

app.post("/mcp", async (req, res) => {
  const server = createServer();
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: () => randomUUID(),
  });

  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("MCP request failed", error);
    if (!res.headersSent) {
      res.status(500).json({ error: "MCP request failed" });
    }
  } finally {
    await transport.close().catch(() => undefined);
    await server.close().catch(() => undefined);
  }
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Cut Vidéo MCP listening on port ${PORT}`);
});
