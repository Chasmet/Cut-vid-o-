import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

source = source.replace(
  'const PACK_TTL_SECONDS = 7 * 24 * 60 * 60;\n',
  'const PACK_TTL_SECONDS = 7 * 24 * 60 * 60;\nconst FRAME_PREFIX = "cutvideo:frame:";\nconst FRAME_TTL_SECONDS = 14 * 24 * 60 * 60;\nconst MAX_FRAME_BYTES = 180_000;\n',
);

if (!source.includes("async function loadVideoFrameContents")) {
  source = source.replace(
`async function savePack(project: string, publications: ReturnType<typeof preparePublication>[]) {
  const id = randomUUID();
  const payload = { id, project, created_at_millis: Date.now(), publications };
  const client = await getRedis();
  await client.set(\`${'${PACK_PREFIX}'}${'${id}'}\`, JSON.stringify(payload), { EX: PACK_TTL_SECONDS });
  return { id, import_url: \`${'${PUBLIC_BASE_URL}'}/handoff-pack/${'${encodeURIComponent(id)}'}\` };
}
`,
`async function savePack(project: string, publications: ReturnType<typeof preparePublication>[]) {
  const id = randomUUID();
  const payload = { id, project, created_at_millis: Date.now(), publications };
  const client = await getRedis();
  await client.set(\`${'${PACK_PREFIX}'}${'${id}'}\`, JSON.stringify(payload), { EX: PACK_TTL_SECONDS });
  return { id, import_url: \`${'${PUBLIC_BASE_URL}'}/handoff-pack/${'${encodeURIComponent(id)}'}\` };
}

async function loadVideoFrameContents(video: LibraryProject["videos"][number]) {
  const urls = [...new Set([video.thumbnail_url, ...video.frame_urls].filter(Boolean))].slice(0, 4);
  if (!urls.length) return [];
  const client = await getRedis();
  const images: Array<{ type: "image"; data: string; mimeType: string }> = [];
  for (const rawUrl of urls) {
    try {
      const parsed = new URL(rawUrl);
      const id = decodeURIComponent(parsed.pathname.split("/").filter(Boolean).pop() ?? "");
      if (!id) continue;
      const stored = await client.get(\`${'${FRAME_PREFIX}'}${'${id}'}\`);
      if (!stored) continue;
      const record = JSON.parse(stored) as { mime_type?: string; data_base64?: string };
      const mimeType = record.mime_type === "image/png" ? "image/png" : "image/jpeg";
      const data = typeof record.data_base64 === "string" ? record.data_base64 : "";
      if (!data) continue;
      images.push({ type: "image", data, mimeType });
    } catch {
      // Ignore une frame expirée ou invalide et continue avec les autres.
    }
  }
  return images;
}
`,
  );
}

// Legacy-schema compatibility: the visible publication-pack tool can also return real frames.
source = source.replace(
`    if (!library) throw new Error("Ouvre d'abord Cut Vidéo quelques secondes afin de synchroniser la bibliothèque.");
    const compatibilityRead = project === "__CUTVIDEO_LIBRARY__"`,
`    if (!library) throw new Error("Ouvre d'abord Cut Vidéo quelques secondes afin de synchroniser la bibliothèque.");
    const compatibilityAnalyze = project === "__CUTVIDEO_ANALYZE__";
    if (compatibilityAnalyze) {
      const requestedVideo = publications.find((publication) => publication.video_name && publication.video_name !== "__CUTVIDEO_ANALYZE__")?.video_name
        ?? publications[0]?.video_name
        ?? "";
      if (!requestedVideo || requestedVideo === "__CUTVIDEO_ANALYZE__") {
        throw new Error("Indique le vrai nom du fichier vidéo dans video_name pour l'analyse.");
      }
      const entry = resolveVideoEntry(library, "", requestedVideo);
      const frameContents = await loadVideoFrameContents(entry.video);
      return {
        structuredContent: {
          mode: "video_analysis_compat",
          project: entry.project.name,
          video: entry.video,
          frame_count: frameContents.length,
          analysis_available: frameContents.length > 0 || Boolean(entry.video.transcript.trim()),
          instruction: frameContents.length
            ? "Analyse visuellement les images renvoyées. Utilise uniquement ce qui est réellement visible."
            : "Aucune frame synchronisée pour cette vidéo. Ouvre la nouvelle version de Cut Vidéo afin de synchroniser les images.",
        },
        content: [
          {
            type: "text",
            text: frameContents.length
              ? \`ANALYSE VISUELLE CUT VIDÉO — ${'${entry.project.name}'} / ${'${entry.video.name}'} — ${'${frameContents.length}'} frame(s)\`
              : \`Aucune frame synchronisée pour ${'${entry.video.name}'}.\`,
          },
          ...frameContents,
        ],
      };
    }
    const compatibilityRead = project === "__CUTVIDEO_LIBRARY__"`,
);

