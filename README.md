# Cut Vidéo

Application Android française pour découper une vidéo directement sur le téléphone, sans compte, sans publicité et sans serveur.

## Fonctions de la version 1.0

- sélection sécurisée depuis Google Photos ou la galerie Android ;
- aperçu vidéo avec lecture, pause et déplacement dans la vidéo ;
- découpe rapide en morceaux de 15, 30, 60 ou 90 secondes ;
- durée personnalisée de 1 à 600 secondes selon la longueur de la vidéo ;
- rognage manuel avec choix du début et de la fin ;
- export MP4 morceau par morceau pour limiter la mémoire utilisée ;
- sauvegarde dans `Films/CutVideo` ;
- écran « Mes vidéos » avec miniature, lecture et partage ;
- fonctionnement entièrement hors ligne.

## Télécharger l'APK depuis un téléphone

1. Ouvrir la page **Releases** du dépôt GitHub.
2. Ouvrir la version **Cut Vidéo v1.0.0**.
3. Appuyer sur `Cut-Video-v1.0.0.apk`.
4. Ouvrir le fichier téléchargé pour l'installer.

En secours, chaque compilation verte conserve aussi une archive **Cut-Video-APK** pendant 90 jours dans l'onglet **Actions**. L'APK de cette première version est installable directement. Android peut demander d'autoriser temporairement l'installation depuis le navigateur ou l'application GitHub.

## Confidentialité

L'application utilise le sélecteur officiel Android. Elle n'accède qu'à la vidéo choisie et ne demande pas l'accès complet à la galerie. Aucune donnée n'est envoyée sur Internet.

## Compatibilité

- Android 10 ou plus récent ;
- téléphone ou tablette Android ;
- formats vidéo et audio pris en charge par les codecs de l'appareil ;
- export visible dans la galerie et les applications de partage.

## Limites connues

- une très longue vidéo ou une vidéo 4K peut prendre du temps et utiliser beaucoup d'espace temporaire ;
- la précision exacte de la première image dépend des codecs et des images-clés de la vidéo source ;
- le mélange d'une musique externe n'est pas encore inclus dans cette V1.

## Base technique

- Java 17 ;
- AndroidX Media3 ExoPlayer et Transformer ;
- MediaStore pour publier les MP4 ;
- tests unitaires du calcul des intervalles ;
- compilation automatique par GitHub Actions.
