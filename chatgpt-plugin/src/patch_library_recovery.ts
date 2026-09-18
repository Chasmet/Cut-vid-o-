import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const helperMarker = "function createFullServer(): McpServer {";
const helpers = `
const LIBRARY_RECOVERY_KEY = "cutvideo:library-recovery:v1";

async function preserveCurrentLibraryRecovery(): Promise<void> {
  const client = await getRedis();
  const current = await client.get(LIBRARY_KEY);
  if (!current) return;
  try {
    const parsed = librarySchema.safeParse(JSON.parse(current));
    if (parsed.success && parsed.data.schedules.length > 0) {
      await client.set(LIBRARY_RECOVERY_KEY, JSON.stringify(parsed.data));
    }
  } catch {
    // Never block the MCP because of a malformed historical snapshot.
  }
}
`;

if (!source.includes("const LIBRARY_RECOVERY_KEY")) {
  if (!source.includes(helperMarker)) throw new Error("Recovery patch: full server marker missing");
  source = source.replace(helperMarker, helpers + "\n" + helperMarker);
}

const saveMarker = "    await client.set(LIBRARY_KEY, JSON.stringify(parsed.data));";
const saveReplacement = `    if (parsed.data.schedules.length > 0) {
      await client.set(LIBRARY_RECOVERY_KEY, JSON.stringify(parsed.data));
    }
    await client.set(LIBRARY_KEY, JSON.stringify(parsed.data));`;
if (source.includes(saveMarker) && !source.includes("parsed.data.schedules.length > 0")) {
  source = source.replace(saveMarker, saveReplacement);
}

const endpointMarker = 'app.get("/api/import-pack/:id", async (req, res) => {';
const recoveryEndpoint = `
app.get("/api/library/recovery", async (req, res) => {
  try {
    if (!(await deviceAuthorized(req))) {
      res.status(401).json({ error: "Unauthorized Cut Video device" });
      return;
    }
    const client = await getRedis();
    const raw = await client.get(LIBRARY_RECOVERY_KEY) ?? await client.get(LIBRARY_KEY);
    if (!raw) {
      res.status(404).json({ error: "No Cut Video recovery snapshot" });
      return;
    }
    const parsed = librarySchema.safeParse(JSON.parse(raw));
    if (!parsed.success) {
      res.status(404).json({ error: "No valid Cut Video recovery snapshot" });
      return;
    }
    res.setHeader("Cache-Control", "no-store, max-age=0");
    res.json(parsed.data);
  } catch (error) {
    console.error("Library recovery fetch failed", error);
    res.status(503).json({ error: "Cut Video recovery unavailable" });
  }
});
`;

if (!source.includes('app.get("/api/library/recovery"')) {
  if (!source.includes(endpointMarker)) throw new Error("Recovery patch: import-pack endpoint marker missing");
  source = source.replace(endpointMarker, recoveryEndpoint + "\n" + endpointMarker);
}

const listenMarker = "app.listen(PORT, () => {";
if (!source.includes("preserveCurrentLibraryRecovery().catch")) {
  if (!source.includes(listenMarker)) throw new Error("Recovery patch: listen marker missing");
  source = source.replace(
    listenMarker,
    'void preserveCurrentLibraryRecovery().catch((error) => console.error("Initial recovery snapshot failed", error));\n\n' + listenMarker,
  );
}

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_LIBRARY_RECOVERY_PATCH applied: persistent last-good schedule snapshot + authenticated restore");
