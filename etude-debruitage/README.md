# Comparaison hors ligne de débruiteurs

`compare.py` compare des fenêtres PCM identiques avec Qwen3-ASR 0.6B INT8,
Sherpa 1.13.8, français forcé, 512 tokens totaux / 256 nouveaux, deux threads.
Les résultats et les fichiers audio écrits dans `--output` sont **privés**.
`summarize.py` n'exporte que des agrégats, sans transcriptions ni identifiants.

Le manifeste JSON privé contient `id`, `path`, `label` (`positive`, `negative`,
`short-word`), `condition`, `expected`. Audio mono 16 kHz PCM16 WAV ou float32
little-endian `.f32`. Les originaux ne sont jamais écrasés.

Méthodes : `original`, `gtcrn`, `ulunas`, `deepfilternet3`, `resample-control`, `pcm16-resample-control`.
Le suffixe `-gain16` (ou `-gain64`) ajoute un gain constant borné à min(16 ou 64, 0,8/crête), jamais
inférieur à 1, avant le filtre, puis retire ce gain après traitement. Cette
normalisation par fenêtre utilise sa crête future : expérience hors ligne,
pas implémentation causale d'un contrôle de gain Android.

GTCRN : runtime Sherpa, état remis à zéro par fenêtre, vidange du tampon en fin.
UL-UNAS : export officiel ONNX, STFT centrée avec padding réfléchi, Hann périodique
512 / pas 256, état remis à zéro par fenêtre, ISTFT avec normalisation overlap-add.
Il utilise donc un contexte futur de 16 ms ; le script n'est pas un test de latence
temps réel. L'identité STFT/ISTFT sur une longueur non multiple du pas est contrôlée.

DeepFilterNet3 : CLI officielle 0.5.6 avec modèle embarqué, pas de post-filtre,
atténuation limite 100 dB, option `-D` de compensation du délai. Rééchantillonnage
polyphase 16→48→16 kHz ; ajout de 100 ms de zéros **uniquement pour vider le
débruiteur**, puis découpe à la longueur originale avant Qwen. La CLI sans cet
ajout raccourcit certains fichiers. `resample-control` vérifie l'aller-retour
sans débruitage. `pcm16-resample-control` ajoute la quantification PCM16 de sortie
de la CLI ; le suffixe `-gain256` permet aussi son contrôle à niveau relevé.
Qwen ne reçoit pas ces zéros supplémentaires.

Chaque sortie débruitée est soumise séparément à la confirmation FireRed
non-streaming de l'étude précédente, gain interne max 64, puis Qwen est exécuté
**même si FireRed rejette** pour mesurer le comportement propre du reconnaisseur.
Les textes forcés sur absence de voix ne représentent donc pas des alertes ou
des transcriptions publiées par la chaîne réelle.

Les options de chemins et `--methods` sont documentées par `compare.py --help`.
Fournir les dépendances numpy, scipy, onnxruntime, sherpa_onnx==1.13.8,
kaldi_native_fbank, kaldiio dans l'environnement Python. `--vad-harness` désigne
`etude-vad-complet/`, `--firered` le dépôt avec ses poids et sa CMVN officiels,
`--ulunas` le fichier `ulunas_stream_simple.onnx`, `--deepfilter` la CLI native,
`--gtcrn` le fichier `gtcrn_simple.onnx`, `--qwen` le répertoire de poids/tokenizer.
Le cache privé Qwen est vérifié contre les empreintes des modèles et les paramètres.

Pour agréger plusieurs passes :

```bash
python etude-debruitage/summarize.py \
  --inputs /chemin/prive/passe1/results-private.json /chemin/prive/passe2/results-private.json \
  --output /chemin/public/resultats-agreges.json
```

Limites : état par fenêtre, frontières figées, même locuteur/téléphone ; les mots
courts sont synthétiques. Pas de recalcul du découpage continu, de nouvelle prise
de son en bruit, de garantie multilingue ou de mesure d'énergie Android.
