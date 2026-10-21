# Étude complémentaire VAD : FSMN face à FireRed — 21 octobre 2026

## Conclusion

FSMN-VAD est un candidat réel supplémentaire, mais ne remplace pas avantageusement
FireRed non streaming sur ce corpus. Son réglage sensible conserve davantage de
phrases avec bruit ajouté, au prix de davantage de bruit admis. Exiger l'accord des
deux élimine les 65 fenêtres de contrôle sans parole, mais aggrave la perte de voix
faible dans deux variantes. Aucune modification Android n'est livrée ici.

SpeechBrain CRDNN n'a pas pu être exécuté : son hébergeur de poids est inaccessible
depuis cet environnement (HTTP 403). Il n'a donc aucun score dans cette étude.

## Matériel effectivement utilisé

- FSMN ONNX et pré/post-traitement du **portage tiers**
  [lovemefan/fsmn-vad](https://github.com/lovemefan/fsmn-vad/tree/7bb4811a628e29f4645fae15fc9106b2932d089e),
  commit `7bb4811a628e29f4645fae15fc9106b2932d089e`.
- Modèle offline de 1 725 472 octets, frontend fourni : FBank 80 dimensions,
  trames 25 ms/pas 10 ms, fenêtre Hamming, dither 0, LFR 5/1 et CMVN fournie.
- Post-traitement fourni : fenêtre 200 ms, transition vers parole 150 ms,
  fin après 800 ms de silence, anticipation du début 200 ms et queue 100 ms.
- ONNX Runtime CPU, un thread. Aucun envoi de voix à un service distant.
- Gain maximum 1/4/16/64, limité par une crête de 0,8, appliqué à une copie de la
  fenêtre complète. Aucun changement de l'audio destiné à Qwen.
- Seuil `speech_noise_thres` 0,4/0,6/0,8. Ce paramètre du post-traitement FSMN
  n'est pas directement comparable à une probabilité FireRed ou Silero.

Le README du portage annonce un apprentissage sur corpus chinois. Cette étude
mesure ce modèle précis sur les voix françaises disponibles ; elle ne prétend pas
évaluer toutes les versions actuelles de FSMN/FunASR. Le dépôt comporte une licence
MIT, mais la provenance et les droits des poids exportés restent à vérifier avant
distribution dans Guardian. Les modèles ne sont pas redistribués ici.

## Protocole

Même corpus que les études précédentes :

- 8 fenêtres de bruit ayant conduit à des décodages suspects ;
- 12 segments de parole réelle : 3 à 1 m, 3 à 3 m et 6 issus de deux tests à 5 m ;
- 15 mots courts **synthétiques** : 5 mots à 3 niveaux ;
- 65 fenêtres supplémentaires sans parole, provenant de cinq sessions valides
  de 100 secondes ;
- 18 bruits synthétiques, 24 copies bruitées des 12 phrases (SNR 20/10 dB),
  et 15 mots synthétiques bruités (SNR 10 dB).

Les contrôles supplémentaires proviennent des mêmes enregistrements ou de
transformations synthétiques : ils ne constituent pas une validation indépendante.

12 configurations initiales ont été exécutées. Puis 45 variantes ont croisé trois
seuils, trois niveaux (-6/0/+6 dB) et cinq décalages (0/5/10/15/20 ms) sur les 35
fenêtres de départ. Le décalage retire ces premières millisecondes avant le frontend,
comme dans l'étude FireRed. Les résultats ne mesurent **ni transcription correcte,
ni alertes Guardian, ni performance Android, ni découpage d'une session complète**.
Un mot admis signifie seulement que du signal est reconnu comme parole quelque part.

## Résultats sur les fenêtres originales

| Configuration | Bruits rejetés | Paroles admises | Mots synthétiques admis |
|---|---:|---:|---:|
| FSMN sans gain ajouté, seuil 0,6 | 8/8 | 5/12 | 15/15 |
| FSMN gain max 16, seuil 0,4 | 8/8 | 10/12 | 15/15 |
| FSMN gain max 64, seuil 0,4 | 8/8 | 12/12 | 15/15 |
| FSMN gain max 64, seuil 0,6 | 8/8 | 12/12 | 15/15 |
| FireRed non streaming, gain max 64, seuil 0,5 | 8/8 | 12/12 | 15/15 |

Le gain 64 n'est pas une recommandation de gain du microphone. Il s'agit seulement
de la copie passée au détecteur pour vérifier une fenêtre déjà constituée.

## Contrôles supplémentaires

| Configuration | Sans parole rejetés | Bruits synthétiques rejetés | Phrases bruitées admises | Mots synthétiques bruités admis |
|---|---:|---:|---:|---:|
| FireRed | 63/65 | 18/18 | 22/24 | 15/15 |
| FSMN seuil 0,6 | 59/65 | 18/18 | 22/24 | 15/15 |
| FSMN seuil 0,4 | 52/65 | 16/18 | 24/24 | 15/15 |
| Accord FireRed ET FSMN 0,6 | 65/65 | 18/18 | 21/24 | 15/15 |
| Accord FireRed ET FSMN 0,4 | 65/65 | 18/18 | 22/24 | 15/15 |

Le score 65/65 de l'accord paraît intéressant, mais ne suffit pas pour l'activer :
les variations suivantes révèlent une dégradation sur la voix distante.
Les opérations ET/OU sont calculées sur les décisions des mêmes fenêtres, pas par
un nouveau système natif ni par un entraînement. Le OU avec FSMN 0,4 garde 24/24
phrases bruitées, mais ne rejette plus que 50/65 fenêtres sans parole et 16/18 bruits
synthétiques : il ne résout pas le problème des faux passages.

## Variations de niveau et découpage

Sur les 15 variantes de chaque configuration :

| Configuration | Variantes conservant 8/8 rejets, 12/12 paroles et 15/15 mots | Minimum de paroles admises |
|---|---:|---:|
| FireRed seul | 13/15 | 11/12 |
| FSMN 0,4 seul | 11/15 | 11/12 |
| FSMN 0,6 seul | 8/15 | 10/12 |
| Accord FireRed ET FSMN 0,4 | 13/15 | **10/12** |

À -6 dB et avec un décalage de 5 ou 15 ms, FireRed et FSMN 0,4 perdent chacun une
phrase différente à 5 m. Leur accord en perd donc deux. FSMN 0,4 admet également
une fenêtre de bruit à +6 dB dans deux variantes ; FireRed la rejette.
Le même nombre de variantes réussies ne signifie donc pas la même gravité des pertes.

## Vérifications et reproductibilité

- Inference et frontend réels, aucun modèle simulé.
- Réinitialisation explicite du post-traitement avant le premier exemple pour
  appliquer effectivement le seuil choisi, puis réinitialisation upstream entre
  les exemples. Assertion du seuil interne avant chaque configuration.
- Inversion de l'ordre des 35 fenêtres : mêmes décisions et mêmes bornes FSMN,
  ce qui vérifie l'absence de dépendance à l'ordre dans cet essai.
- Vérification de la forme et des bornes des intervalles produits par FSMN.
- Comparaisons ET/OU uniquement après vérification que les identifiants des fenêtres
  correspondent. Aucune moyenne mélangeant des corpus différents.
- SHA-256 des deux modèles et de la CMVN consignés dans `fsmn-summary.json`.
- Scripts `recherche-moteur/evaluate_fsmn.py` et `summarize_fsmn.py`, résultats
  agrégés `recherche-moteur/fsmn-summary.json`. Les manifests et WAV restent privés.

Dépendances de l'évaluateur : numpy, onnxruntime, PyYAML, kaldi_native_fbank et le
checkout du portage épinglé ci-dessus. Arguments : `--source`, `--manifest`,
`--output`, puis `--stress` pour les variations ou `--fixed --threshold .4` pour
les contrôles. Le manifeste privé contient `id`, `label`, `condition`, `path` ; les
fichiers sont mono 16 kHz PCM16 WAV ou float32 little-endian `.f32`.

## Suite proposée

Conserver FireRed comme référence de confirmation à prototyper en observation.
FSMN peut être observé en parallèle pour comprendre les désaccords, mais l'accord
strict des deux ne doit pas devenir un blocage de production sur ces seules données.
Il faut mesurer sur téléphone le découpage continu, la latence et la consommation,
puis valider les débuts/fins de vrais mots d'urgence courts prononcés doucement par
plusieurs personnes. Les résultats de fenêtres déjà préparées ne prouvent pas la
préservation des syllabes au découpage.

Pour SpeechBrain : la recette officielle
[LibriParty CRDNN](https://github.com/speechbrain/speechbrain/blob/develop/recipes/LibriParty/VAD/hparams/train.yaml)
et son [interface VAD](https://github.com/speechbrain/speechbrain/blob/develop/speechbrain/inference/VAD.py)
sont consultables. Les poids `speechbrain/vad-crdnn-libriparty` sur Hugging Face et
le modèle FSMN officiel sur ModelScope ont renvoyé HTTP 403 depuis cet environnement.
Le portage GitHub FSMN accessible a permis le test ci-dessus ; aucun poids SpeechBrain
accessible n'a été obtenu et aucun score n'est revendiqué pour lui.
