# Guardian Voice v3.5.2 — choix du micro et décodage plus fluide

Version candidate pour essais réels, basée sur `18fd88c3d1116d2eb0a627b8e4dd5af7c29b7dd8`.

## Changements actifs dans le moteur

Le moteur conserve sa capture et son admission principale FireRed + FSMN. Pour chaque phrase admise en multicanal, il examine **tous les canaux exploitables et continus de cette fenêtre**, au lieu de s’arrêter au premier canal accepté. Chaque remplaçant doit réussir ses propres confirmations. La qualité compare la bande 120–3800 Hz sur un support vocal commun, par rapport au fond de la même fenêtre, avec pénalité d’écrêtage. Elle ne compare pas simplement les niveaux sonores.

Un canal comparable ne remplace pas l’incumbent ; une amélioration mesurée d’au moins 3 dB de contraste est nécessaire, sauf pour quitter un canal fortement écrêté au profit d’un canal confirmé non écrêté. En l’absence d’estimation de fond exploitable, le canal confirmé en place est conservé. Les silences, les canaux nuls, les ruptures de route et les canaux non confirmés ne peuvent devenir une preuve de parole. La sélection s’applique à **la phrase entière** : Qwen reçoit réellement le PCM du canal retenu, sans collage de micros au milieu de la fenêtre et sans nouveau gain ajouté.

Ce choix signifie « meilleur contraste acoustique mesuré parmi les canaux confirmés ». Il ne prétend pas identifier un microphone physique précis du téléphone ni garantir le meilleur mot reconnu. Le contrôle sur appareil reste indispensable.

Un refus de l’original ne peut pas être contourné par l’acceptation d’un autre micro. Les relectures ont confirmé qu’une perturbation refusée par FSMN sur le canal principal pouvait être acceptée sur l’autre canal. La voie originale reste donc la garde d’admission. Cette version ne récupère pas automatiquement les phrases que cette garde refuse déjà dans le bruit.

La file Qwen reste unique et les finales principales prioritaires. Les diagnostics dont le PCM float32 est strictement identique sont dédupliqués par SHA-256, indépendamment de leurs identifiants de candidat. L’identité est limitée à la génération courante du décodeur et à 32 entrées ; reset et échec de décodage permettent les tentatives appropriées. Le statut `DUPLICATE_PCM`, avec `duplicateOfSegment` et `duplicateOfLane`, distingue explicitement ce cas d’un échec, d’un refus acoustique ou du silence. Les variantes contenant réellement un contexte différent restent distinctes.

## Observabilité pour vérifier le micro choisi

Les nouveaux événements sont :

- `ASR_MICROPHONE_QUALITY` : par canal, confirmations, contraste, écrêtage, sélection et révision de route.
- `ASR_MICROPHONE_SELECTION` : canal retenu pour la phrase entière, canaux comparés, bornes et motif.
- `ASR_INPUT` : canal effectivement fourni à Qwen, nombre d’échantillons, fréquence et SHA-256 du PCM après l’éventuel gain existant.

Le nouveau collecteur conserve ces événements dans `session.json`, sous `asrMicrophoneSelections`, `asrMicrophoneQualities` et `asrInputIdentities`. `diagnosticDuplicateOffers` conserve les liens des diagnostics dédupliqués. Les tableaux historiques dans les logs restent encodés comme chaînes ; les mesures de qualité ont aussi leurs propres événements à champs scalaires.

Les WAV continus de capture restent des traces de capture, pas un assemblage des phrases finalement choisies pour Qwen. Pour contrôler le signal décodé, utiliser le canal, les bornes et `ASR_INPUT.pcmSha256` ; ne pas supposer que le seul nom `asr` d’un WAV continu décrit toutes les fenêtres décodées après sélection d’un autre canal.

## Étude filtrée : corrections portées, limite maintenue

Les mesures de qualité et le choix du PCM sont maintenant actifs dans le chemin principal. Les diagnostics filtrés gagnent également :

- le nombre de trames positives consécutives FireRed ;
- un indicateur `calibrationQualified` distinct du simple profil disponible ;
- un motif `FIRERED_REJECT` affiché avant `CONTEXT_PREFERRED` quand la confirmation refuse ;
- la distinction explicite entre présence de parole et exactitude du texte.