// Make the dedicated analysis tool return actual MCP image content instead of only URLs.
source = source.replace(
`      content: [{
        type: "text",
        text: analysisAvailable
          ? \`Média disponible pour ${'${entry.video.name}'} : miniature=${'${hasThumbnail}'}, frames=${'${hasFrames}'}, transcription=${'${hasTranscript}'}\`
          : \`Aucune frame ni transcription synchronisée pour ${'${entry.video.name}'}.\`,
      }],`,
`      content: [
        {
          type: "text",
          text: analysisAvailable
            ? \`Média disponible pour ${'${entry.video.name}'} : miniature=${'${hasThumbnail}'}, frames=${'${hasFrames}'}, transcription=${'${hasTranscript}'}\`
            : \`Aucune frame ni transcription synchronisée pour ${'${entry.video.name}'}.\`,
        },
        ...(await loadVideoFrameContents(entry.video)),
      ],`,
);

// Frame upload endpoint: authenticated by the paired Android device token.
if (!source.includes('app.post("/api/library/frames"')) {
  source = source.replace(
`app.post("/api/library/sync", async (req, res) => {`,
`app.post("/api/library/frames", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Vidéo device" });
      return;
    }
    const project = typeof req.body?.project === "string" ? req.body.project.trim() : "";
    const videoName = typeof req.body?.video_name === "string" ? req.body.video_name.trim() : "";
    const signature = typeof req.body?.signature === "string" ? req.body.signature.trim() : "";
    const frames = Array.isArray(req.body?.frames) ? req.body.frames.slice(0, 4) : [];
    if (!videoName || !signature || !frames.length) {
      res.status(400).json({ error: "Invalid Cut Vidéo frame bundle" });
      return;
    }

    const client = await getRedis();
    const frameUrls: string[] = [];
    for (const frame of frames) {
      const mimeType = frame?.mime_type === "image/png" ? "image/png" : "image/jpeg";
      const dataBase64 = typeof frame?.data_base64 === "string" ? frame.data_base64.trim() : "";
      if (!dataBase64) continue;
      let bytes: Buffer;
      try {
        bytes = Buffer.from(dataBase64, "base64");
      } catch {
        continue;
      }
      if (!bytes.length || bytes.length > MAX_FRAME_BYTES) {
        res.status(413).json({ error: "Frame too large", max_bytes: MAX_FRAME_BYTES });
        return;
      }
      const id = randomUUID();
      await client.set(
        \`${'${FRAME_PREFIX}'}${'${id}'}\`,
        JSON.stringify({
          project,
          video_name: videoName,
          signature,
          mime_type: mimeType,
          data_base64: dataBase64,
          created_at_millis: Date.now(),
        }),
        { EX: FRAME_TTL_SECONDS },
      );
      frameUrls.push(\`${'${PUBLIC_BASE_URL}'}/api/media/frame/${'${encodeURIComponent(id)}'}\`);
    }
    res.json({
      ok: true,
      project,
      video_name: videoName,
      frame_count: frameUrls.length,
      thumbnail_url: frameUrls[0] ?? "",
      frame_urls: frameUrls,
      ttl_seconds: FRAME_TTL_SECONDS,
    });
  } catch (error) {
    console.error("Frame sync failed", error);
    res.status(503).json({ error: "Cut Vidéo frame storage unavailable" });
  }
});

app.get("/api/media/frame/:id", async (req, res) => {
  try {
    const id = String(req.params.id ?? "").trim();
    if (!/^[0-9a-f-]{20,80}$/i.test(id)) {
      res.status(404).end();
      return;
    }
    const client = await getRedis();
    const raw = await client.get(\`${'${FRAME_PREFIX}'}${'${id}'}\`);
    if (!raw) {
      res.status(404).end();
      return;
    }
    const record = JSON.parse(raw) as { mime_type?: string; data_base64?: string };
    const mimeType = record.mime_type === "image/png" ? "image/png" : "image/jpeg";
    const dataBase64 = typeof record.data_base64 === "string" ? record.data_base64 : "";
    if (!dataBase64) {
      res.status(404).end();
      return;
    }
    const bytes = Buffer.from(dataBase64, "base64");
    res.setHeader("Content-Type", mimeType);
    res.setHeader("Cache-Control", "private, max-age=3600");
    res.send(bytes);
  } catch (error) {
    console.error("Frame fetch failed", error);
    res.status(503).end();
  }
});

app.post("/api/library/sync", async (req, res) => {`,
  );
}

source = source.replaceAll(
  'version: "2.0.0"',
  'version: "2.1.0"',
);
source = source.replace(
  'automatic_project_selection: true,\n  autopilot: true,',
  'automatic_project_selection: true,\n  frame_analysis: true,\n  frame_count_per_video: 3,\n  autopilot: true,',
);
source = source.replace(
  'Cut Vidéo MCP v2.0.0 listening on port',
  'Cut Vidéo MCP v2.1.0 listening on port',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_MEDIA_PATCH applied: authenticated frame sync + MCP image analysis + legacy analysis mode");
