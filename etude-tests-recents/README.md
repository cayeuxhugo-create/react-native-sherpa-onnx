# Étude des trois derniers tests

Voir le [rapport](../ETUDE-TESTS-RECENTS-20261026.md). Outils d’hôte uniquement ; sorties privées, aucune modification du moteur Android.

Dépendances : numpy/scipy/sherpa-onnx 1.13.8/onnxruntime ; outils natifs déjà décrits dans `../etude-bruit-elargie/README.md`. Les ASR et débruiteurs sont chargés localement. Aucun réseau n’est utilisé par les scripts d’inférence.

`prepare_recordings.py --inputs inputs.json --output /private/new --tools /local/tools` attend une liste `{id, root, negativeSegments?}`. Les trois IDs anonymes de cette étude sont `quiet1m`, `quiet3m`, `vacuum3m`. Le dossier de chaque prise doit contenir les WAV/F32 et `events-private.json` extraits des logs, ainsi que `test-plan.json`. Une fenêtre admise par l’une des deux voies est associée dans l’ordre au protocole ; les faux positifs explicitement confirmés par l’utilisateur sont exclus via `negativeSegments`, jamais traités comme une phrase prononcée.

La préparation émet 40 configurations, un manifeste privé, les métriques et les éventuels échecs. Les fenêtres négatives sont conservées pour l’ASR forcé et pour l’admission acoustique. Les méthodes continues traitent la prise entière ; les méthodes par fenêtre sont signalées. Le mélange GTCRN réutilise le vrai flux continu enregistré sur téléphone, avec ses positions exactes.

Utiliser ensuite `../etude-reconnaisseurs/benchmark.py` pour Qwen/Parakeet/Whisper. `streaming_asr.py` évalue les deux modèles français, avec `--decoding-method greedy_search` ou `modified_beam_search --beam 4`. Il remet l’état à zéro entre les fenêtres et applique un tail de 0,8 s à la fin pour vider le reconnaisseur streaming ; ce n’est pas la segmentation Android.

`vad.py` compare Silero/TEN sur les fenêtres et expose explicitement seuil, durée minimale et gain maximal. Les décisions FireRed de l’étude utilisent le JNI de production via le harness `FireRedNativeTestKt` existant.

`ExactReplay.kt` se compile avec les sources de production `GuardianFireRedStreaming`, `GuardianFireRedConfirmation`, `GuardianFireRedPolicy`, `GuardianNeuralInputGain` et `GuardianSpeechSegmenter`. Arguments : assets, TSV d’entrées, dossier privé de sortie. Le TSV contient ID anonyme, chemin float complet, chemin du TSV d’index du téléphone. Le replay respecte les tailles et séquences des blocs `asr`, publie tous les candidats et leurs positions exactes, sans flush artificiel. Les fenêtres originales et verdicts ont été vérifiés identiques à ceux du téléphone avant d’interpréter les transformations.

`score.py --root /private/study --output /private/aggregate.json` lit les résultats et confirmations de cette étude. La référence des huit succès du téléphone est explicitement définie pour ces trois prises ; ce mapping doit être remplacé pour un autre corpus. Les compteurs d’ASR forcé et d’ASR admis restent séparés. Les agrégats contiennent le nombre de fenêtres, pour ne pas interpréter un essai partiel comme une série complète.

```bash
python -m unittest discover -s etude-tests-recents -p test_score.py
```

Les JSON publiés sous `results/` ne contiennent que statistiques, configurations et empreintes des modèles. Les anciens contrôles se lisent séparément : leur objectif est la non-régression, pas l’augmentation artificielle du nombre d’exemples indépendants. Les variantes annexes de reconnaisseurs ne couvrent pas toutes les combinaisons de la grille Qwen. Aucun jeu de validation aveugle inédit n’est revendiqué.
