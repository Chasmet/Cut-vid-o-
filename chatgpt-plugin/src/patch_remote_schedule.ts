import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const helperMarker = "function createStableServer(): McpServer {";
const helpers = `
const REMOTE_COMMAND_PREFIX = "cutvideo:remote-schedule:";
const REMOTE_COMMAND_QUEUE_KEY = "cutvideo:remote-schedule-queue";
const REMOTE_COMMAND_HISTORY_KEY = "cutvideo:remote-schedule-history";
const REMOTE_COMMAND_TTL_SECONDS = 7 * 24 * 60 * 60;

type RemoteScheduleCommand = {
  id: string;
  type: "schedule_publications";
  project: string;
  created_at_millis: number;
  acknowledged_at_millis?: number;
  status: "queued" | "applied" | "partial" | "failed";
  publications: unknown[];
  ack?: { applied: number; missing: number; invalid: number };
};

async function enqueueRemoteScheduleCommand(project: string, publications: unknown[]): Promise<RemoteScheduleCommand> {
  const client = await getRedis();
  const command: RemoteScheduleCommand = {
    id: randomUUID(),
    type: "schedule_publications",
    project,
    created_at_millis: Date.now(),
    status: "queued",
    publications,
  };
  await client.set(REMOTE_COMMAND_PREFIX + command.id, JSON.stringify(command), { EX: REMOTE_COMMAND_TTL_SECONDS });
  await client.rPush(REMOTE_COMMAND_QUEUE_KEY, command.id);
  await client.rPush(REMOTE_COMMAND_HISTORY_KEY, command.id);
  await client.expire(REMOTE_COMMAND_QUEUE_KEY, REMOTE_COMMAND_TTL_SECONDS);
  await client.expire(REMOTE_COMMAND_HISTORY_KEY, REMOTE_COMMAND_TTL_SECONDS);
  return command;
}

async function pendingRemoteScheduleCommands(): Promise<RemoteScheduleCommand[]> {
  const client = await getRedis();
  const ids = await client.lRange(REMOTE_COMMAND_QUEUE_KEY, 0, 99);
  const commands: RemoteScheduleCommand[] = [];
  for (const id of ids) {
    const raw = await client.get(REMOTE_COMMAND_PREFIX + id);
    if (!raw) {
      await client.lRem(REMOTE_COMMAND_QUEUE_KEY, 0, id);
      continue;
    }
    try {
      const command = JSON.parse(raw) as RemoteScheduleCommand;
      if (command.status === "queued") commands.push(command);
      else await client.lRem(REMOTE_COMMAND_QUEUE_KEY, 0, id);
    } catch {
      await client.lRem(REMOTE_COMMAND_QUEUE_KEY, 0, id);
    }
  }
  return commands;
}

async function recentRemoteScheduleCommands(): Promise<Array<Record<string, unknown>>> {
  const client = await getRedis();
  const ids = await client.lRange(REMOTE_COMMAND_HISTORY_KEY, -20, -1);
  const result: Array<Record<string, unknown>> = [];
  for (const id of ids.reverse()) {
    const raw = await client.get(REMOTE_COMMAND_PREFIX + id);
    if (!raw) continue;
    try {
      const command = JSON.parse(raw) as RemoteScheduleCommand;
      result.push({
        id: command.id,
        project: command.project,
        status: command.status,
        created_at_millis: command.created_at_millis,
        acknowledged_at_millis: command.acknowledged_at_millis ?? 0,
        publication_count: Array.isArray(command.publications) ? command.publications.length : 0,
        ack: command.ack ?? null,
      });
    } catch {
      // Ignore malformed history entries.
    }
  }
  return result;
}
`;

if (!source.includes("REMOTE_COMMAND_QUEUE_KEY")) {
  if (!source.includes(helperMarker)) throw new Error("MCP 2.3 patch: stable server marker not found");
  source = source.replace(helperMarker, helpers + "\n" + helperMarker);
}

const queueMarker = `    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);\n    const pack = await savePack(realProject.name, ordered);\n    const fiche = [`;
const queueReplacement = `    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);\n    const pack = await savePack(realProject.name, ordered);\n    const remoteCommand = await enqueueRemoteScheduleCommand(realProject.name, ordered);\n    const fiche = [`;
if (source.includes(queueMarker)) {
  source = source.replace(queueMarker, queueReplacement);
} else if (!source.includes("const remoteCommand = await enqueueRemoteScheduleCommand")) {
  throw new Error("MCP 2.3 patch: publication queue marker not found");
}

