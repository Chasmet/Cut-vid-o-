import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const createServerMarker = "function createFullServer(): McpServer {";
const helperBlock = `
const ADMIN_COMMAND_PREFIX = "cutvideo:admin-command:";
const ADMIN_COMMAND_QUEUE_KEY = "cutvideo:admin-command-queue";
const ADMIN_COMMAND_HISTORY_KEY = "cutvideo:admin-command-history";
const ADMIN_COMMAND_TTL_SECONDS = 7 * 24 * 60 * 60;

type AdminCommand = {
  id: string;
  type: "app_action";
  action: string;
  payload: Record<string, unknown>;
  created_at_millis: number;
  acknowledged_at_millis?: number;
  status: "queued" | "applied" | "partial" | "failed";
  ack?: { applied: number; failed: number; message: string };
};

const NON_DESTRUCTIVE_ADMIN_ACTIONS = [
  "ping",
  "sync_now",
  "reschedule_all",
  "set_video_metadata",
  "create_schedule",
  "update_schedule",
  "create_collection",
  "rename_collection",
  "assign_folder",
  "rename_folder",
  "set_folder_note",
  "set_collection_note",
  "rename_video",
  "mark_schedule",
  "request_update",
] as const;

const DESTRUCTIVE_ADMIN_ACTIONS = [
  "delete_collection",
  "delete_video",
  "delete_schedule",
] as const;

async function enqueueAdminCommand(action: string, payload: Record<string, unknown>): Promise<AdminCommand> {
  const client = await getRedis();
  const command: AdminCommand = {
    id: randomUUID(),
    type: "app_action",
    action,
    payload,
    created_at_millis: Date.now(),
    status: "queued",
  };
  await client.set(ADMIN_COMMAND_PREFIX + command.id, JSON.stringify(command), { EX: ADMIN_COMMAND_TTL_SECONDS });
  await client.rPush(ADMIN_COMMAND_QUEUE_KEY, command.id);
  await client.rPush(ADMIN_COMMAND_HISTORY_KEY, command.id);
  await client.expire(ADMIN_COMMAND_QUEUE_KEY, ADMIN_COMMAND_TTL_SECONDS);
  await client.expire(ADMIN_COMMAND_HISTORY_KEY, ADMIN_COMMAND_TTL_SECONDS);
  return command;
}

async function pendingAdminCommands(): Promise<AdminCommand[]> {
  const client = await getRedis();
  const ids = await client.lRange(ADMIN_COMMAND_QUEUE_KEY, 0, 99);
  const commands: AdminCommand[] = [];
  for (const id of ids) {
    const raw = await client.get(ADMIN_COMMAND_PREFIX + id);
    if (!raw) {
      await client.lRem(ADMIN_COMMAND_QUEUE_KEY, 0, id);
      continue;
    }
    try {
      const command = JSON.parse(raw) as AdminCommand;
      if (command.status === "queued") commands.push(command);
      else await client.lRem(ADMIN_COMMAND_QUEUE_KEY, 0, id);
    } catch {
      await client.lRem(ADMIN_COMMAND_QUEUE_KEY, 0, id);
    }
  }
  return commands;
}

async function readAdminCommand(id: string): Promise<AdminCommand | null> {
  const client = await getRedis();
  const raw = await client.get(ADMIN_COMMAND_PREFIX + id);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AdminCommand;
  } catch {
    return null;
  }
}

async function waitAdminCommand(id: string, timeoutMs: number): Promise<AdminCommand> {
  const deadline = Date.now() + Math.max(0, timeoutMs);
  let current = await readAdminCommand(id);
  while (current && current.status === "queued" && Date.now() < deadline) {
    await new Promise<void>((resolve) => setTimeout(resolve, 750));
    current = await readAdminCommand(id);
  }
  if (current) return current;
  return {
    id,
    type: "app_action",
    action: "",
    payload: {},
    created_at_millis: Date.now(),
    status: "failed",
    ack: { applied: 0, failed: 1, message: "Commande introuvable." },
  };
}

async function recentAdminCommands(): Promise<Array<Record<string, unknown>>> {
  const client = await getRedis();
  const ids = await client.lRange(ADMIN_COMMAND_HISTORY_KEY, -20, -1);
  const result: Array<Record<string, unknown>> = [];
  for (const id of ids.reverse()) {
    const raw = await client.get(ADMIN_COMMAND_PREFIX + id);
    if (!raw) continue;
    try {
      const command = JSON.parse(raw) as AdminCommand;
      result.push({
        id: command.id,
        action: command.action,
        status: command.status,
        created_at_millis: command.created_at_millis,
        acknowledged_at_millis: command.acknowledged_at_millis ?? 0,
        ack: command.ack ?? null,
      });
    } catch {
      // Ignore malformed historical commands.
    }
  }
  return result;
}
`;

