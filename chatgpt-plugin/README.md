# Cut Vidéo — compagnon ChatGPT

Ce dossier ajoute un serveur MCP destiné à être connecté à ChatGPT.

## Mission du plugin

Le plugin ne découpe pas les vidéos et ne reçoit aucun fichier vidéo. Il sert uniquement à :

- préparer les métadonnées de publication ;
- produire un titre, une description et des hashtags compatibles avec l'import presse-papiers de Cut Vidéo ;
- organiser plusieurs publications dans un ordre clair ;
- préparer les dates, heures, réseaux, visibilité et statut de chaque publication ;
- générer une fiche récapitulative à coller dans le bloc-notes dédié de l'application.

## Outils MCP

### `prepare_cutvideo_metadata`

Formate une publication unique en :

```text
Titre: ...
Description: ...
Hashtags: #... #... #...
```

Ce format est compris par `PublicationMetadataParser` dans l'application Android.

### `prepare_cutvideo_publication_sheet`

Crée une fiche ordonnée avec :

- numéro d'ordre ;
- nom de la vidéo ;
- réseau ;
- date et heure ;
- statut ;
- visibilité ;
- titre ;
- description ;
- hashtags.

La réponse contient aussi `fiche_bloc`, prévue pour être copiée dans le bloc-notes de Cut Vidéo.

### `prepare_cutvideo_publication_pack`

Outil principal : combine métadonnées, liste de programmation et fiche récapitulative.

## Exemple d'utilisation dans ChatGPT

> Prépare mes métadonnées YouTube pour `Albator 01.mp4`, programme-la vendredi à 18 h, puis fais-moi la fiche Cut Vidéo.

ChatGPT prépare le texte et appelle le serveur MCP pour obtenir une sortie propre et structurée.

## Lancer en local

```bash
cd chatgpt-plugin
npm install
npm run start
```

Le serveur écoute par défaut sur :

```text
http://localhost:3000/mcp
```

Pour ChatGPT, le serveur MCP devra être déployé sur une adresse HTTPS accessible publiquement.

## Confidentialité

Le serveur ne reçoit ni ne traite les vidéos. Les fichiers restent dans l'application Android Cut Vidéo sur le téléphone.