source = source.replace(
  '${p.date} a ${p.time} • ${statusLabel(p.status)}',
  '${p.date} a ${p.time} • EN ATTENTE APK',
);

source = source.replace(
  '      notes ? "NOTES" : "", notes,\n      `IMPORTER: ${pack.import_url}`',
  '      notes ? "NOTES" : "", notes,\n      "ETAT APK: EN ATTENTE DE SYNCHRO — pas encore confirme comme programme",\n      `IMPORTER MANUELLEMENT SI BESOIN: ${pack.import_url}`',
);

source = source.replace(
  '        import_all_url: pack.import_url,\n      },',
  '        import_all_url: pack.import_url,\n        remote_command_id: remoteCommand.id,\n        device_status: "en_attente_apk",\n        device_confirmed: false,\n        instruction: "La commande est en file d attente. Ne dis PROGRAMME qu apres confirmation APK visible dans remote_schedule_commands ou dans la bibliotheque synchronisee.",\n      },',
);

source = source.replace(
  '    const recommended = recommendedProject(library);\n    const projects = library.projects.map((project) => {',
  '    const remoteCommands = await recentRemoteScheduleCommands();\n    const recommended = recommendedProject(library);\n    const projects = library.projects.map((project) => {',
);

source = source.replace(
  '        recommended_project: recommended?.name ?? "",\n        projects,\n        instruction:',
  '        recommended_project: recommended?.name ?? "",\n        projects,\n        remote_schedule_commands: remoteCommands,\n        instruction:',
);

const endpointMarker = 'app.post("/mcp", async (req, res) => {';
const endpoints = `
app.get("/api/device/commands", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Video device" });
      return;
    }
    const commands = await pendingRemoteScheduleCommands();
    res.json({ ok: true, commands });
  } catch (error) {
    console.error("Remote command fetch failed", error);
    res.status(503).json({ error: "Remote scheduling unavailable" });
  }
});

app.post("/api/device/commands/:id/ack", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Video device" });
      return;
    }
    const id = String(req.params.id ?? "").trim();
    const client = await getRedis();
    const raw = await client.get(REMOTE_COMMAND_PREFIX + id);
    if (!raw) {
      res.status(404).json({ error: "Remote schedule command not found" });
      return;
    }
    const command = JSON.parse(raw) as RemoteScheduleCommand;
    const requestedStatus = String(req.body?.status ?? "").trim();
    const status = requestedStatus === "applied" || requestedStatus === "partial" || requestedStatus === "failed"
      ? requestedStatus
      : "failed";
    command.status = status;
    command.acknowledged_at_millis = Date.now();
    command.ack = {
      applied: Math.max(0, Number(req.body?.applied ?? 0) || 0),
      missing: Math.max(0, Number(req.body?.missing ?? 0) || 0),
      invalid: Math.max(0, Number(req.body?.invalid ?? 0) || 0),
    };
    await client.set(REMOTE_COMMAND_PREFIX + id, JSON.stringify(command), { EX: REMOTE_COMMAND_TTL_SECONDS });
    await client.lRem(REMOTE_COMMAND_QUEUE_KEY, 0, id);
    res.json({ ok: true, id, status: command.status, ack: command.ack });
  } catch (error) {
    console.error("Remote command acknowledgement failed", error);
    res.status(503).json({ error: "Remote schedule acknowledgement unavailable" });
  }
});
`;

if (!source.includes('app.get("/api/device/commands"')) {
  if (!source.includes(endpointMarker)) throw new Error("MCP 2.3 patch: MCP endpoint marker not found");
  source = source.replace(endpointMarker, endpoints + "\n" + endpointMarker);
}

source = source.replaceAll('version: "2.2.0"', 'version: "2.3.0"');
source = source.replaceAll('version: "2.2.1"', 'version: "2.3.0"');
source = source.replaceAll("Cut Vidéo MCP v2.2.0 listening on port", "Cut Vidéo MCP v2.3.0 listening on port");
source = source.replaceAll("Cut Vidéo MCP v2.2.1 listening on port", "Cut Vidéo MCP v2.3.0 listening on port");
source = source.replace(
  'connector_schema: "cutvideo-legacy-compatible-v1",',
  'connector_schema: "cutvideo-legacy-compatible-v1",\n  remote_schedule_execution: true,\n  remote_schedule_confirmation_required: true,',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_REMOTE_SCHEDULE_PATCH applied: real APK scheduling queue + device acknowledgement");
