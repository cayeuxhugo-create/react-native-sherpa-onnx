# Banc d’étude privé du débruitage

Résultats et limites : [rapport](../ETUDE-BRUIT-ELARGIE-20261026.md). Ce dossier contient des outils de comparaison sur hôte, pas un remplacement du moteur Android.

Python nécessite numpy, scipy ; sherpa-onnx pour GTCRN. Les pilotes natifs attendent les installations locales suivantes sous `--tools` : `rnnoise-v01/librnnoise.so`, `speexdsp-1.2.1/libspeexdsp.so`, `deepfilter/deep-filter` et `deepfilter/source/models`. Le modèle GTCRN est celui des assets du module. Voir le rapport pour les versions.

Un manifeste de préparation est une liste JSON : `id`, `path` vers float32 little-endian mono 16 kHz, `prompts` (positions en secondes), `texts` (références), facultativement `stereo` (WAV PCM16 stéréo 16 kHz) et `shifts` (défaut −0,5 et +1,5 s). Les prises doivent commencer par du bruit sans parole entre 0,5 et 3 s pour les méthodes utilisant un profil initial. **Les répertoires produits contiennent de l’audio privé et ne doivent pas être publiés.**

```bash
python etude-bruit-elargie/prepare.py --dataset /private/dataset.json --output /private/new-study --tools /local/tools
python etude-reconnaisseurs/benchmark.py --manifest /private/new-study/manifest-private.json --model-root /local/qwen --output /private/new-asr --model qwen
python etude-bruit-elargie/score.py /private/new-asr/results-private.json --output /private/ranking.json
python -m unittest discover -s etude-bruit-elargie -p test_numerics.py
```

`--configs` accepte une liste de configurations comme dans `results/tested-configs.json` (sélectionner une des listes). Une signature empêche de réutiliser un cache d’une autre configuration ; les mesures indiquent le recours au cache et le temps d’audio effectivement traité. Les anciens caches non signés nécessitent un nouveau dossier. Les métriques historiques de préparation de la grille ne sont pas exploitées comme benchmark de vitesse.

`post_segments.py` applique les deux calibrations DF3 aux fenêtres effectivement proposées par le moteur, rejets compris. Son manifeste d’entrée indique `id`, `path`, `dataset`, `originalSegment`, `preRoll`, `availableAtSample`, `rawAccepted`, `group`. Il faut ensuite refaire la confirmation FireRed et le décodage Qwen ; les fenêtres décodées de force ne sont pas toutes des sorties autorisées.

`policies.py ROOT --output aggregate.json` analyse les artefacts privés de cette étude : `labels-private.json` contient les références par prise (champ `texts`), les résultats Qwen et les confirmations TSV sont décrits dans le script. Le scoring utilise une sous-séquence exacte dans l’ordre ; le nombre total de sorties est toujours conservé pour rendre les sorties supplémentaires visibles. Le champ `rawAccepted` désigne le verdict de la fenêtre d’origine à pré-roll 2 s, y compris pour la variante allongée : celle-ci ne simule pas une nouvelle admission brute à pré-roll 3 s.

Les JSON sous `results/` ne contiennent que configurations, empreintes de modèles et agrégats. Les sorties diagnostiques Qwen, WAV, manifestes nominatifs et journaux restent privés. La grille est exploratoire, avec choix des affinements sur le même corpus ; aucun jeu de validation indépendant n’est revendiqué.
