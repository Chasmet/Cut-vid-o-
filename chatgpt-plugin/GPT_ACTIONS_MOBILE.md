# Cut Vidéo — GPT Actions mobile

Cette configuration contourne la limite des apps MCP personnalisées sur mobile sans supprimer le MCP existant.

## Endpoints

- Schéma OpenAPI public : `https://cut-video-chatgpt-mcp.onrender.com/gpt-actions/openapi.json`
- Politique de confidentialité : `https://cut-video-chatgpt-mcp.onrender.com/gpt-actions/privacy`
- Conditions : `https://cut-video-chatgpt-mcp.onrender.com/gpt-actions/terms`
- Support : `https://cut-video-chatgpt-mcp.onrender.com/gpt-actions/support`

Les endpoints `/api/gpt/*` exigent une authentification Bearer avec la variable Render `CUTVIDEO_GPT_ACTION_KEY`.

## GPT à créer sur ChatGPT web

Nom recommandé : `Cut Vidéo Mobile`

Instructions recommandées :

1. Pour toute demande concernant les vidéos Cut Vidéo, appelle d'abord `getCutVideoLibrary`.
2. Utilise uniquement les vrais noms de fichiers retournés par la bibliothèque. Ne demande pas à l'utilisateur de recopier les noms.
3. Pour CHKNOIRSHADOW, les réseaux autorisés sont YouTube, TikTok, Instagram et X. Pour QG, YouTube et TikTok.
4. Les métadonnées doivent être différentes pour chaque vidéo, pertinentes pour le dossier/fichier, contenir au maximum 5 hashtags et rester sous 100 caractères pour le bloc titre + description + hashtags.
5. N'appelle `scheduleCutVideoPublications` que si l'utilisateur demande réellement une programmation.
6. Une réponse `queued` signifie `EN ATTENTE APK`, jamais `PROGRAMMÉ`.
7. Après une programmation, appelle `getCutVideoScheduleStatus` avec le `remote_command_id` jusqu'à obtenir le statut courant. Dire `PROGRAMMÉ` uniquement si `status=applied`. Si `partial`, indiquer le nombre appliqué et les éléments manquants. Si `failed`, dire que la programmation a échoué.
8. Fuseau par défaut : `Europe/Paris`.

## Configuration de l'Action

Dans l'éditeur GPT sur le Web :

1. Actions → Créer une nouvelle action.
2. Authentification → Clé API → Bearer.
3. Utiliser la valeur de `CUTVIDEO_GPT_ACTION_KEY` configurée sur Render.
4. Importer le schéma depuis l'URL OpenAPI ci-dessus.
5. Politique de confidentialité : utiliser l'URL `/gpt-actions/privacy`.
6. Tester `getCutVideoLibrary` puis une programmation de test non critique.

Une fois le GPT créé, il peut être ouvert depuis le menu GPTs/barre latérale de l'application Android. La création et la modification du GPT restent limitées au Web.