if (!source.includes("const ADMIN_COMMAND_PREFIX")) {
  if (!source.includes(createServerMarker)) throw new Error("MCP full access: createServer marker missing");
  source = source.replace(createServerMarker, helperBlock + "\n" + createServerMarker);
}

const toolBlock = `
  server.registerTool("get_cutvideo_capabilities", {
    title: "Capacités complètes Cut Vidéo",
    description: "Source de vérité des droits applicatifs du connecteur. Appelle cet outil si une demande semble ne pas avoir de droit ou si tu hésites sur l'outil à utiliser. Le propriétaire autorise le connecteur à lire et piloter toutes les fonctions Cut Vidéo exposées. Ne réponds jamais 'je n'ai pas le droit' pour une action listée ici. Les confirmations imposées par Android ou par la plateforme restent distinctes.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => {
    const library = await loadLibrary();
    const recent = await recentAdminCommands();
    return {
      structuredContent: {
        full_app_control: true,
        library_available: Boolean(library),
        app_version: library?.app_version ?? "",
        non_destructive_actions: NON_DESTRUCTIVE_ADMIN_ACTIONS,
        destructive_actions: DESTRUCTIVE_ADMIN_ACTIONS,
        direct_tools: [
          "list_cutvideo_accounts",
          "list_cutvideo_library",
          "get_cutvideo_work_context",
          "get_cutvideo_project",
          "get_cutvideo_folder",
          "get_cutvideo_video",
          "analyze_cutvideo_video",
          "prepare_cutvideo_metadata",
          "prepare_cutvideo_publication_pack",
          "save_cutvideo_publication_plan",
          "get_cutvideo_schedule",
          "cutvideo_control",
          "cutvideo_delete"
        ],
        recent_admin_commands: recent,
        instruction: "Pour toute action listée, utilise l'outil correspondant. Si Android exige une confirmation système, indique uniquement confirmation Android requise; ne présente pas cela comme un manque de droit MCP.",
      },
      content: [{
        type: "text",
        text: "Contrôle Cut Vidéo complet activé. Lecture, métadonnées, programmation, organisation, renommage, synchronisation, rappels et demande de mise à jour sont pilotables. Suppressions disponibles via cutvideo_delete. Seules les confirmations obligatoires d'Android restent hors du contrôle MCP.",
      }],
    };
  });

  server.registerTool("cutvideo_control", {
    title: "Piloter Cut Vidéo",
    description: "OUTIL ADMIN PRINCIPAL. Le propriétaire autorise ces actions applicatives: ping, sync_now, reschedule_all, set_video_metadata, create_schedule, update_schedule, create_collection, rename_collection, assign_folder, rename_folder, set_folder_note, set_collection_note, rename_video, mark_schedule, request_update. Utilise cet outil au lieu de répondre que tu n'as pas le droit. La commande est envoyée à l'APK et son accusé de réception est attendu. request_update demande à l'APK d'ouvrir le flux de mise à jour au prochain retour au premier plan; Android peut encore exiger sa confirmation d'installation.",
    inputSchema: {
      action: z.string().trim().min(1).max(80),
      payload: z.object({}).passthrough().default({}),
      wait_seconds: z.number().int().min(0).max(20).default(18),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false, idempotentHint: false },
  }, async ({ action, payload, wait_seconds }) => {
    if (!(NON_DESTRUCTIVE_ADMIN_ACTIONS as readonly string[]).includes(action)) {
      return {
        structuredContent: {
          status: "unsupported_action",
          requested_action: action,
          supported_actions: NON_DESTRUCTIVE_ADMIN_ACTIONS,
          destructive_actions: DESTRUCTIVE_ADMIN_ACTIONS,
          instruction: "Choisis l'action correspondante dans supported_actions. Pour une suppression utilise cutvideo_delete.",
        },
        content: [{ type: "text", text: "Action non reconnue: " + action + ". Utilise get_cutvideo_capabilities pour choisir une action correcte." }],
      };
    }
    const command = await enqueueAdminCommand(action, payload as Record<string, unknown>);
    const result = await waitAdminCommand(command.id, wait_seconds * 1000);
    return {
      structuredContent: {
        command_id: result.id,
        action,
        status: result.status,
        device_confirmed: result.status !== "queued",
        ack: result.ack ?? null,
        instruction: result.status === "applied"
          ? "Action réellement appliquée dans l'APK."
          : result.status === "partial"
            ? "Action partiellement appliquée. Rapporte le message ack."
            : result.status === "failed"
              ? "L'APK a refusé ou n'a pas pu appliquer l'action. Rapporte la cause exacte; ne dis pas manque de droit MCP."
              : "Commande en attente de l'APK. Demande d'ouvrir Cut Vidéo seulement si elle reste en attente.",
      },
      content: [{
        type: "text",
        text: result.status === "queued"
          ? "Commande " + action + " envoyée — EN ATTENTE APK."
          : "Commande " + action + " — " + result.status.toUpperCase() + " — " + (result.ack?.message ?? ""),
      }],
    };
  });

  server.registerTool("cutvideo_delete", {
    title: "Supprimer dans Cut Vidéo",
    description: "Outil réservé aux suppressions explicitement demandées par l'utilisateur: delete_collection, delete_video, delete_schedule. Le connecteur est autorisé à envoyer la commande à l'APK. Une confirmation Android peut rester nécessaire si le système protège le média.",
    inputSchema: {
      action: z.enum(["delete_collection", "delete_video", "delete_schedule"]),
      payload: z.object({}).passthrough().default({}),
      wait_seconds: z.number().int().min(0).max(20).default(18),
    },
    annotations: { readOnlyHint: false, destructiveHint: true, openWorldHint: false, idempotentHint: false },
  }, async ({ action, payload, wait_seconds }) => {
    const command = await enqueueAdminCommand(action, payload as Record<string, unknown>);
    const result = await waitAdminCommand(command.id, wait_seconds * 1000);
    return {
      structuredContent: {
        command_id: result.id,
        action,
        status: result.status,
        device_confirmed: result.status !== "queued",
        ack: result.ack ?? null,
      },
      content: [{
        type: "text",
        text: result.status === "queued"
          ? "Suppression " + action + " envoyée — EN ATTENTE APK."
          : "Suppression " + action + " — " + result.status.toUpperCase() + " — " + (result.ack?.message ?? ""),
      }],
    };
  });
`;