La voie Wiener LONG indépendante reste `SHADOW_NO_ALERT`. Elle **n’est pas promue automatiquement** : le deuxième essai fournit un contre-exemple à FireRed ∧ FSMN sur le filtré. Une calibration sans activation VAD n’est pas, à elle seule, une preuve humaine d’absence de parole. Aucun remplacement lexical, liste noire de mots ou durcissement global de seuil n’a été ajouté. Les substitutions lexicales observées ne sont pas présentées comme corrigées.

## Vérifications

La livraison fournit des tests hôtes Kotlin, des contrôles des signatures Android et Sherpa, un test du collecteur PowerShell simulé et des outils opt-in de relecture native des captures privées. Les tests de sélection couvrent notamment un canal plus clair, un canal seulement plus fort, l’écrêtage, la confirmation obligatoire, l’absence de fond et la stabilité du choix. Les tests de file couvrent identité audio, bornage, priorité des finales, reset et arrêt.

Les deux captures ont été relues avec les vrais modèles FireRed/FSMN. Le contrôle du pipeline vérifie les identifiants admis et l’égalité exacte entre la fenêtre du microphone sélectionné et le PCM fourni au décodeur. **Qwen est remplacé par une doublure dans ce contrôle** : il ne valide pas de nouveaux textes reconnus. Le détail des tests réellement terminés est enregistré dans `modules/guardian-native-protection/tests/results/v3.5.2-microphone-selection-summary.json`.

Les modèles et bibliothèques Android livrés sont ceux de la base. Les bibliothèques JNI hôtes de test ont été compilées séparément et ne remplacent pas celles du téléphone. Aucune voix ni transcription issue des captures privées n’est incluse dans l’archive, les tests ou le manifeste. Le projet complet de l’application Windows n’est pas présent ici : aucune compilation APK complète ni installation sur téléphone n’est revendiquée.

## Installation dans le projet Windows

Télécharger l’archive `modules-voix-v3.5.2-microphone-selection.zip` et le script `installer-moteur-v3.5.2-microphone-selection.ps1` dans le même dossier. Le script exige l’archive locale et vérifie son SHA-256, puis les fichiers du manifeste. Il sauvegarde l’ancien module hors du projet et restaure celui-ci si le remplacement échoue.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\Downloads\installer-moteur-v3.5.2-microphone-selection.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -ArchivePath "$env:USERPROFILE\Downloads\modules-voix-v3.5.2-microphone-selection.zip"
```

Recompiler ensuite depuis le projet de l’application avec la commande Android habituelle ; pour un projet Expo configuré pour la compilation locale :

```powershell
Set-Location 'C:\Projets\guardian-voice'
npx expo run:android --device
```

Installer par-dessus la même application `com.hugo3154.guardianvoice`, avec la même signature. Ne pas désinstaller l’application ni effacer ses données : le modèle Qwen déjà installé doit être conservé. Si Android refuse la mise à jour pour signature différente, corriger la chaîne de signature avant de poursuivre ; l’installateur du module ne touche pas aux données du téléphone.

## Essai réel à effectuer

Utiliser `enregistrer-guardian-micros.ps1` et `preparer-envoi-diagnostic.ps1`, fournis dans l’archive, plutôt que le collecteur de la v3.5.1. Le nouveau collecteur exige `guardian-voice-v3.5.2-microphone-selection`.

Extraire le ZIP dans un dossier d’outils neuf, puis se placer dans ce dossier :

```powershell
$outils = Join-Path $env:TEMP ('guardian-352-' + [Guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\modules-voix-v3.5.2-microphone-selection.zip" -DestinationPath $outils
Set-Location $outils
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File '.\enregistrer-guardian-micros.ps1' -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode REFERENCE_NOISE_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 5 -VoiceLevel SOFT -NoiseDescription 'piece calme'
```

Garder vingt secondes sans parole au début, prononcer une seule fois les phrases prévues, puis ne plus parler. Décrire les mots réellement prononcés et les bruits survenus ; les consignes affichées ne suffisent pas comme vérité terrain. Refaire ensuite l’essai à 3 m avec bruit constant, et un essai sans parole. Pour vérifier les micros, répéter avec une orientation différente du téléphone, sans toucher ni masquer volontairement les micros pendant une phrase.

Évaluer séparément les omissions, substitutions, textes indus, le canal réellement utilisé, les temps de confirmation et d’attente Qwen, ainsi que les pertes de capture. Le premier contrôle réel doit confirmer qu’un changement de micro mesuré améliore aussi la transcription et ne dégrade pas les mots courts. Conserver la sauvegarde du module précédent pour comparer les deux versions.
