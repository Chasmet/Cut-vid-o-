import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const endpointMarker = 'app.post("/mcp", async (req, res) => {';

const injected = String.raw`
const GPT_ACTION_API_KEY = String(process.env.CUTVIDEO_GPT_ACTION_KEY ?? "").trim();

function gptActionAuthorized(req: any): boolean {
  if (!GPT_ACTION_API_KEY) return false;
  const auth = String(req.headers?.authorization ?? "").trim();
  return auth === \`Bearer \${GPT_ACTION_API_KEY}\`;
}

function requireGptActionAuth(req: any, res: any): boolean {
  if (!GPT_ACTION_API_KEY) {
    res.status(503).json({ error: "Cut Video GPT Actions is not configured" });
    return false;
  }
  if (!gptActionAuthorized(req)) {
    res.status(401).setHeader("WWW-Authenticate", "Bearer").json({ error: "Unauthorized" });
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

function publicLibraryView(library: LibrarySnapshot) {
  return {
    app_version: library.app_version,
    synced_at_millis: library.synced_at_millis,
    projects: library.projects.map((project) => {
      const stats = projectStats(library, project);
      return {
        name: project.name,
        video_count: project.videos.length,
        pending_count: stats.pendingCount,
        videos: project.videos.map((video) => ({
          name: video.name,
          duration_ms: video.duration_ms,
          size_bytes: video.size_bytes,
          thumbnail_url: video.thumbnail_url,
          frame_urls: video.frame_urls,
          transcript: video.transcript,
        })),
        pending_videos: stats.pendingVideos.map((video) => ({
          name: video.name,
          duration_ms: video.duration_ms,
          thumbnail_url: video.thumbnail_url,
          frame_urls: video.frame_urls,
          transcript: video.transcript,
        })),
        existing_schedules: stats.schedules.map((schedule) => ({
          id: schedule.id,
          video_name: schedule.video_name,
          platform: schedule.platform,
          scheduled_at_millis: schedule.scheduled_at_millis,
          title: schedule.title,
          published: schedule.published,
          account: schedule.account,
        })),
      };
    }),
  };
}

app.get("/api/gpt/library", async (req, res) => {
  if (!requireGptActionAuth(req, res)) return;
  try {
    const library = await loadLibrary();
    if (!library) {
      res.status(409).json({
        error: "library_not_synced",
        message: "Ouvre Cut Video et attends Synchro ChatGPT OK.",
      });
      return;
    }
    const remote_schedule_commands = await recentRemoteScheduleCommands();
    res.json({
      ok: true,
      library: publicLibraryView(library),
      remote_schedule_commands,
      instruction: "Utilise uniquement les vrais noms de fichiers renvoyes par la bibliotheque.",
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
      res.status(409).json({
        error: "library_not_synced",
        message: "Ouvre Cut Video et attends Synchro ChatGPT OK.",
      });
      return;
    }

    const { project, timezone, publications } = parsed.data;
    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));
    const realProject = selected.project;
    const stats = projectStats(library, realProject);
    const realNames = new Set(realProject.videos.map((video) => normalizeName(video.name)));
    const invalid = publications.filter((publication) => !realNames.has(normalizeName(publication.video_name)));
    if (invalid.length > 0) {
      res.status(409).json({
        error: "invalid_video_names",
        project: realProject.name,
        invalid_video_names: invalid.map((item) => item.video_name),
        pending_videos: stats.pendingVideos.map((video) => video.name),
        message: "Recommence avec les vrais noms pending_videos.",
      });
      return;
    }

    const ordered = [...publications]
      .sort((a, b) => a.order - b.order)
      .map((publication) => preparePublication({ ...publication, status: "a_programmer" }));

    const remoteCommand = await enqueueRemoteScheduleCommand(realProject.name, ordered);
    res.status(202).json({
      ok: true,
      project: realProject.name,
      timezone,
      remote_command_id: remoteCommand.id,
      status: remoteCommand.status,
      device_status: "EN_ATTENTE_APK",
      device_confirmed: false,
      publications: ordered,
      message: "Commande envoyee a Cut Video. Ne considere la programmation comme confirmee qu apres status applied ou partial.",
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
      created_at_millis: command.created_at_millis,
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
  const base = "https://cut-video-chatgpt-mcp.onrender.com";
  res.json({
    openapi: "3.1.0",
    info: {
      title: "Cut Video Actions",
      version: "1.0.0",
      description: "Lit la bibliotheque Cut Video synchronisee et envoie de vraies commandes de programmation a l APK Android.",
    },
    servers: [{ url: base }],
    components: {
      securitySchemes: {
        bearerAuth: { type: "http", scheme: "bearer" },
      },
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
            date: { type: "string", description: "Date locale YYYY-MM-DD." },
            time: { type: "string", description: "Heure locale HH:mm." },
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
          description: "Retourne les dossiers, vrais noms de videos, videos en attente, programmations existantes et etat des commandes. Appeler avant toute creation de metas ou programmation.",
          responses: {
            "200": { description: "Bibliotheque synchronisee" },
            "401": { description: "Cle API invalide" },
            "409": { description: "APK pas encore synchronisee" },
          },
        },
      },
      "/api/gpt/schedule": {
        post: {
          operationId: "scheduleCutVideoPublications",
          summary: "Programmer reellement dans Cut Video",
          description: "Place une commande dans la file de l APK. La reponse queued signifie EN ATTENTE APK, pas encore programme. Verifier ensuite avec getCutVideoScheduleStatus.",
          requestBody: {
            required: true,
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  additionalProperties: false,
                  required: ["project", "publications"],
                  properties: {
                    project: { type: "string", description: "Nom exact du projet renvoye par getCutVideoLibrary." },
                    timezone: { type: "string", default: "Europe/Paris" },
                    publications: {
                      type: "array",
                      minItems: 1,
                      maxItems: 100,
                      items: { "$ref": "#/components/schemas/Publication" },
                    },
                  },
                },
              },
            },
          },
          responses: {
            "202": { description: "Commande acceptee et en attente de l APK" },
            "400": { description: "Requete invalide" },
            "401": { description: "Cle API invalide" },
            "409": { description: "Bibliotheque ou noms de fichiers invalides" },
          },
        },
      },
      "/api/gpt/schedule/{id}": {
        get: {
          operationId: "getCutVideoScheduleStatus",
          summary: "Verifier la programmation dans l APK",
          description: "Retourne queued, applied, partial ou failed. Dire PROGRAMME uniquement si le statut est applied; expliquer les manquants si partial.",
          parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }],
          responses: {
            "200": { description: "Etat de la commande" },
            "401": { description: "Cle API invalide" },
            "404": { description: "Commande introuvable" },
          },
        },
      },
    },
  });
});

app.get("/gpt-actions/privacy", (_req, res) => {
  res.type("html").send(`<!doctype html><html lang="fr"><meta charset="utf-8"><title>Cut Video - Confidentialite</title><body><h1>Politique de confidentialite - Cut Video</h1><p>Cut Video synchronise uniquement les donnees necessaires a la gestion des publications : noms de dossiers et de fichiers video, durees et tailles, metadonnees de publication, dates et heures de programmation, et trois images representatives compressees par video lorsque l analyse visuelle est activee.</p><p>Les fichiers video complets ne sont pas envoyes au serveur. Les images representatives sont conservees temporairement jusqu a 14 jours. Les commandes de programmation et leur historique sont conserves jusqu a 7 jours. Un identifiant technique d appareil est utilise pour autoriser la synchronisation.</p><p>Ces donnees servent exclusivement a lire la bibliotheque Cut Video, preparer des metadonnees et transmettre les programmations demandees par l utilisateur a son application Android. Elles ne sont pas vendues ni utilisees a des fins publicitaires.</p><p>Pour supprimer les donnees synchronisees, l utilisateur peut cesser la synchronisation et demander la suppression via la page support.</p></body></html>`);
});

app.get("/gpt-actions/terms", (_req, res) => {
  res.type("html").send(`<!doctype html><html lang="fr"><meta charset="utf-8"><title>Cut Video - Conditions</title><body><h1>Conditions d utilisation - Cut Video</h1><p>Cut Video fournit des outils de preparation et de programmation de publications. Une commande n est consideree comme reellement programmee qu apres confirmation de l APK Android. L utilisateur reste responsable du contenu publie, des comptes sociaux utilises et du respect des conditions des plateformes concernees.</p><p>Le service peut etre interrompu temporairement pour maintenance ou indisponibilite de l hebergement.</p></body></html>`);
});

app.get("/gpt-actions/support", (_req, res) => {
  res.type("html").send(`<!doctype html><html lang="fr"><meta charset="utf-8"><title>Cut Video - Support</title><body><h1>Support Cut Video</h1><p>Pour diagnostiquer une synchronisation, ouvrez Cut Video et verifiez le message « Synchro ChatGPT OK ». Pour une demande de suppression de donnees ou un probleme technique, utilisez le depot public du projet : Chasmet/Cut-vid-o- sur GitHub.</p></body></html>`);
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
