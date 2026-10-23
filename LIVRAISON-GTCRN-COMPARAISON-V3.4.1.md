# Guardian Voice v3.4.1 — comparaison GTCRN

La voie principale reste FireRed streaming, confirmation FireRed puis Qwen sur le PCM original. Cette version ajoute une voie GTCRN indépendante, uniquement pendant un enregistrement diagnostic NOISY avec WAV. Elle ne déclenche aucune alerte et ne publie aucun texte. En fonctionnement habituel ou avec DISTANT, GTCRN reste désactivé.

Le but est de mesurer sur une vraie voix dans le bruit si le débruitage améliore la reconnaissance, avant de lui confier une décision. Les essais antérieurs ne démontrent pas de bénéfice systématique à distance. Cette version ne constitue pas encore un secours automatique de reconnaissance.

## Installation

Exécuter l'installateur v3.4.1, reconstruire Android avec la procédure habituelle, puis installer le nouvel APK. L'installateur sauvegarde le module remplacé en dehors du projet. Il ne compile pas l'application. Vérifier ensuite que Qwen est disponible.

Télécharger `enregistrer-gtcrn-comparison.ps1` depuis le même commit que l'installateur. Puis exécuter dans PowerShell :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-gtcrn-comparison.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile NOISY -DurationSeconds 60
```

Arrêter et redémarrer la protection lorsque le script le demande. Faire deux enregistrements séparés à 1 mètre et 3 mètres, avec un bruit stable sans autre voix : 15 secondes sans parler, trois répétitions espacées de la même phrase et quelques mots courts, puis silence. Indiquer distance, phrase exacte et type de bruit dans les notes. Conserver le même niveau sonore. Envoyer les ZIP ; ils contiennent des voix privées.

## Contenu et limites

Les cinq WAV habituels et les logs restent présents. Le sous-dossier `gtcrn-comparison` ajoute une paire `truth.wav` (original) / `asr.wav` (GTCRN) et `index.tsv`. Le nom de fichier « asr » n'implique pas que ce son soit envoyé à Qwen. Le même gain de capture est pris en compte pour comparer les trames.

La file secondaire est bornée : en cas de retard ou d'erreur, elle abandonne des trames sans bloquer la voie principale. Ces WAV ne sont donc pas forcément continus ; l'index, `dropped` et `unmatched` doivent être pris en compte. Le collecteur vérifie les empreintes, les en-têtes et la durée appariée. Une comparaison vide ou une erreur d'écriture fait échouer la validation en conservant les éléments disponibles.

Les contrôles sur ordinateur vérifient l'appariement, l'isolation du PCM original, la compilation des interfaces et les cas de collecte simulés. Ils ne remplacent pas une compilation APK ni un test sur téléphone. Aucun gain de précision dans le bruit n'est revendiqué avant ce test réel.
