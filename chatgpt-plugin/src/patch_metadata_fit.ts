import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const oldCompact = `function compactMetadata(title: string, description: string, hashtags: string[]): string {
  const lines = [title.trim()];
  if (description.trim()) lines.push(description.trim());
  if (hashtags.length) lines.push(hashtags.join(" "));
  const text = lines.join("\\n");
  const length = [...text].length;
  if (length > MAX_METADATA_CHARS) {
    throw new Error(\`Les métadonnées font \${length} caractères. Maximum : \${MAX_METADATA_CHARS}.\`);
  }
  return text;
}`;

const newCompact = `function fitMetadataFields(title: string, description: string, hashtags: string[]) {
  const build = (safeTitle: string, safeDescription: string, safeHashtags: string[]) => {
    const lines = [safeTitle.trim()];
    if (safeDescription.trim()) lines.push(safeDescription.trim());
    if (safeHashtags.length) lines.push(safeHashtags.join(" "));
    return lines.join("\\n");
  };

  let safeTitle = [...title.trim()].slice(0, 70).join("");
  let safeDescription = description.trim();
  let safeHashtags = [...hashtags];
  let text = build(safeTitle, safeDescription, safeHashtags);

  if ([...text].length <= MAX_METADATA_CHARS) {
    return { title: safeTitle, description: safeDescription, hashtags: safeHashtags, text, compacted: false };
  }

  // Preserve the title first. Remove least-important trailing hashtags only when title + hashtags alone exceed the limit.
  while (safeHashtags.length > 0 && [...build(safeTitle, "", safeHashtags)].length > MAX_METADATA_CHARS) {
    safeHashtags.pop();
  }

  const baseWithoutDescription = build(safeTitle, "", safeHashtags);
  const descriptionBudget = Math.max(
    0,
    MAX_METADATA_CHARS - [...baseWithoutDescription].length - (safeDescription ? 1 : 0),
  );
  safeDescription = [...safeDescription].slice(0, descriptionBudget).join("").trimEnd();
  text = build(safeTitle, safeDescription, safeHashtags);

  // Unicode-safe final guard. This should only trim the description, never fail the schedule.
  while ([...text].length > MAX_METADATA_CHARS && safeDescription.length > 0) {
    safeDescription = [...safeDescription].slice(0, -1).join("").trimEnd();
    text = build(safeTitle, safeDescription, safeHashtags);
  }
  while ([...text].length > MAX_METADATA_CHARS && safeHashtags.length > 0) {
    safeHashtags.pop();
    text = build(safeTitle, safeDescription, safeHashtags);
  }

  return { title: safeTitle, description: safeDescription, hashtags: safeHashtags, text, compacted: true };
}

function compactMetadata(title: string, description: string, hashtags: string[]): string {
  return fitMetadataFields(title, description, hashtags).text;
}`;

if (source.includes(oldCompact)) {
  source = source.replace(oldCompact, newCompact);
} else if (!source.includes("function fitMetadataFields(")) {
  throw new Error("Metadata fit patch: compactMetadata marker not found");
}

const oldPrepare = `function preparePublication(p: PublicationInput) {
  assertAllowed(p.account, p.platform);
  const tags = normalizeHashtags(p.hashtags);
  const metadata = compactMetadata(p.title, p.description, tags);
  return {
    ...p,
    hashtags: tags,
    account_label: accountLabel(p.account),
    metadata_text: metadata,
    metadata_characters: [...metadata].length,
    source_file: p.video_name,
    handoff: \`Ouvrir \${p.platform.toUpperCase()} avec le compte \${accountLabel(p.account)} déjà connecté.\`,
    handoff_url: buildHandoffUrl(p, tags),
  };
}`;

const newPrepare = `function preparePublication(p: PublicationInput) {
  assertAllowed(p.account, p.platform);
  const tags = normalizeHashtags(p.hashtags);
  const fitted = fitMetadataFields(p.title, p.description, tags);
  const normalized: PublicationInput = {
    ...p,
    title: fitted.title,
    description: fitted.description,
    hashtags: fitted.hashtags,
  };
  return {
    ...normalized,
    hashtags: fitted.hashtags,
    account_label: accountLabel(p.account),
    metadata_text: fitted.text,
    metadata_characters: [...fitted.text].length,
    metadata_compacted: fitted.compacted,
    source_file: p.video_name,
    handoff: \`Ouvrir \${p.platform.toUpperCase()} avec le compte \${accountLabel(p.account)} déjà connecté.\`,
    handoff_url: buildHandoffUrl(normalized, fitted.hashtags),
  };
}`;

if (source.includes(oldPrepare)) {
  source = source.replace(oldPrepare, newPrepare);
} else if (!source.includes("metadata_compacted: fitted.compacted")) {
  throw new Error("Metadata fit patch: preparePublication marker not found");
}

source = source.replaceAll('version: "2.4.0"', 'version: "2.4.1"');
source = source.replaceAll("Cut Vidéo MCP v2.4.0 listening on port", "Cut Vidéo MCP v2.4.1 listening on port");

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_METADATA_FIT_PATCH applied: metadata auto-compaction <= 100 chars");
