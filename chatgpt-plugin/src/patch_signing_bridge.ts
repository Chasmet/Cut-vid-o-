import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

if (!source.includes('from "jose"')) {
  source = source.replace(
    'import { z } from "zod";',
    'import { z } from "zod";\nimport { createRemoteJWKSet, jwtVerify } from "jose";',
  );
}
if (!source.includes('generateKeyPairSync')) {
  source = source.replace(
    'import { randomUUID } from "node:crypto";',
    'import { randomUUID, generateKeyPairSync } from "node:crypto";',
  );
}
if (!source.includes('execFileSync')) {
  source = source.replace(
    'import express from "express";',
    'import express from "express";\nimport { execFileSync } from "node:child_process";\nimport { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";\nimport { tmpdir } from "node:os";\nimport { join } from "node:path";',
  );
}

const marker = 'app.post("/mcp", async (req, res) => {';
const bridge = `
const CUTVIDEO_SIGNING_AUDIENCE = "cut-video-signing";
const CUTVIDEO_GITHUB_OIDC_ISSUER = "https://token.actions.githubusercontent.com";
const CUTVIDEO_GITHUB_OIDC_JWKS = createRemoteJWKSet(
  new URL("https://token.actions.githubusercontent.com/.well-known/jwks"),
);
const CUTVIDEO_SIGNING_MATERIAL_KEY = "cutvideo:android-signing-material:v1";

type CutVideoSigningMaterial = {
  private_key_pem: string;
  certificate_pem: string;
  created_at_millis: number;
};

async function isAuthorizedSigningWorkflow(req: express.Request): Promise<boolean> {
  const authorization = req.header("authorization") ?? "";
  if (!authorization.toLowerCase().startsWith("bearer ")) return false;
  const token = authorization.slice(7).trim();
  if (!token) return false;

  try {
    const { payload } = await jwtVerify(token, CUTVIDEO_GITHUB_OIDC_JWKS, {
      issuer: CUTVIDEO_GITHUB_OIDC_ISSUER,
      audience: CUTVIDEO_SIGNING_AUDIENCE,
    });
    return payload.repository === "Chasmet/Cut-vid-o-"
      && payload.ref === "refs/heads/main"
      && typeof payload.workflow_ref === "string"
      && payload.workflow_ref === "Chasmet/Cut-vid-o-/.github/workflows/android.yml@refs/heads/main";
  } catch {
    return false;
  }
}

async function getOrCreateStableAndroidSigningMaterial(): Promise<CutVideoSigningMaterial> {
  const client = await getRedis();
  const existing = await client.get(CUTVIDEO_SIGNING_MATERIAL_KEY);
  if (existing) {
    try {
      const parsed = JSON.parse(existing) as CutVideoSigningMaterial;
      if (parsed.private_key_pem && parsed.certificate_pem) return parsed;
    } catch {
      // Corrupt legacy value: regenerate once below.
    }
  }

  const { privateKey } = generateKeyPairSync("ec", {
    namedCurve: "prime256v1",
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" },
  });

  const directory = mkdtempSync(join(tmpdir(), "cutvideo-signing-"));
  const privateKeyPath = join(directory, "private-key.pem");
  const certificatePath = join(directory, "certificate.pem");
  try {
    writeFileSync(privateKeyPath, privateKey, { encoding: "utf8", mode: 0o600 });
    execFileSync("openssl", [
      "req", "-new", "-x509",
      "-key", privateKeyPath,
      "-out", certificatePath,
      "-days", "10000",
      "-sha256",
      "-subj", "/C=FR/ST=Ile-de-France/L=Paris/O=Chasmet/OU=Android/CN=Cut Video",
    ], { stdio: "ignore" });
    const certificatePem = readFileSync(certificatePath, "utf8");
    const material: CutVideoSigningMaterial = {
      private_key_pem: privateKey,
      certificate_pem: certificatePem,
      created_at_millis: Date.now(),
    };
    await client.set(CUTVIDEO_SIGNING_MATERIAL_KEY, JSON.stringify(material));
    return material;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

app.get("/internal/android-signing-bundle", async (req, res) => {
  if (!(await isAuthorizedSigningWorkflow(req))) {
    res.status(401).json({ error: "Unauthorized GitHub Actions workflow" });
    return;
  }

  try {
    const material = await getOrCreateStableAndroidSigningMaterial();
    res.setHeader("Cache-Control", "no-store, max-age=0");
    res.setHeader("Pragma", "no-cache");
    res.json({
      private_key_pem: material.private_key_pem,
      certificate_pem: material.certificate_pem,
      created_at_millis: material.created_at_millis,
      storage: "private_redis_persistent",
      rotation: "disabled",
    });
  } catch (error) {
    console.error("Stable Android signing material unavailable", error);
    res.status(503).json({ error: "Stable Android signing material unavailable" });
  }
});
`;

if (!source.includes('app.get("/internal/android-signing-bundle"')) {
  if (!source.includes(marker)) throw new Error("Signing bridge: MCP route marker not found");
  source = source.replace(marker, bridge + "\n" + marker);
}

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_SIGNING_BRIDGE_PATCH applied: stable key stored privately in Redis + GitHub OIDC");
