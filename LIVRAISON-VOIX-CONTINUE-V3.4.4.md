# Prototype diagnostique v3.4.4 : débruitage continu et qualité des canaux

## Ce qui change

Une nouvelle voie **expérimentale, activable uniquement avec l’enregistrement WAV privé**, utilise GTCRN en continu, avec son état conservé d’un bloc à l’autre. Elle ne redémarre pas le modèle à chaque phrase. Elle compare les mêmes fenêtres temporelles que le chemin principal : original et débruité, verdicts FireRed, puis résultat Qwen diagnostique si la fenêtre débruitée a été confirmée.

GTCRN est déjà fourni dans les assets : aucun nouveau téléchargement de modèle de débruitage. **DeepFilterNet n’est pas intégré dans cette livraison** ; son pilote Android et son coût n’ont pas été validés. L’étude précédente ne démontrait pas un avantage suffisant pour en faire le moteur principal.

Le chemin principal conserve son PCM original et ses règles d’admission. Les sorties diagnostiques n’atteignent aucun listener de transcription produit et aucune règle d’alerte. Le score de canal expérimental est une option distincte et peut modifier le canal capté dans ce mode explicitement demandé.

## Modes

| Mode du collecteur | Capture et canal | Débruitage/comparaison |
| --- | --- | --- |
| `ORIGINAL` | Référence historique avec correctif de calibration du zéro numérique | Ancienne comparaison WAV GTCRN en profil NOISY |
| `CANDIDATE` | Gain de capture à 1, gain de segment à 1, maintien du canal pendant la parole | Ancienne comparaison WAV GTCRN en NOISY |
| `CONTINUOUS_STUDY` | Même candidat v3.4.3 | Nouvelle voie GTCRN continue et confirmation/ASR diagnostiques |
| `CONTINUOUS_QUALITY_STUDY` | Candidat avec score de canal lissé et pénalité de saturation | Même comparaison continue ; sélection réellement différente uniquement en stéréo Voice Focus |

Le mode par défaut du moteur reste `ORIGINAL`. Le nouveau collecteur demande par défaut `CONTINUOUS_STUDY`. Les modes expérimentaux nécessitent le fichier privé de contrôle et le WAV activé ; le collecteur sauvegarde puis restaure les contrôles précédents. Ne pas désinstaller l’application pour mettre à jour : cela supprimerait Qwen du stockage privé.

La sélection existante reposait déjà sur un rapport de bande vocale au bruit estimé, pas sur le seul volume. Le mode qualité ajoute un lissage causal (coefficient 0,25) et une pénalité bornée pour la saturation ; il conserve le maintien pendant la parole et l’exclusion des canaux invalides. Ce score ne prouve jamais la présence de voix. Un bruit peut encore tromper l’estimation : l’objectif du test est de vérifier cette option, pas de la déclarer supérieure à l’avance. Android doit réellement fournir deux canaux exploitables ; aucun microphone physique ou emplacement n’est inventé.

## Contraintes de fonctionnement

- Un worker GTCRN séparé, de faible priorité, avec file bornée, limite de 180 s et de 64 paires ; une discontinuité ou saturation invalide l’étude plutôt que de raccorder des morceaux audio sans lien.
- Un seul modèle Qwen partagé, exécuté sur son worker existant ; les FINAL principaux passent avant le diagnostic en attente, limité à une tâche. Un décodage déjà démarré n’est pas préemptible et peut retarder le suivant : mesurer la latence en mode diagnostic.
- Le segmenter expose désormais les positions exactes en échantillons. Les tests vérifient le contenu exact lors de coupures naturelles et de rollovers après pause, sans changer le PCM ni les limites de segmentation.
- Les sorties streaming sherpa sont indexées selon leur horloge d’échantillons, avec le délai de production séparé du contenu. Pas de remplissage artificiel. Une fenêtre dont le début a été évincé est signalée et ignorée.
- À l’arrêt : aucune complétion artificielle du signal, et les fenêtres dont la fin débruitée manque sont comptées. Les diagnostics Qwen non terminés sont signalés dans les métadonnées ; ne pas les compter comme une absence de parole.

## Installation

Télécharger `installer-moteur-v3.4.4-continuous-study.ps1`, `enregistrer-guardian-continu.ps1` et `preparer-envoi-diagnostic.ps1` depuis le commit de livraison indiqué dans la réponse. Exécuter seulement les commandes, sans les invites `PS C:\...>`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.4.4-continuous-study.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

