import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

if (!source.includes('from "jose"')) {
  source = source.replace(
    'import { z } from "zod";',
    'import { z } from "zod";\nimport { createRemoteJWKSet, jwtVerify } from "jose";',
  );
}

const marker = 'app.post("/mcp", async (req, res) => {';
const bridge = `
const CUTVIDEO_SIGNING_AUDIENCE = "cut-video-signing";
const CUTVIDEO_GITHUB_OIDC_ISSUER = "https://token.actions.githubusercontent.com";
const CUTVIDEO_GITHUB_OIDC_JWKS = createRemoteJWKSet(
  new URL("https://token.actions.githubusercontent.com/.well-known/jwks"),
);

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

app.get("/internal/android-signing-bundle", async (req, res) => {
  if (!(await isAuthorizedSigningWorkflow(req))) {
    res.status(401).json({ error: "Unauthorized GitHub Actions workflow" });
    return;
  }

  const keystore = process.env.CUTVIDEO_SIGNING_KEYSTORE_BASE64 ?? "";
  const storePassword = process.env.CUTVIDEO_SIGNING_STORE_PASSWORD ?? "";
  const keyAlias = process.env.CUTVIDEO_SIGNING_KEY_ALIAS ?? "";
  const keyPassword = process.env.CUTVIDEO_SIGNING_KEY_PASSWORD ?? "";
  if (!keystore || !storePassword || !keyAlias || !keyPassword) {
    res.status(503).json({ error: "Stable Android signing key is not configured" });
    return;
  }

  res.setHeader("Cache-Control", "no-store, max-age=0");
  res.setHeader("Pragma", "no-cache");
  res.json({
    keystore_base64: keystore,
    store_password: storePassword,
    key_alias: keyAlias,
    key_password: keyPassword,
  });
});
`;

if (!source.includes('app.get("/internal/android-signing-bundle"')) {
  if (!source.includes(marker)) throw new Error("Signing bridge: MCP route marker not found");
  source = source.replace(marker, bridge + "\n" + marker);
}

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_SIGNING_BRIDGE_PATCH applied: GitHub OIDC protected stable signing endpoint");
