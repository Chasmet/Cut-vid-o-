import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const helperMarker = "function createStableServer(): McpServer {";
const helpers = `
function remoteScheduleFingerprint(project: string, publications: unknown[]): string {
  return JSON.stringify({
    project: normalizeName(project),
    publications: publications.map((raw) => {
      const item = raw as Record<string, unknown>;
      return {
        video_name: normalizeName(String(item.video_name ?? "")),
        account: String(item.account ?? ""),
        platform: String(item.platform ?? ""),
        date: String(item.date ?? ""),
        time: String(item.time ?? ""),
      };
    }),
  });
}

async function readRemoteScheduleCommand(id: string): Promise<RemoteScheduleCommand | null> {
  const client = await getRedis();
  const raw = await client.get(REMOTE_COMMAND_PREFIX + id);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as RemoteScheduleCommand;
  } catch {
    return null;
  }
}

async function enqueueRemoteScheduleCommandDeduped(project: string, publications: unknown[]): Promise<RemoteScheduleCommand> {
  const client = await getRedis();
  const wanted = remoteScheduleFingerprint(project, publications);
  const ids = await client.lRange(REMOTE_COMMAND_HISTORY_KEY, -20, -1);
  const now = Date.now();
  for (let index = ids.length - 1; index >= 0; index--) {
    const raw = await client.get(REMOTE_COMMAND_PREFIX + ids[index]);
    if (!raw) continue;
    try {
      const command = JSON.parse(raw) as RemoteScheduleCommand;
      if (now - command.created_at_millis > 120_000) continue;
      if (command.status === "failed") continue;
      if (remoteScheduleFingerprint(command.project, Array.isArray(command.publications) ? command.publications : []) === wanted) {
        return command;
      }
    } catch {
      // Ignore malformed history records.
    }
  }
  return enqueueRemoteScheduleCommand(project, publications);
}

async function waitForRemoteScheduleCommand(id: string, timeoutMs = 18_000): Promise<RemoteScheduleCommand> {
  const deadline = Date.now() + timeoutMs;
  let current = await readRemoteScheduleCommand(id);
  while (current && current.status === "queued" && Date.now() < deadline) {
    await new Promise<void>((resolve) => setTimeout(resolve, 1_000));
    current = await readRemoteScheduleCommand(id);
  }
  if (current) return current;
  return {
    id,
    type: "schedule_publications",
    project: "",
    created_at_millis: Date.now(),
    status: "failed",
    publications: [],
    ack: { applied: 0, missing: 0, invalid: 1 },
  };
}

function remoteScheduleStatusLabel(status: RemoteScheduleCommand["status"]): string {
  if (status === "applied") return "PROGRAMMÉ DANS L'APK";
  if (status === "partial") return "PROGRAMMATION PARTIELLE";
  if (status === "failed") return "ÉCHEC PROGRAMMATION";
  return "EN ATTENTE APK";
}
`;

if (!source.includes("function remoteScheduleFingerprint(")) {
  if (!source.includes(helperMarker)) throw new Error("MCP 2.5 patch: stable server marker not found");
  source = source.replace(helperMarker, helpers + "\n" + helperMarker);
}

const stableStart = source.indexOf(helperMarker);
const stableEnd = source.indexOf("\nfunction sendMcpError", stableStart);
if (stableStart < 0 || stableEnd < 0) throw new Error("MCP 2.5 patch: stable server boundaries not found");
let stable = source.slice(stableStart, stableEnd);

stable = stable.replace(
  'title: "Programmer un lot Cut Vidéo",',
  'title: "Programmer réellement dans Cut Vidéo",',
);
stable = stable.replace(
  'description: "Use this as the main Cut Vidéo tool. For every supplied video file, create network-specific metadata that matches that file, stays within 100 characters total, then organize the requested date/time schedule and return one clean fiche for the application\'s dedicated block. This tool does not cut videos and does not publish by API.",',
  'description: "PROGRAMMATION RÉELLE. Use this whenever the user asks to program or schedule Cut Vidéo files. It creates the real remote scheduling command consumed by the Android APK, waits for the APK acknowledgment, prevents immediate duplicate commands, and returns queued/applied/partial/failed. Say PROGRAMMÉ only when status is applied. queued means EN ATTENTE APK. It also generates network-specific metadata limited to 100 characters. It schedules inside Cut Vidéo; it does not directly publish to social networks.",',
);

const oldQueue = `    const remoteCommand = await enqueueRemoteScheduleCommand(realProject.name, ordered);\n    const fiche = [`;
const newQueue = `    const remoteCommand = await enqueueRemoteScheduleCommandDeduped(realProject.name, ordered);\n    const remoteResult = await waitForRemoteScheduleCommand(remoteCommand.id, 18_000);\n    const remoteLabel = remoteScheduleStatusLabel(remoteResult.status);\n    const fiche = [`;
if (stable.includes(oldQueue)) {
  stable = stable.replace(oldQueue, newQueue);
} else if (!stable.includes("const remoteResult = await waitForRemoteScheduleCommand")) {
  throw new Error("MCP 2.5 patch: remote queue marker not found");
}

stable = stable.replace(
  '        `${p.date} a ${p.time} • EN ATTENTE APK`,',
  '        `${p.date} a ${p.time} • ${remoteLabel}`,',
);
stable = stable.replace(
  '      "ETAT APK: EN ATTENTE DE SYNCHRO — pas encore confirme comme programme",',
  '      `ETAT APK: ${remoteLabel}`,',
);

const oldStructured = `        remote_command_id: remoteCommand.id,\n        device_status: "en_attente_apk",\n        device_confirmed: false,\n        instruction: "La commande est en file d attente. Ne dis PROGRAMME qu apres confirmation APK visible dans remote_schedule_commands ou dans la bibliotheque synchronisee.",`;
const newStructured = `        remote_command_id: remoteResult.id,\n        device_status: remoteResult.status,\n        device_confirmed: remoteResult.status !== "queued",\n        device_ack: remoteResult.ack ?? null,\n        instruction: remoteResult.status === "applied"\n          ? "PROGRAMMATION REELLEMENT ENREGISTREE DANS L APK. Tu peux dire PROGRAMME."\n          : remoteResult.status === "partial"\n            ? "PROGRAMMATION PARTIELLE. Indique applied/missing/invalid et ne prétends pas que tout est programmé."\n            : remoteResult.status === "failed"\n              ? "ECHEC DE PROGRAMMATION DANS L APK. Rien ne doit être présenté comme programmé."\n              : "COMMANDE EN ATTENTE APK. Ne dis pas PROGRAMME tant que Cut Vidéo n'a pas confirmé.",`;
if (stable.includes(oldStructured)) {
  stable = stable.replace(oldStructured, newStructured);
} else if (!stable.includes("device_ack: remoteResult.ack")) {
  throw new Error("MCP 2.5 patch: structured result marker not found");
}

source = source.slice(0, stableStart) + stable + source.slice(stableEnd);
source = source.replaceAll('version: "2.4.1"', 'version: "2.5.0"');
source = source.replaceAll("Cut Vidéo MCP v2.4.1 listening on port", "Cut Vidéo MCP v2.5.0 listening on port");

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_CONNECTOR_REAL_SCHEDULE_PATCH applied: dedupe + wait for APK acknowledgement");
