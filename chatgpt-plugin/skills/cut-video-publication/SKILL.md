---
name: cut-video-publication
description: Gérer automatiquement les vidéos, métadonnées, programmations et fiches Cut Vidéo à partir de la bibliothèque synchronisée.
---

# Cut Vidéo Publication

Utilise ce workflow dès que l'utilisateur demande de gérer, préparer ou programmer ses vidéos Cut Vidéo.

## Principe principal

L'utilisateur ne doit pas avoir à recopier les noms de projets ou de fichiers déjà présents dans Cut Vidéo. La bibliothèque synchronisée est la source de vérité.

Quand l'utilisateur dit simplement « occupe-toi des vidéos », « fais les metas », « programme mes vidéos », « jusqu'à telle vidéo » ou formule une demande similaire :

1. appelle d'abord `list_cutvideo_library` ;
2. appelle `get_cutvideo_work_context` ;
3. laisse le serveur choisir automatiquement le projet si l'utilisateur ne l'a pas clairement indiqué ;
4. travaille uniquement avec les vrais fichiers retournés ;
5. crée les métadonnées et la programmation ;
6. termine avec `prepare_cutvideo_publication_pack` et le lien d'import en lot.

## Ne pas poser de questions inutiles

- Ne demande jamais une capture d'écran pour retrouver un projet ou une vidéo déjà synchronisés.
- Ne demande jamais le nom exact du projet avant d'avoir consulté la bibliothèque.
- `CHKNOIRSHADOW` et `QG` sont des comptes, pas des projets.
- Si aucun projet n'est indiqué, utilise automatiquement le projet recommandé par `get_cutvideo_work_context`.
- Si l'utilisateur cite une vidéo, retrouve automatiquement son projet.
- Si compte, réseau ou heure manquent, utilise `recommended_defaults` fourni par `get_cutvideo_work_context` au lieu de poser une question.
- Par défaut sans historique exploitable : CHKNOIRSHADOW, TikTok, 18:00, une vidéo par jour, Europe/Paris.
- Pose une question uniquement si aucune décision raisonnable n'est possible à partir de la bibliothèque et des réglages existants.

## Règles obligatoires

1. Les noms réels des fichiers synchronisés sont la source principale.
2. Le bloc complet `titre + description + hashtags` doit faire **100 caractères maximum**, retours à la ligne compris.
3. Utilise **5 hashtags maximum**.
4. Adapte la méta au nom réel du fichier et au réseau.
5. Comptes autorisés : CHKNOIRSHADOW = YouTube/TikTok/Instagram/X ; QG = YouTube/TikTok.
6. Date et heure en `Europe/Paris` sauf indication contraire.
7. Ne jamais inventer un fichier absent de la bibliothèque.
8. Le plugin ne reçoit jamais les fichiers vidéo eux-mêmes.

## Cas « toutes mes vidéos »

Traite tous les projets qui ont des vidéos non encore programmées, en commençant par le projet recommandé le plus récent. Crée un lot par projet si nécessaire, sans demander à l'utilisateur de choisir chaque projet.

## Format final

Réponse courte : projet choisi, nombre de vidéos traitées, période, compte/réseau, puis **FICHE CUT VIDÉO** et lien **Importer tout le lot dans Cut Vidéo**.
