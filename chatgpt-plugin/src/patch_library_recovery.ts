import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const helperMarker = "function createFullServer(): McpServer {";
const helpers = `
const LIBRARY_RECOVERY_KEY = "cutvideo:library-recovery:v1";
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
if (
  source.includes(saveMarker)
  && !source.includes("await client.set(LIBRARY_RECOVERY_KEY, JSON.stringify(parsed.data));")
) {
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
    let raw = await client.get(LIBRARY_RECOVERY_KEY);

    // First migration: preserve the last synchronized 1.x snapshot before the
    // freshly installed APK can ever replace LIBRARY_KEY with an empty schedule list.
    if (!raw) {
      const currentRaw = await client.get(LIBRARY_KEY);
      if (currentRaw) {
        const currentParsed = librarySchema.safeParse(JSON.parse(currentRaw));
        if (currentParsed.success && currentParsed.data.schedules.length > 0) {
          raw = JSON.stringify(currentParsed.data);
          await client.set(LIBRARY_RECOVERY_KEY, raw);
        }
      }
    }

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
    res.setHeader("Pragma", "no-cache");
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

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_LIBRARY_RECOVERY_PATCH applied: last-good schedules preserved + authenticated restore");
