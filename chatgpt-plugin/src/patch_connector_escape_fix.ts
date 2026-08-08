import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

source = source.replaceAll("\\`", "`");
source = source.replaceAll("\\${", "${");

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_CONNECTOR_ESCAPE_FIX applied");
