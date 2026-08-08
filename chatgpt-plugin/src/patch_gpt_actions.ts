import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");
const endpointMarker = 'app.post("/mcp", async (req, res) => {';

const injected = String.raw`
const GPT_ACTION_API_KEY = String(process.env.CUTVIDEO_GPT_ACTION_KEY ?? "").trim();

function requireGptActionAuth(req: any, res: any): boolean {
  if (!GPT_ACTION_API_KEY) {
    res.status(503).json({ error: "gpt_actions_not_configured" });
    return false;
  }
  const auth = String(req.headers?.authorization ?? "").trim();
  if (auth !== "Bearer " + GPT_ACTION_API_KEY) {
    res.status(401).setHeader("WWW-Authenticate", "Bearer").json({ error: "unauthorized" });
    return false;
  }
  return true;
}

const gptActionPublicationSchema = z.object({
  order: z.number().int().min(1).max(999),
  video_name: z.string().trim().min(1).max(250),
  account: accountSchema,
  platform: platformSchema,
  date: z.string().trim().min(8).max(20),
  time: z.string().trim().min(4).max(10),
  visibility: z.string().trim().max(80).default("public"),
  title: z.string().trim().min(1).max(70),
  description: z.string().trim().max(90).default(""),
  hashtags: z.array(z.string()).max(5).default([]),
});

const gptActionScheduleSchema = z.object({
  project: z.string().trim().min(1).max(120),
  timezone: z.string().trim().default("Europe/Paris"),
  publications: z.array(gptActionPublicationSchema).min(1).max(100),
});

function gptLibraryView(library: any) {
  return {
    app_version: library.app_version,
    synced_at_millis: library.synced_at_millis,
    projects: library.projects.map((project: any) => {
      const stats = projectStats(library, project);
      return {
        name: project.name,
        video_count: project.videos.length,
        pending_count: stats.pendingCount,
        videos: project.videos,
        pending_videos: stats.pendingVideos,
        existing_schedules: stats.schedules,
      };
    }),
  };
}

app.get("/api/gpt/library", async (req, res) => {
  if (!requireGptActionAuth(req, res)) return;
  try {
    const library = await loadLibrary();
    if (!library) {
      res.status(409).json({ error: "library_not_synced", message: "Ouvre Cut Video et attends Synchro ChatGPT OK." });
      return;
    }
    res.json({
      ok: true,
      library: gptLibraryView(library),
      remote_schedule_commands: await recentRemoteScheduleCommands(),
    });
  } catch (error) {
    console.error("GPT Actions library failed", error);
    res.status(503).json({ error: "library_unavailable" });
  }
});

app.post("/api/gpt/schedule", async (req, res) => {
  if (!requireGptActionAuth(req, res)) return;
  try {
    const parsed = gptActionScheduleSchema.safeParse(req.body ?? {});
    if (!parsed.success) {
      res.status(400).json({ error: "invalid_request", details: parsed.error.flatten() });
      return;
    }
    const library = await loadLibrary();
    if (!library) {
      res.status(409).json({ error: "library_not_synced", message: "Ouvre Cut Video et attends Synchro ChatGPT OK." });
      return;
    }

    const data = parsed.data;
    const selected = selectProject(library, data.project, data.publications.map((item) => item.video_name));
    const realProject = selected.project;
    const stats = projectStats(library, realProject);
    const realNames = new Set(realProject.videos.map((video: any) => normalizeName(video.name)));
    const invalid = data.publications.filter((item) => !realNames.has(normalizeName(item.video_name)));
    if (invalid.length) {
      res.status(409).json({
        error: "invalid_video_names",
        project: realProject.name,
        invalid_video_names: invalid.map((item) => item.video_name),
        pending_videos: stats.pendingVideos.map((video: any) => video.name),
      });
      return;
    }

    const ordered = [...data.publications]
      .sort((a, b) => a.order - b.order)
      .map((item) => preparePublication({ ...item, status: "a_programmer" }));
    const remoteCommand = await enqueueRemoteScheduleCommand(realProject.name, ordered);

    res.status(202).json({
      ok: true,
      project: realProject.name,
      timezone: data.timezone,
      remote_command_id: remoteCommand.id,
      status: remoteCommand.status,
      device_status: "EN_ATTENTE_APK",
      device_confirmed: false,
      publications: ordered,
      message: "Verifier ensuite le statut. queued ne signifie pas encore programme.",
    });
  } catch (error) {
    console.error("GPT Actions scheduling failed", error);
    res.status(503).json({ error: "scheduling_unavailable" });
  }
});

app.get("/api/gpt/schedule/:id", async (req, res) => {
  if (!requireGptActionAuth(req, res)) return;
  try {
    const id = String(req.params.id ?? "").trim();
    const client = await getRedis();
    const raw = await client.get(REMOTE_COMMAND_PREFIX + id);
    if (!raw) {
      res.status(404).json({ error: "command_not_found" });
      return;
    }
    const command = JSON.parse(raw) as RemoteScheduleCommand;
    res.json({
      ok: true,
      remote_command_id: command.id,
      project: command.project,
      status: command.status,
      device_confirmed: command.status !== "queued",
      acknowledged_at_millis: command.acknowledged_at_millis ?? 0,
      ack: command.ack ?? null,
      publication_count: Array.isArray(command.publications) ? command.publications.length : 0,
    });
  } catch (error) {
    console.error("GPT Actions schedule status failed", error);
    res.status(503).json({ error: "schedule_status_unavailable" });
  }
});

app.get("/gpt-actions/openapi.json", (_req, res) => {
  res.json({
    openapi: "3.1.0",
    info: {
      title: "Cut Video Actions",
      version: "1.0.0",
      description: "Lit la bibliotheque Cut Video et envoie de vraies commandes de programmation a l application Android.",
    },
    servers: [{ url: "https://cut-video-chatgpt-mcp.onrender.com" }],
    components: {
      securitySchemes: { bearerAuth: { type: "http", scheme: "bearer" } },
      schemas: {
        Publication: {
          type: "object",
          additionalProperties: false,
          required: ["order", "video_name", "account", "platform", "date", "time", "title"],
          properties: {
            order: { type: "integer", minimum: 1, maximum: 999 },
            video_name: { type: "string", description: "Nom exact renvoye par getCutVideoLibrary." },
            account: { type: "string", enum: ["chknoirshadow", "qg"] },
            platform: { type: "string", enum: ["youtube", "tiktok", "instagram", "x"] },
            date: { type: "string", description: "YYYY-MM-DD" },
            time: { type: "string", description: "HH:mm" },
            visibility: { type: "string", default: "public" },
            title: { type: "string", maxLength: 70 },
            description: { type: "string", maxLength: 90 },
            hashtags: { type: "array", maxItems: 5, items: { type: "string" } },
          },
        },
      },
    },
    security: [{ bearerAuth: [] }],
    paths: {
      "/api/gpt/library": {
        get: {
          operationId: "getCutVideoLibrary",
          summary: "Lire la bibliotheque Cut Video",
          description: "Appeler avant toute creation de metas ou programmation. Retourne les vrais dossiers, fichiers, videos en attente et programmations existantes.",
          responses: { "200": { description: "OK" }, "401": { description: "Non autorise" }, "409": { description: "APK non synchronisee" } },
        },
      },
      "/api/gpt/schedule": {
        post: {
          operationId: "scheduleCutVideoPublications",
          summary: "Envoyer une vraie programmation a Cut Video",
          description: "Cree une commande pour l APK. Une reponse queued signifie EN ATTENTE APK. Verifier le statut ensuite.",
          requestBody: {
            required: true,
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  additionalProperties: false,
                  required: ["project", "publications"],
                  properties: {
                    project: { type: "string" },
                    timezone: { type: "string", default: "Europe/Paris" },
                    publications: { type: "array", minItems: 1, maxItems: 100, items: { "$ref": "#/components/schemas/Publication" } },
                  },
                },
              },
            },
          },
          responses: { "202": { description: "Commande en attente APK" }, "400": { description: "Requete invalide" }, "401": { description: "Non autorise" }, "409": { description: "Bibliotheque ou noms invalides" } },
        },
      },
      "/api/gpt/schedule/{id}": {
        get: {
          operationId: "getCutVideoScheduleStatus",
          summary: "Verifier si l APK a applique la programmation",
          description: "Dire PROGRAMME uniquement si status vaut applied. partial signifie application partielle et failed signifie echec.",
          parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }],
          responses: { "200": { description: "Etat courant" }, "401": { description: "Non autorise" }, "404": { description: "Commande introuvable" } },
        },
      },
    },
  });
});

app.get("/gpt-actions/privacy", (_req, res) => {
  res.type("text/plain").send("Politique de confidentialite Cut Video. Donnees synchronisees: noms de dossiers et fichiers, durees et tailles, metadonnees et programmations, identifiant technique d appareil, et jusqu a trois images representatives compressees par video lorsque l analyse est activee. Les videos completes ne sont pas envoyees. Les images sont conservees temporairement jusqu a 14 jours et les commandes de programmation jusqu a 7 jours. Les donnees servent uniquement au fonctionnement de Cut Video, ne sont pas vendues et ne sont pas utilisees pour la publicite. Pour une demande de suppression, utiliser la page support.");
});

app.get("/gpt-actions/terms", (_req, res) => {
  res.type("text/plain").send("Conditions Cut Video. Le service prepare des metadonnees et transmet des commandes de programmation a l application Android. Une commande est consideree comme reellement programmee uniquement apres confirmation de l APK. L utilisateur reste responsable de ses contenus, comptes sociaux et du respect des conditions des plateformes.");
});

app.get("/gpt-actions/support", (_req, res) => {
  res.type("text/plain").send("Support Cut Video. Pour diagnostiquer la synchronisation, ouvrir Cut Video et verifier Synchro ChatGPT OK. Depot de support: https://github.com/Chasmet/Cut-vid-o-");
});
`;

if (!source.includes('app.get("/api/gpt/library"')) {
  if (!source.includes(endpointMarker)) throw new Error("GPT Actions patch: MCP endpoint marker not found");
  source = source.replace(endpointMarker, injected + "\n" + endpointMarker);
}

source = source.replaceAll('version: "2.3.0"', 'version: "2.4.0"');
source = source.replaceAll("Cut Vidéo MCP v2.3.0 listening on port", "Cut Vidéo MCP v2.4.0 listening on port");
source = source.replace(
  "remote_schedule_confirmation_required: true,",
  "remote_schedule_confirmation_required: true,\n  gpt_actions_mobile_bridge: true,\n  gpt_actions_openapi: '/gpt-actions/openapi.json',",
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_GPT_ACTIONS_PATCH applied: authenticated mobile GPT Actions bridge + OpenAPI + policy pages");
