# Cut Vidéo — compagnon ChatGPT

Ce dossier ajoute un serveur MCP destiné à être connecté à ChatGPT.

## Mission du plugin

Le plugin ne découpe pas les vidéos et ne reçoit aucun fichier vidéo. Il sert uniquement à :

- lire le nom du fichier vidéo fourni dans la demande ;
- préparer des métadonnées cohérentes avec ce fichier ;
- limiter le bloc complet titre + description + hashtags à **100 caractères maximum** ;
- utiliser au maximum 5 hashtags ;
- préparer la date et l'heure de programmation ;
- classer les publications dans un ordre clair ;
- générer une fiche récapitulative pour le bloc dédié de Cut Vidéo.

## Comptes configurés

- **CHKNOIRSHADOW** : YouTube, TikTok, Instagram, X.
- **QG** : YouTube, TikTok.

Cut Vidéo ouvre l'application sociale choisie. Le compte utilisé est celui déjà connecté et actif dans l'application sociale sur le téléphone.

## Règle des métadonnées

Chaque publication utilise ce format compact déjà compatible avec le parseur Android :

```text
Titre
Description
#Hashtag1 #Hashtag2
```

Les trois lignes réunies ne peuvent jamais dépasser **100 caractères**. Si ChatGPT prépare un texte plus long, le serveur MCP renvoie une erreur et ChatGPT doit le raccourcir avant de continuer.

Le champ `video_name` est obligatoire. ChatGPT doit créer le titre, la description et les hashtags en fonction du nom du fichier et du sujet donné par l'utilisateur. Il ne doit pas inventer un sujet sans rapport.

## Outils MCP

### `list_cutvideo_accounts`

Retourne les deux profils et les réseaux autorisés.

### `prepare_cutvideo_metadata`

Prépare les métadonnées compactes d'un seul fichier avec contrôle strict des 100 caractères.

### `prepare_cutvideo_publication_pack`

Outil principal. Pour chaque fichier vidéo, il prépare :

- compte ;
- réseau ;
- date ;
- heure ;
- statut ;
- métadonnées ≤ 100 caractères ;
- nombre exact de caractères ;
- fiche finale pour le bloc dédié de Cut Vidéo.

## Exemple

Demande :

> Programme `Albator_01.mp4` vendredi à 18 h sur TikTok CHKNOIRSHADOW et fais la fiche.

Sortie de principe :

```text
FICHE PUBLICATION — Albator

1. Albator_01.mp4
CHKNOIRSHADOW • TIKTOK
07/08/2026 à 18:00 • À PROGRAMMER
META (68/100)
Albator rap sombre
Le corsaire revient dans l'espace
#Albator #RapFR
```

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

Pour l'utiliser depuis ChatGPT, le serveur MCP doit être exposé sur une adresse HTTPS accessible à ChatGPT. Les Apps/Plugins ChatGPT actuels peuvent utiliser des serveurs MCP. Voir la documentation développeur OpenAI.

## Confidentialité

Le serveur ne reçoit ni vidéo, ni mot de passe, ni identifiant de réseau social. Les vidéos restent dans Cut Vidéo sur le téléphone.
