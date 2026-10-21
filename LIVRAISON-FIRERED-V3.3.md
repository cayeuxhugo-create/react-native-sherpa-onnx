# Guardian Voice v3.3 — confirmation FireRed avant Qwen

Cette livraison ajoute le modèle **FireRed non streaming** comme validation
obligatoire des fenêtres finales avant Qwen. Le modèle et les quatre bibliothèques
Android sont inclus dans le module. Aucun téléchargement FireRed n'est nécessaire
sur le téléphone. Le modèle Qwen déjà installé reste utilisé tel quel.

## Chaîne réellement installée

Capture/micro et gain existants → Silero/GTCRN pour former les fenêtres candidates
→ **FireRed non streaming** sur une copie → Qwen sur la fenêtre originale complète
→ règles Guardian existantes.

TEN n'était pas actif dans la version v3.2.1 livrée : il avait été testé dans les
études. Ce changement ne remplace donc pas le prédécoupage Silero par FireRed
streaming. Il remplace l'admission fondée sur ce seul prédécoupage par une
confirmation FireRed. C'est bien la version non streaming qui a donné les meilleurs
résultats de confirmation. Aucun second microphone n'est ouvert.

Paramètres FireRed : seuil 0,5, moyenne sur 5 trames, confirmation après la séquence
minimale du post-traitement officiel (21 trames positives pour min_speech_frame=20).
Gain de la copie limité à 64 et par la crête à 0,8, sans atténuation de l'original.
Le pré-roll de 2 s, la queue de 800 ms, la fin après 1 200 ms de silence et les
fenêtres de 8 s avec recouvrement restent ceux de v3.2.1. Ni le gain du microphone,
ni la sélection des routes/micros, ni les mots d'urgence n'ont été modifiés.

## Exécution et erreurs

FireRed est exécuté sur le worker vocal ordonné, après formation de la fenêtre,
pas dans le callback du microphone. Les files existantes restent bornées. Les logs
`elapsedMs`, `inputWaitMs` et `droppedInputTasks` permettent de repérer un téléphone
trop lent. Qwen ne reçoit pas de décodage intermédiaire et ne reçoit que les fenêtres
acceptées. Les fenêtres rejetées conservent leur trace dans les WAV de diagnostic.

Le chargement vérifie les SHA-256 des trois fichiers modèle/CMVN. Un échec de
chargement est explicite ; une erreur d'inférence ne déclenche pas de repli silencieux
sur le niveau sonore ou sur une transcription non confirmée.

NCNN est lié statiquement dans `libguardian_firered_vad.so`, distinct de Sherpa et
d'ONNX Runtime. Les quatre ABIs sont fournies : arm64-v8a, armeabi-v7a, x86, x86_64.
Les segments ELF sont alignés sur 16 Ko. Aucune dépendance ONNX Java n'est ajoutée.
Les règles de réduction R8 conservent les noms nécessaires à JNI.

## Vérifications réalisées

- Compilation native croisée des quatre bibliothèques Android avec NDK r27c,
  NCNN 20260113. Inspection des dépendances ELF : aucune dépendance ONNX Runtime,
  ni libc++ partagée supplémentaire, ni libomp partagée.
- Exécution réelle du pont Kotlin → JNI → NCNN sur hôte Linux, sur 157 fenêtres,
  chacune répétée. Audio original inchangé ; décisions répétées identiques ;
  chargement d'assets invalides refusé, fermeture idempotente et usage après
  fermeture refusé.
- Test du branchement avec FireRed réel, VAD candidat volontairement permissif et
  Qwen simulé : zéro appel Qwen sur la fenêtre rejetée, un appel sur la fenêtre
  acceptée avec les échantillons originaux inchangés.
- 647 comparaisons NCNN/référence ONNX : aucune décision différente sur ce corpus.
  Écart maximal de probabilité 0,009052 ; les probabilités ne sont pas bit à bit
  identiques. Le frontend mobile a été comparé au frontend Kaldi de référence.
- Tests existants du cœur vocal, segmentation, ordre, reset, concurrence Qwen,
  WAV et diagnostics, plus neuf contrôles de la politique FireRed.
- Compilation de l'adaptateur Kotlin contre l'API Sherpa 1.13.8 avec framework
  Android remplacé par des stubs : vérifie les signatures, pas un APK complet.
