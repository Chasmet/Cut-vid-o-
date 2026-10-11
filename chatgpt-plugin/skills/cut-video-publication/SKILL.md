---
name: cut-video-publication
description: Gérer automatiquement toutes les vidéos, métadonnées, programmations et fiches Cut Vidéo à partir de toute la bibliothèque synchronisée.
---

# Cut Vidéo Publication

Utilise ce workflow dès que l'utilisateur demande de gérer, préparer ou programmer ses vidéos Cut Vidéo.

## Principe principal

La bibliothèque synchronisée est la source de vérité. L'utilisateur ne doit pas avoir à recopier les dossiers, sous-dossiers ou fichiers déjà présents dans Cut Vidéo.

Dès qu'un dossier contenant des vidéos est visible dans la bibliothèque :

1. entre dans ce dossier automatiquement ;
2. récupère toutes ses vidéos réelles ;
3. repère celles qui ne sont pas encore programmées ;
4. crée une méta différente et pertinente pour chaque vidéo ;
5. programme toutes les vidéos concernées ;
6. passe ensuite au dossier suivant sans demander confirmation.

Les entrées peuvent représenter un chemin complet de l'application, par exemple `Classement / Sous-dossier`. Il faut traiter chaque chemin comme un dossier de travail réel et descendre dans toute la bibliothèque retournée.

## Mode autonomie maximale

Quand l'utilisateur dit « occupe-toi de mes vidéos », « fais les metas partout », « programme tout », « gère mes vidéos » ou une formulation équivalente :

1. appelle `list_cutvideo_library` ;
2. parcours **tous** les dossiers retournés qui contiennent des vidéos non encore programmées ;
3. pour chaque dossier, appelle `get_cutvideo_work_context` ou `get_cutvideo_project` avec son chemin réel ;
4. génère les métadonnées de toutes les vidéos à traiter ;
5. crée un lot avec `prepare_cutvideo_publication_pack` ;
6. recommence pour le dossier suivant jusqu'à ce qu'il ne reste plus de dossier à traiter.

Ne t'arrête pas au premier dossier recommandé si la demande porte sur toutes les vidéos.

## Ne pas poser de questions inutiles

- Ne demande jamais une capture d'écran pour retrouver un dossier ou une vidéo déjà synchronisés.
- Ne demande jamais à l'utilisateur de préciser si un nom correspond à un classement, un sous-dossier ou un projet : cherche-le dans toute la bibliothèque.
- Ne saute jamais un dossier parce que son nom ressemble à un compte social, une catégorie ou un nom déjà connu.
- `CHKNOIRSHADOW`, `QG` ou tout autre nom peuvent être de simples noms de dossiers. Ils ne doivent **jamais** servir à filtrer la bibliothèque.
- Le compte social est une information séparée du chemin des dossiers.
- Si compte, réseau ou heure manquent, utilise silencieusement les réglages/historique disponibles et `recommended_defaults` au lieu de poser une question.
- Par défaut sans historique exploitable : TikTok, 18:00, une vidéo par jour, Europe/Paris.
- Pose une question uniquement si l'action est réellement impossible techniquement avec les données disponibles.

## Règles des métadonnées

1. Fais des métadonnées pour **toutes** les vidéos à traiter, dans **tous** les dossiers concernés.
2. Utilise le vrai nom du fichier et le chemin du dossier pour comprendre le sujet.
3. Le bloc complet `titre + description + hashtags` doit faire **100 caractères maximum**, retours à la ligne compris.
4. Utilise **5 hashtags maximum**.
5. Chaque vidéo doit avoir une méta adaptée ; ne recopie pas exactement la même méta partout.
6. Ne jamais inventer un fichier absent de la bibliothèque.
7. Le plugin ne reçoit jamais les fichiers vidéo eux-mêmes.

## Programmation

- Respecte les dates/heures données par l'utilisateur.
- Si l'utilisateur donne une date de fin ou une dernière vidéo, répartis automatiquement les vidéos jusqu'à cette limite.
- Si aucun horaire n'est donné, choisis automatiquement des horaires raisonnables sans demander.
- Crée un lot par dossier si nécessaire et continue automatiquement sur les autres dossiers.

## Format final

Réponse courte : nombre total de dossiers traités, nombre total de vidéos traitées, période de programmation, puis les fiches et liens d'import nécessaires. Ne fais pas perdre du temps à l'utilisateur avec des questions dont la réponse existe déjà dans Cut Vidéo.
