---
name: cut-video-publication
description: Préparer les métadonnées, la programmation et la fiche Cut Vidéo à partir du nom d'un fichier vidéo, d'un compte et d'un réseau.
---

# Cut Vidéo Publication

Utilise ce workflow lorsque l'utilisateur demande de préparer ou programmer une publication provenant de Cut Vidéo.

## Règles obligatoires

1. Le nom du fichier vidéo est la source principale. Les métadonnées doivent correspondre au sujet identifiable dans ce nom et aux indications données par l'utilisateur. Ne crée jamais un sujet sans rapport.
2. Le bloc complet `titre + description + hashtags` doit faire **100 caractères maximum**, retours à la ligne compris.
3. Utilise **5 hashtags maximum**.
4. Adapte le texte au réseau demandé tout en respectant la limite globale de 100 caractères.
5. Comptes autorisés :
   - `CHKNOIRSHADOW` : YouTube, TikTok, Instagram, X.
   - `QG` : YouTube, TikTok.
6. Si le compte n'est pas indiqué et qu'il est nécessaire pour exécuter la demande, demande uniquement `CHKNOIRSHADOW ou QG ?`.
7. Le plugin ne découpe pas la vidéo, ne reçoit pas de fichier vidéo, ne change pas le compte actif dans une application sociale et ne publie pas via les API des réseaux.
8. La date et l'heure de programmation sont en `Europe/Paris` sauf indication contraire.

## Outils

- Pour une seule vidéo : utilise `prepare_cutvideo_metadata`.
- Pour une ou plusieurs publications avec date/heure et fiche : utilise `prepare_cutvideo_publication_pack`.
- N'utilise `list_cutvideo_accounts` que si le compte manque.

## Format attendu

Pour chaque publication, restitue au minimum : fichier, compte, réseau, date, heure, métadonnées et compteur de caractères.

Termine par la **FICHE CUT VIDÉO** fournie par `fiche_bloc`. Elle doit être directement copiable dans le bloc dédié de l'application.
