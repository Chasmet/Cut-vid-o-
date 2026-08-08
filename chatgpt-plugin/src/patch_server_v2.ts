import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

// Keep the three legacy-visible tools fully usable even when ChatGPT has cached an old MCP schema.
source = source.replace(
  'description: "OUTIL PRINCIPAL D’ÉCRITURE. Ne demande pas le nom du projet : project est facultatif et sera inféré à partir des vrais noms de fichiers ou du projet actif recommandé. Utilise seulement des fichiers retournés par get_cutvideo_work_context/get_cutvideo_project. Crée des métadonnées différentes ≤100 caractères puis un seul lien d’import pour tout le lot.",',
  'description: "OUTIL AUTOPILOTE. Prépare un lot avec les vrais fichiers Cut Vidéo, y compris sur plusieurs dossiers. Si un nom est faux ou ancien, ne bloque pas : renvoie automatiquement la bibliothèque et les fichiers exacts pour permettre un nouvel appel immédiat. Pour lire toute la bibliothèque avec une ancienne connexion ChatGPT, utilise project=__CUTVIDEO_LIBRARY__. Métadonnées différentes, 100 caractères maximum.",',
);
source = source.replace(
  'description: "OUTIL PRINCIPAL D\'ÉCRITURE. Ne demande pas le nom du projet : project est facultatif et sera inféré à partir des vrais noms de fichiers ou du projet actif recommandé. Utilise seulement des fichiers retournés par get_cutvideo_work_context/get_cutvideo_project. Crée des métadonnées différentes ≤100 caractères puis un seul lien d\'import pour tout le lot.",',
  'description: "OUTIL AUTOPILOTE. Prépare un lot avec les vrais fichiers Cut Vidéo, y compris sur plusieurs dossiers. Si un nom est faux ou ancien, ne bloque pas : renvoie automatiquement la bibliothèque et les fichiers exacts pour permettre un nouvel appel immédiat. Pour lire toute la bibliothèque avec une ancienne connexion ChatGPT, utilise project=__CUTVIDEO_LIBRARY__. Métadonnées différentes, 100 caractères maximum.",',
);

source = source.replace(
  'instruction: "Parcours tous les dossiers ayant pending_count > 0, utilise uniquement pending_videos, crée une méta différente par vidéo puis appelle prepare_cutvideo_publication_pack normalement pour chaque dossier.",',
  'instruction: "SOURCE DE VÉRITÉ : parcours tous les dossiers ayant pending_count > 0, utilise uniquement les vrais noms de pending_videos, crée une méta différente par vidéo. Un seul lot multi-dossiers est accepté avec project=tout.",',
);

const oldValidation = `    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));
    const realProject = selected.project;
    const realNames = new Set(realProject.videos.map((video) => normalizeName(video.name)));
    for (const publication of publications) {
      if (!realNames.has(normalizeName(publication.video_name))) {
        throw new Error(\`La vidéo \${publication.video_name} n'existe pas dans \${realProject.name}. Relis le projet avec get_cutvideo_work_context et utilise les noms exacts.\`);
      }
    }
    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const pack = await savePack(realProject.name, ordered);`;

const newValidation = `    const requestedIsGeneric = isGenericProjectName(project);
    const explicitProject = requestedIsGeneric ? null : findProject(library, project);
    const allEntries = library.projects.flatMap((projectEntry) =>
      projectEntry.videos.map((video) => ({ project: projectEntry, video, normalized: normalizeName(video.name) })),
    );
    const preferredEntries = explicitProject
      ? allEntries.filter((entry) => normalizeName(entry.project.name) === normalizeName(explicitProject.name))
      : allEntries;

    const resolvedEntries: Array<{ publication: PublicationInput; project: LibraryProject; video: LibraryProject["videos"][number] }> = [];
    const invalidNames: string[] = [];
    const ambiguousNames: Array<{ video_name: string; projects: string[] }> = [];

    for (const publication of publications) {
      const target = normalizeName(publication.video_name);
      let matches = preferredEntries.filter((entry) => entry.normalized === target);
      if (!matches.length && explicitProject) {
        matches = allEntries.filter((entry) => entry.normalized === target);
      }
      if (!matches.length) {
        invalidNames.push(publication.video_name);
        continue;
      }
      if (matches.length > 1) {
        ambiguousNames.push({
          video_name: publication.video_name,
          projects: [...new Set(matches.map((entry) => entry.project.name))],
        });
        continue;
      }
      resolvedEntries.push({ publication, project: matches[0].project, video: matches[0].video });
    }

    if (invalidNames.length || ambiguousNames.length) {
      const projects = library.projects.map((projectEntry) => {
        const stats = projectStats(library, projectEntry);
        return {
          name: projectEntry.name,
          pending_count: stats.pendingCount,
          pending_videos: stats.pendingVideos,
          videos: projectEntry.videos,
          existing_schedules: stats.schedules,
          recommended_defaults: recommendedDefaults(library, projectEntry),
        };
      });
      return {
        structuredContent: {
          mode: "retry_with_real_files",
          invalid_video_names: invalidNames,
          ambiguous_video_names: ambiguousNames,
          synced_at_millis: library.synced_at_millis,
          app_version: library.app_version,
          projects,
          instruction: "Ne demande rien à l'utilisateur. Réessaie immédiatement avec les noms exacts présents dans pending_videos. Pour une demande générale, traite tous les dossiers ayant pending_count > 0. Un lot multi-dossiers est accepté avec project=tout.",
        },
        content: [{
          type: "text",
          text: [
            "CUT VIDÉO — CORRECTION AUTOMATIQUE DES NOMS",
            invalidNames.length ? \`Noms invalides : \${invalidNames.join(", ")}\` : "",
            ambiguousNames.length ? \`Noms ambigus : \${ambiguousNames.map((item) => item.video_name).join(", ")}\` : "",
            ...projects.flatMap((item) => [
              \`\${item.name} — \${item.pending_count} à traiter\`,
              ...item.pending_videos.map((video, index) => \`  \${index + 1}. \${video.name}\`),
            ]),
          ].filter(Boolean).join("\\n"),
        }],
      };
    }

    const projectNames = [...new Set(resolvedEntries.map((entry) => entry.project.name))];
    const realProject = {
      name: projectNames.length === 1 ? projectNames[0] : \`Multi-dossiers (\${projectNames.length})\`,
    };
    const selected = {
      method: projectNames.length === 1 ? "video_filenames" : "multi_project_video_filenames",
    };
    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const pack = await savePack(realProject.name, ordered);`;

if (source.includes(oldValidation)) {
  source = source.replace(oldValidation, newValidation);
} else if (!source.includes('mode: "retry_with_real_files"')) {
  throw new Error("CUTVIDEO v2 patch: publication validation block not found");
}

// Expose a deployment/version marker so health checks can prove the new behavior is live.
source = source.replace(
  'version: "1.6.0"',
  'version: "2.0.0"',
);
source = source.replace(
  'version: "1.6.0",\n  status: "ok",',
  'version: "2.0.0",\n  status: "ok",\n  autopilot: true,\n  multi_project_packs: true,\n  self_healing_names: true,',
);
source = source.replace(
  'Cut Vidéo MCP v1.6.0 listening on port',
  'Cut Vidéo MCP v2.0.0 listening on port',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_MCP_PATCH_V2 applied: self-healing names + multi-folder packs + legacy-schema autopilot");