if (!source.includes('server.registerTool("get_cutvideo_capabilities"')) {
  const start = source.indexOf(createServerMarker);
  if (start < 0) throw new Error("MCP full access: createServer not found");
  const returnMarker = "\n  return server;\n}";
  const end = source.indexOf(returnMarker, start);
  if (end < 0) throw new Error("MCP full access: createServer return marker missing");
  source = source.slice(0, end) + "\n" + toolBlock + source.slice(end);
}

const endpointMarker = 'app.post("/mcp", async (req, res) => {';
const endpointBlock = `
app.get("/api/device/admin-commands", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Video device" });
      return;
    }
    const commands = await pendingAdminCommands();
    res.json({ ok: true, commands });
  } catch (error) {
    console.error("Admin command fetch failed", error);
    res.status(503).json({ error: "Cut Video admin queue unavailable" });
  }
});

app.post("/api/device/admin-commands/:id/ack", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Video device" });
      return;
    }
    const id = String(req.params.id ?? "").trim();
    const client = await getRedis();
    const raw = await client.get(ADMIN_COMMAND_PREFIX + id);
    if (!raw) {
      res.status(404).json({ error: "Admin command not found" });
      return;
    }
    const command = JSON.parse(raw) as AdminCommand;
    const requestedStatus = String(req.body?.status ?? "").trim();
    const status = requestedStatus === "applied" || requestedStatus === "partial" || requestedStatus === "failed"
      ? requestedStatus
      : "failed";
    command.status = status;
    command.acknowledged_at_millis = Date.now();
    command.ack = {
      applied: Math.max(0, Number(req.body?.applied ?? 0) || 0),
      failed: Math.max(0, Number(req.body?.failed ?? 0) || 0),
      message: String(req.body?.message ?? "").slice(0, 500),
    };
    await client.set(ADMIN_COMMAND_PREFIX + id, JSON.stringify(command), { EX: ADMIN_COMMAND_TTL_SECONDS });
    await client.lRem(ADMIN_COMMAND_QUEUE_KEY, 0, id);
    res.json({ ok: true, id, status: command.status, ack: command.ack });
  } catch (error) {
    console.error("Admin command acknowledgement failed", error);
    res.status(503).json({ error: "Cut Video admin acknowledgement unavailable" });
  }
});
`;

if (!source.includes('app.get("/api/device/admin-commands"')) {
  if (!source.includes(endpointMarker)) throw new Error("MCP full access: MCP endpoint marker missing");
  source = source.replace(endpointMarker, endpointBlock + "\n" + endpointMarker);
}

// Le mode stable à 3 outils était la principale restriction du connecteur.
// On garde le code de compatibilité, mais le serveur actif redevient le serveur complet.
source = source.replace("const server = createStableServer();", "const server = createFullServer();");
source = source.replaceAll('version: "2.5.0"', 'version: "3.0.0"');
source = source.replaceAll("Cut Vidéo MCP v2.5.0 listening on port", "Cut Vidéo MCP v3.0.0 listening on port");
source = source.replace(
  'connector_mode: "stable_3_tools",',
  'connector_mode: "full_access",\n  full_app_control: true,\n  admin_command_queue: true,',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_FULL_ACCESS_PATCH applied: full tool surface + admin queue + stable-3-tools restriction removed");