L’installateur vérifie SHA-256, manifeste et contenu des fichiers et sauvegarde l’ancien module hors du projet. Il faut ensuite **reconstruire Android et installer le nouvel APK** avec la procédure habituelle. Le script n’exécute pas EAS. En cas de refus de déplacement du dossier : fermer les outils qui le verrouillent, arrêter Gradle, puis réessayer ; aucune suppression forcée de la version précédente n’est effectuée.

## Premier essai : calme à 1 m

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-continu.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -ProcessingMode CONTINUOUS_STUDY -CaptureRoute VOICE_RECOGNITION_MONO -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 1 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Arrêter complètement puis redémarrer la protection lorsque le collecteur le demande. Garder le silence jusqu’à chaque consigne `PARLER`. Lire chaque phrase une fois. Après la dernière phrase, laisser le temps au diagnostic Qwen de finir ; à la fin des 75 secondes, arrêter complètement la protection et valider comme demandé. Le ZIP est créé sous `C:\Projets\guardian-voice\diagnostics-voix`.

Ensuite refaire ce même test à 3 m, puis à 3 m sous aspirateur avec position et puissance fixes, en changeant les métadonnées de distance et de bruit. Pour évaluer les mots courts, utiliser `-TestCase WORDS` sur 75 s. Ne pas multiplier les distances avant d’avoir vérifié ce premier ZIP.

Pour comparer la sélection en stéréo, répéter une condition fixe avec `-CaptureRoute MIC_STEREO_FOCUS`, d’abord `CONTINUOUS_STUDY`, puis `CONTINUOUS_QUALITY_STUDY`. Ne pas comparer une source VOICE_RECOGNITION à une source MIC pour attribuer une différence au seul nouveau score. Le collecteur refuse un test si la route réelle ne correspond pas à celle demandée.

## Fichiers et métriques attendus

Le ZIP conserve les cinq voies WAV principales et leurs Float32 exacts. Dans `continuous-study/`, on trouve le flux original/débruité et, pour chaque fenêtre traitée, une paire de même longueur avec ses Float32 et son index. Les transferts sont vérifiés par SHA-256 ; les longueurs float et WAV doivent correspondre.

Événements : `CONTINUOUS_STUDY_READY`, `CONTINUOUS_STUDY_FRAME`, `CONTINUOUS_STUDY_WINDOW`, `CONTINUOUS_STUDY_ASR_OFFER`, `STUDY_QWEN_RESULT`, `CONTINUOUS_STUDY_END` et éventuellement `CONTINUOUS_STUDY_INVALID`/`WINDOW_SKIPPED`. Le résultat original est dans les événements FireRed/Qwen principaux, avec le même identifiant de segment. Les logs distinguent une fenêtre confirmée par la voie retraitée d’une sortie réellement publiée.

Mesures : durée native de débruitage, temps CPU du thread, facteur temps réel cumulé du débruiteur, retard en échantillons, âge de capture, saturation et crêtes, file d’attente, durée et attente Qwen. Le heap journalisé est celui de Java, pas la RAM totale. Le collecteur ajoute `dumpsys meminfo` avant, pendant (à partir de 20 s) et après pour examiner aussi la mémoire native. Ce ne sont pas des mesures de consommation batterie ; un test prolongé sera nécessaire pour cela.

## Validation de cette livraison

Tests hôte Kotlin réussis : régressions existantes, horloge d’échantillons et coupures, buffer sans raccordement de données évincées, qualité de canal, diagnostic Qwen sans publication et priorité des FINAL principaux. Compilation des adaptateurs contre l’API sherpa-onnx 1.13.8 réussie, avec stubs du framework Android. Collecteur PowerShell exécuté avec ADB simulé dans quatre cas : export complet, étude invalidée, corruption binaire, Float32 tronqué. Installateur testé localement sur un projet factice avec espaces et manifeste vérifié.

**APK complet non compilé ici ; comportement, précision, charge CPU/RAM et compatibilité non encore validés sur le téléphone.** Aucun résultat de précision supplémentaire n’est revendiqué pour ce prototype. Aucun changement de seuil FireRed ni de gain général n’est introduit dans le fonctionnement par défaut.
