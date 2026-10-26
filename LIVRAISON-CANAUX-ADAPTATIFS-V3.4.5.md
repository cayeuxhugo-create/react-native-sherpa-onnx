# V3.4.5 : capture et sélection adaptatives des canaux Android

## Changement demandé

En mode AUTO, Guardian ouvre la route qualifiée qui expose le plus d’informations acoustiquement indépendantes, puis de canaux utilisables et de canaux ouverts. Il examine tous les canaux de cette route, sans limite fixe LEFT/RIGHT. La découverte teste INDEX_1 à INDEX_4 ainsi que les nombres de canaux annoncés par Android (masques d’index représentables de 1 à 30 canaux). Les configurations refusées restent exclues. Une route qui échoue à l’ouverture retombe sur la stéréo, puis le mono ; les logs distinguent demande et capture réellement ouverte.

Cela concerne les **canaux audio rendus accessibles à l’application**, pas un accès garanti à chaque microphone physique. Des canaux peuvent être muets, dupliqués ou déjà traités par le téléphone. Plusieurs sources Android ne sont pas enregistrées simultanément : changer de source nécessiterait de rouvrir AudioRecord. La sélection pendant une phrase se fait entre les canaux synchrones de l’AudioRecord actif.

Les contrôles explicites `VOICE_RECOGNITION_MONO`, `MIC_MONO` ou `OFF` continuent à imposer le mono pour les comparaisons. Le nouveau collecteur utilise **AUTO par défaut**, contrairement aux trois essais précédents. Une préférence manuelle persistante OFF doit être retirée/remplacée par AUTO pour bénéficier du nouveau routage hors collecte.

## Sélection pendant la parole

Le sélecteur N canaux remplace le verrouillage par phrase lorsque la route adaptative est active. Il estime, pour chaque canal, l’excès d’énergie dans la bande vocale par rapport au bruit antérieur, lisse le score, pénalise la saturation et écarte les canaux muets ou les copies fortement corrélées du canal actif. Il ne multiplie pas le gain. Le score acoustique ne constitue jamais une preuve de présence de parole ; FireRed reste la validation vocale.

Un changement normal exige un avantage d’au moins 3 dB pendant 200 ms et un intervalle d’au moins 500 ms depuis le changement précédent, après une calibration initiale minimale de 500 ms. Un canal invalide peut être remplacé immédiatement. Le début de la session n’est pas supprimé pendant cette calibration.

Transition sur 10 ms, sans ajout ni suppression d’échantillons : fondu lorsque son énergie est suffisante, sinon raccord au point de plus faible discontinuité pour éviter un creux d’annulation entre canaux déphasés. Aucun mélange spatial permanent ni estimation de direction physique n’est imposé. Un changement de route invalide les anciennes statistiques.

Ces garde-fous limitent les sauts et le papillonnement ; ils ne prouvent pas encore l’absence de dégradation perceptive sur tout téléphone. Un bruit changeant peut tromper un score acoustique. Le test doit vérifier la reconnaissance obtenue par chacun des canaux et par la sortie sélectionnée, et non seulement le score du sélecteur.

## Diagnostic et vérification

Avec enregistrement privé activé, chaque canal est conservé séparément en WAV PCM16 et Float32 sous `adaptive-channels/<session>/channel-N/`. `selection.tsv` contient pour chaque bloc la séquence, le timestamp, la longueur, le canal choisi, les scores, la validité et le type de transition. Le signal mono choisi se retrouve dans les voies principales habituelles.

Les écritures de canaux sont faites sur un worker séparé avec file bornée. Une perte diagnostique est signalée ; elle ne bloque pas la capture principale. Limite de 180 s par enregistrement. Le collecteur vérifie la finalisation, les empreintes, la présence de tous les canaux et les longueurs Float/WAV, et refuse de qualifier comme complet un diagnostic ayant perdu des blocs. Les lectures AudioRecord partielles du chemin adaptatif conservent les échantillons intercalés jusqu’à avoir une trame complète.

Événements lisibles dans `guardian.log` et `audio-routing.log` :

- `ADAPTIVE_CHANNELS_PLAN` : route demandée, décision structurelle ;
- `ADAPTIVE_CHANNELS_READY` : nombre réel de canaux, activation effective ;
- `ADAPTIVE_CHANNELS` : canal choisi, scores, transitions, temps de sélection ;
- `ADAPTIVE_CHANNELS_RESET` / `OPEN_FAILED` : changement de route ou repli ;
- `ADAPTIVE_CHANNELS_WAV_SAVED` : fichiers finalisés, pertes et limite.

Le nouveau collecteur choisit `CANDIDATE`, profil `DISTANT` par défaut : gain de capture et de segment à 1, Qwen sur l’original sélectionné, aucun GTCRN diagnostique. Le profil reste un contrôle local ; les seuils FireRed ne changent pas. Le fichier de version natif est `guardian-voice-v3.4.5-adaptive-channels`.

## Installation et premier test

L’installateur remplace uniquement le module, après vérification SHA-256 et sauvegarde hors du projet. Reconstruire ensuite Android et installer l’APK sans désinstaller l’application pour garder Qwen.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.4.5-adaptive-channels.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

D’abord vérifier la capture à 3 m au calme, puis répéter sous aspirateur à position et puissance constantes :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-canaux.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Le script demande d’arrêter/redémarrer la protection avant de parler. Ne pas ajouter `VOICE_RECOGNITION_MONO` : cela empêcherait la comparaison des canaux. Envoyer le ZIP ou les `partie-*.zip` et `repartition.json` créés sous `diagnostics-voix`. Les canaux mono nécessaires seront aussi enregistrés sur un téléphone ne fournissant qu’un canal via les cinq voies habituelles, sans prétendre faire une sélection multicanale.

## Validation effectuée

- Tests Kotlin existants et nouveaux tests synthétiques N canaux réussis : changement pendant une phrase, canaux dupliqués, canal muet, quatrième canal utile, repli immédiat, mono bit à bit, longueur et bornes des échantillons.
- Compilation du moteur de capture et de la découverte contre les signatures réelles du framework Android API 35, sans stubs pour ces classes.
- Collecteur PowerShell exécuté avec ADB simulé : export complet de trois canaux, pertes diagnostiques, canal manquant et corruption de transfert.
- Installateur testé sur projet factice avec espaces, vérification du manifeste et conservation des fichiers de l’application.

APK complet non compilé ici ; précision, transitions audibles, coût du sélecteur et comportement sur le téléphone restent à vérifier. Le programme ne peut pas affirmer avoir choisi le meilleur signal vocal avant cette comparaison réelle. Les sondages ne garantissent pas un nombre de microphones physiques.
