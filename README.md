# Cut Vidéo

Application Android française pour découper une vidéo directement sur le téléphone, sans compte, sans publicité et sans serveur.

## Fonctions de la version 1.3

- sélection sécurisée depuis Google Photos ou la galerie Android ;
- aperçu vidéo avec lecture, pause et déplacement dans la vidéo ;
- découpe rapide en morceaux de 15, 30, 60 ou 90 secondes ;
- durée personnalisée de 1 à 600 secondes selon la longueur de la vidéo ;
- rognage manuel avec choix du début et de la fin ;
- export MP4 morceau par morceau pour limiter la mémoire utilisée ;
- création d'un sous-dossier unique pour chaque vidéo traitée dans `Films/CutVideo` ;
- écran « Mes vidéos » organisé par dossiers, puis par morceaux ;
- renommage durable des dossiers directement depuis « Mes vidéos » ;
- renommage des fichiers MP4 à l’intérieur de chaque dossier ;
- suppression sécurisée d’un morceau avec confirmation ;
- suppression d’un dossier et de toutes ses vidéos avec confirmation ;
- suppression multiple depuis le mode de sélection ;
- demande d’autorisation Android automatique lorsque le système protège un fichier ;
- nettoyage du suivi local associé aux vidéos supprimées ;
- résumé du nombre de dossiers, de vidéos et de l’espace utilisé ;
- appui long sur un morceau pour commencer rapidement une sélection ;
- conservation des anciens exports dans le dossier virtuel « Anciennes vidéos » ;
- sélection de plusieurs morceaux, sélection de tout un dossier et partage groupé ;
- partage direct de tous les fichiers d'un dossier ;
- miniature, lecture et partage individuel de chaque morceau ;
- suivi manuel sous chaque vidéo pour YouTube, TikTok, Instagram et X ;
- mémorisation locale des cases cochées, même après fermeture de l'application ;
- fonctionnement entièrement hors ligne.

## Télécharger l'APK depuis un téléphone

1. Ouvrir la page **Releases** du dépôt GitHub.
2. Ouvrir la version **Cut Vidéo v1.3.0**.
3. Appuyer sur `Cut-Video-v1.3.0.apk`.
4. Ouvrir le fichier téléchargé pour l'installer.

En secours, chaque compilation verte conserve aussi une archive **Cut-Video-APK** pendant 90 jours dans l'onglet **Actions**. Android peut demander d'autoriser temporairement l'installation depuis le navigateur ou l'application GitHub.

## Confidentialité

L'application utilise le sélecteur officiel Android. Elle n'accède qu'à la vidéo choisie et ne demande pas l'accès complet à la galerie. Les cases de suivi sont enregistrées uniquement dans les préférences locales de l'application. Aucune donnée n'est envoyée sur Internet.

## Compatibilité

- Android 10 ou plus récent ;
- téléphone ou tablette Android ;
- formats vidéo et audio pris en charge par les codecs de l'appareil ;
- export visible dans la galerie et les applications de partage.

## Limites connues

- une très longue vidéo ou une vidéo 4K peut prendre du temps et utiliser beaucoup d'espace temporaire ;
- la précision exacte de la première image dépend des codecs et des images-clés de la vidéo source ;
- certaines applications sociales n'acceptent pas plusieurs vidéos en un seul partage ; dans ce cas, les morceaux restent partageables individuellement ;
- le suivi des plateformes est manuel : l'application ne peut pas confirmer automatiquement qu'une publication a réellement été mise en ligne ;
- le mélange d'une musique externe n'est pas encore inclus.

## Base technique

- Java 17 ;
- AndroidX Media3 ExoPlayer et Transformer ;
- MediaStore pour publier les MP4 ;
- regroupement par sous-dossier MediaStore pour séparer chaque travail ;
- SharedPreferences pour mémoriser le suivi local des partages ;
- tests unitaires du calcul des intervalles ;
- compilation automatique par GitHub Actions.
