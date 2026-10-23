# Comparaison locale des reconnaisseurs

`benchmark.py` exécute un modèle par processus, CPU deux threads, Sherpa-ONNX
1.13.8. Les fichiers de `--output` sont **privés** : transcriptions et chemins
des enregistrements. `summarize.py` exporte uniquement des agrégats et empreintes
des poids publics, jamais des empreintes de voix.

Le manifeste privé JSON comprend `id`, `path`, `scope` (`fixed` ou `firered`),
`label` (`positive`, `negative`, `short-word`), `condition`, `expected`. Les
entrées sont mono 16 kHz, PCM16 WAV ou float32 little-endian `.f32`.

- `fixed` : 12 phrases, 15 mots courts synthétiques, huit fenêtres sans voix ;
  mêmes frontières que l'étude des débruiteurs.
- `firered` : 12 phrases et 15 mots synthétiques, fenêtres acceptées par le
  candidat FireRed streaming + confirmation non-streaming de l'étude antérieure,
  queue 1,2 s. Les périodes sans voix y avaient toutes été rejetées. Le benchmark
  substitue le reconnaisseur sur ces sorties conservées ; il ne relance pas le
  VAD ni une capture Android.

Les modèles sont les exports des publications officielles Sherpa (le runtime
Whisper ici est Sherpa/ONNX, pas whisper.cpp ; aucun résultat de vitesse de
whisper.cpp/Metal/Vulkan n’est revendiqué) :

- `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` ;
- `sherpa-onnx-whisper-small`, encodeur et décodeur INT8 ;
- `sherpa-onnx-whisper-turbo`, encodeur et décodeur INT8 ;
- notre export Qwen3-ASR 0.6B : conv_frontend + encoder/decoder INT8 + tokenizer.

Source des archives : <https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models>.
Les archives Whisper peuvent inclure d'autres variantes ; seuls les fichiers
énumérés et hachés dans les résultats sont effectivement utilisés. Aucun poids
ni audio utilisateur n'est redistribué par ce dossier.

Parakeet utilise `from_transducer(model_type='nemo_transducer')` et détecte la
langue lui-même. Whisper utilise `language='fr', task='transcribe'`, recherche
gloutonne, `tail_paddings=-1` (défaut Sherpa). Qwen utilise `language='French'`,
512 tokens totaux, 256 nouveaux. Ce contrôle reprend la configuration française
actuelle ; ce n'est pas un test d'identification multilingue équitable en aveugle.
Pas de hotwords, modèle de langage auxiliaire, beam search ou débruitage.

Chaque modèle reçoit un warmup de deux secondes de zéros, non scoré, puis les
mêmes entrées. Les fenêtres négatives sont volontairement transcrites sans VAD :
un texte obtenu n'est pas une alerte émise par Guardian. Pas de référence textuelle
fournie au moteur.

Avec `--input-gain 64`, le gain effectif de chaque fenêtre est
`max(1, min(64, 0.8 / peak))`, appliqué de la même façon pour les quatre modèles.
Il ne change pas le rapport signal/bruit. Ce contrôle utilise la crête de la
fenêtre complète, donc une information future : ce n'est pas encore un AGC causal.
L'original n'est jamais écrasé. Le gain est conservé à l'entrée du reconnaisseur,
contrairement à l'étude de débruitage qui le retirait après le filtre.

`decodeSeconds` mesure l'appel de décodage uniquement, hors chargement, warmup
et `accept_waveform`. Pour les passes disposant de `recognitionSeconds`, ce
second temps inclut création du flux et acceptation de l'audio, hors lecture
du fichier. Ne pas transformer les RTF CPU hôte ou RSS du processus en mesures
de délai, batterie ou RAM sur un téléphone.

Exemple, avec chemins locaux adaptés :

```bash
python etude-reconnaisseurs/benchmark.py \
  --model parakeet-v3 --model-root /chemin/parakeet-v3-int8 \
  --manifest /chemin/prive/manifest.json --output /chemin/prive/resultats \
  --input-gain 1

python etude-reconnaisseurs/summarize.py \
  --inputs /chemin/prive/resultats/results-private.json \
  --output /chemin/public/agregats.json
```

Dépendances : numpy, sherpa_onnx==1.13.8. Le script de synthèse ne nécessite que
la bibliothèque standard. Les anciens résultats Qwen en cache ne sont pas
réutilisés : les comparaisons exécutent à nouveau le modèle pour cette étude.
