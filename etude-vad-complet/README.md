# Étude des flux complets — 22 octobre 2026

Le rapport principal est [ETUDE-ARCHITECTURE-VAD-COMPLET-20261022.md](../ETUDE-ARCHITECTURE-VAD-COMPLET-20261022.md).
Les scripts sont des outils de recherche sur ordinateur ; ils ne modifient ni
l'application, ni les fichiers audio sources. Les sorties détaillées sont privées :
elles contiennent des transcriptions et des chemins. Ne publier que les synthèses.

## Dépendances

Python avec numpy, onnxruntime, kaldi_native_fbank, kaldiio et sherpa-onnx==1.13.8 ;
Java et Kotlin 2.1.20 pour le segmenter. Les versions employées sont celles des
études précédentes : ONNX Runtime Python 1.31.0, kaldi-native-fbank 1.22.4,
kaldiio 2.18.1. Le module v3.3 présent dans ce dépôt fournit les assets Silero/GTCRN
pour la reproduction du baseline. Aucun appel distant de reconnaissance n'est fait.

Source officielle FireRed : commit `c30ec49e8cc69642b0ee65362eba11b9d11c6e54`.
Le dossier passé par `--model-root` doit contenir les sources `fireredvad/core/`
et `pretrained_models/onnx_models/` avec `fireredvad_vad.onnx`,
`fireredvad_stream_vad_with_cache.onnx`, `cmvn.ark`. Empreintes publiques dans
`validation-summary.json`. Qwen attend son dossier local avec conv_frontend,
encoder.int8, decoder.int8 et tokenizer.

## Données privées

`prepare_manifest.py` reçoit quatre WAV de parole, le manifeste privé des 65
contrôles contigus sans voix de l'étude précédente et le manifeste des mots courts.
La phrase de référence est un argument privé `--expected-phrase`. Chaque WAV vocal
est annoté ici comme trois répétitions : cette hypothèse doit être adaptée à un
autre corpus, pas réutilisée sans vérification.

Les entrées du manifeste final ont `id`, `label`, `path`, et pour la parole
`expected`/`expectedRepetitions`. Labels : `recorded-voice`,
`recorded-nonspeech`, `synthetic-short-word`. PCM mono 16 kHz, WAV PCM16 ou `.f32`
float32 little-endian. Aucune normalisation de l'audio destiné à Qwen n'est ajoutée.

## Exécution

Choisir des répertoires de sortie neufs. Les variables ci-dessous désignent les
chemins privés préparés localement ; les données ne sont pas incluses au dépôt.
Les variables Kotlin sont les classpaths du compilateur et de la bibliothèque.

```bash
bash etude-vad-complet/build_segmenter.sh "$LAB/segmenter-800" 800
python etude-vad-complet/compare_full_recordings.py \
  --manifest "$LAB/manifest-private.json" --output "$LAB/replay" \
  --model-root "$FIRERED_SOURCE" --segmenter-jar "$LAB/segmenter-800/segmenter.jar" \
  --kotlin-stdlib "$KOTLIN_STDLIB" --module modules/guardian-native-protection \
  --voice-stress
python etude-vad-complet/add_confirmation_variants.py \
  --results "$LAB/replay/results-private.json" --model-root "$FIRERED_SOURCE" \
  --output "$LAB/with-confirmations-private.json"
python etude-vad-complet/decode_windows.py \
  --results "$LAB/with-confirmations-private.json" --qwen "$QWEN_LOCAL" \
  --cache "$LAB/qwen-cache" --output "$LAB/decoded-private.json"
python etude-vad-complet/summarize.py \
  --results "$LAB/decoded-private.json" --output "$LAB/summary.json"
```

Pour l'ablation de queue, compiler un autre JAR avec `build_segmenter.sh ... 1200`,
rejouer uniquement `--strategies stream0.5` avec ce JAR et
`--strategy-suffix=-tail1.2`, puis appliquer confirmation et décodage avec le même
cache. Ajouter les lignes de ce résultat à celles du premier avant la synthèse
finale. Le suffixe ne change que l'identifiant du résultat, pas les calculs.

`check_causality.py --model-root ... --wav ...` vérifie, avec les modèles réels,
que prolonger un préfixe de huit secondes ne modifie pas ses décisions déjà
engagées. Choisir un WAV d'au moins seize secondes. Le préfixe est aligné sur le
pas de 500 ms, pour ne pas introduire une requête supplémentaire propre à l'EOF.

## Mesures et limites

Le WER est la distance de Levenshtein entre les mots normalisés du texte attendu
répété et la concaténation des sorties Qwen d'un enregistrement vocal. Les
occurrences exactes sont comptées dans chaque sortie individuelle. Les mots
synthétiques sont reconnus si leur séquence attendue apparaît dans une sortie.
Toutes ces mesures diffèrent de l'admission VAD et d'une alerte Guardian.

Les sorties silencieuses/non vides des périodes connues sans voix sont comptées
séparément. Les chiffres de calcul sont des temps muraux du replay hôte ; les
caches ASR ne sont pas interprétés comme un calcul instantané. Le baseline
recalcule GTCRN et les trois vues Silero ; les états natifs et la disponibilité
adaptative de l'enhancement du téléphone ne sont pas exactement reproduits.

La queue plus longue a été essayée après observation d'une régression sur les
mots courts. Les dix variantes ne sont pas dix validations indépendantes. Un jeu
neuf et un essai Android sont nécessaires avant une sélection de production.

Aucun silence de fin n'est fabriqué ; les finales non arrivées à maturité ne sont
pas forcées. Les décisions glissantes avec retard ne traitent que les échantillons
pour lesquels le contexte futur prévu a effectivement été observé.