- 14 tests du collecteur FireRed : cinq WAV, restitution des réglages, rejet d'un
  ancien APK ou d'un échec de démarrage FireRed.
- Installateur : manifeste SHA-256, sauvegarde hors projet, remplacement vérifié,
  réinstallation, refus d'une archive invalide et restauration après interruption.

**Aucun APK complet n'a été compilé ou exécuté sur téléphone dans cet environnement.**
Il faut reconstruire l'application et installer le nouvel APK pour utiliser le code
natif. Les bibliothèques Android sont compilées, mais leur exécution effective sur
le téléphone reste à vérifier lors de cette étape.

## Résultats conservés et limites

| Corpus | Résultat du filtre natif sur hôte |
|---|---:|
| 8 fenêtres suspectes sans parole | 8 rejetées |
| 12 segments de parole, dont 6 à 5 m | 12 acceptés |
| 15 mots courts synthétiques | 15 acceptés |
| 65 autres fenêtres sans parole | 63 rejetées |
| 24 phrases avec bruit ajouté | 22 acceptées |
| 18 bruits synthétiques | 18 rejetés |
| 15 mots synthétiques avec bruit ajouté | 15 acceptés |

Comme la référence, une phrase à 5 m est perdue dans deux variantes combinant -6 dB
et un décalage de 5/15 ms. Le filtrage ne peut pas rattraper une phrase que le
prédécoupage n'a pas captée. Ce ne sont ni des taux de transcription correcte, ni
des taux d'alerte Guardian, ni une garantie de résultats identiques sur tous les
appareils. Les vrais mots d'urgence courts restent à tester.

## Installation et test

Utiliser `installer-moteur-v3.3-firered.ps1` avec `-ProjectDirectory` pointant vers
l'application Expo. L'installateur remplace seulement
`modules/guardian-native-protection`, vérifie tous ses fichiers et sauvegarde
l'ancien module à côté du projet. Fermer les fenêtres qui verrouillent ce dossier
si Windows refuse son déplacement. Il ne lance pas EAS.

Après reconstruction/installation du nouvel APK :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-firered.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile DISTANT -DurationSeconds 60
```

Télécharger d'abord le collecteur `enregistrer-firered.ps1` de la même livraison
vers ce chemin temporaire. Faire un premier enregistrement de silence, puis les
répétitions à 1 m et 3 m, et à 5 m dans un enregistrement séparé. Le collecteur
vérifie que l'APK annonce `guardian-voice-v3.3-firered` et `FIRERED_READY`, puis
exporte les cinq WAV et les logs dans le projet. Il restaure les fichiers de contrôle
existants. Ne pas utiliser l'ancien collecteur strict `collect-guardian-logs.ps1`.

Événements à vérifier : `FIRERED_READY`, `FIRERED_RESULT` (accepted, elapsedMs,
peakProbability, longestPositiveFrames), `FIRERED_ERROR`, puis `QWEN_START` et
`QWEN_RESULT`. Un segment `FIRERED_REJECT` ne doit jamais produire de `QWEN_START`.

## Fichiers modifiés par rapport à v3.2.1

Production :

- `GuardianQwenPrimaryAudioProcessor.kt` : charge FireRed, filtre les fenêtres
  avant Qwen, journalise les décisions et ferme le modèle sur le worker propriétaire.
- `GuardianProtectionService.kt` : annonce v3.3/FireRed et libère Qwen si le
  processeur ne peut pas être créé.
- `android/build.gradle` : déclare les règles de conservation JNI.
- Nouveaux `GuardianFireRedConfirmation.kt`, `GuardianFireRedPolicy.kt`,
  `android/consumer-rules.pro`, sources natives `cpp/firered/`, assets `firered/`
  et quatre bibliothèques `jniLibs/*/libguardian_firered_vad.so`.

Tests/outils : tests de la politique et JNI, comparaison native/référence,
reconstruction des bibliothèques et synthèse agrégée dans `tests/results/`.
Le collecteur `enregistrer-firered.ps1` et son test sont fournis dans le dépôt.
Le ZIP contient le module complet, pas uniquement les fichiers modifiés.

Provenance et licences : `android/src/main/assets/firered/PROVENANCE.md`.
Les scripts/rapports publiés ne contiennent aucun WAV ni log de session privé.
