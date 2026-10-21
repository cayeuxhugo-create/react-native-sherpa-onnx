# Simulation d'une confirmation acoustique avant Qwen

## Résultat principal

Une vérification WebRTC VAD sur une copie de l'audio original rejette **6 des 8 fenêtres suspectes étudiées**, tout en laissant passer les **12 fenêtres de phrases réelles** à 1, 3 et 5 mètres et les **15 contrôles de mots courts synthétiques**.

Ce résultat est une piste de prototype, pas une correction Android validée. Deux fenêtres suspectes restent admises. L'admission des mots courts ne signifie pas que Qwen les transcrit correctement ; les erreurs de reconnaissance déjà documentées restent ouvertes.

Aucun changement du moteur Android, du gain micro, de Qwen ou des alertes n'a été effectué dans cette étude.

## Corpus et protocole

Le test porte sur 35 fenêtres complètes, avec l'audio original destiné à Qwen :

- Huit fenêtres négatives : quatre du silence confirmé du premier bloc, trois du bruit blanc déclaré et une du ventilateur. La dernière est issue d'une admission débutant avant la fin chronométrée, bien que sa publication ait lieu après. Les événements supplémentaires postérieurs au test sont exclus.
- Douze fenêtres positives : trois à 1 m, trois à 3 m et deux séries de trois à 5 m. Elles contiennent la phrase de référence réellement prononcée. La segmentation corrigée du dernier test à 5 m est utilisée.
- Quinze contrôles synthétiques : cinq mots ou expressions courts, à trois niveaux. Ils ne remplacent pas des mots d'urgence prononcés par une personne réelle dans le bruit.

Les fenêtres négatives sont reconstruites avec les changements VAD et les offsets enregistrés sur le téléphone, puis le segmenter Kotlin réel v3.2.1. Le nombre et les tailles des fenêtres reproduisent les événements du téléphone. Le troisième bloc incomplet n'entre pas dans ces huit fenêtres : les WAV de sa seconde session sont absents.

Chaque confirmation commence avec un état vierge sur une fenêtre complète déjà disponible. C'est le comportement d'un vérificateur placé avant l'appel Qwen final, pas une simulation du microphone ou d'un VAD continu conservant son état entre toutes les fenêtres.

Les scores concernent **la conservation ou le rejet des fenêtres**, sans relancer Qwen ni mesurer l'interprétation d'urgence. Les PCM destinés à Qwen ne sont pas modifiés.

## Comparaison

| Configuration | Fenêtres suspectes encore admises | Phrases réelles admises | Mots courts synthétiques admis |
|---|---:|---:|---:|
| Sans confirmation supplémentaire, sur ces fenêtres déjà sélectionnées | 8/8 | 12/12 | 15/15 |
| Confirmation Silero sur l'original, seuil 0,65, durée 192 ms, gain interne max. 4 | 0/8 | 5/12 | 14/15 |
| Confirmation WebRTC VAD, candidat retenu pour exploration | 2/8 | 12/12 | 15/15 |

La vérification Silero plus stricte élimine les huit fenêtres négatives de ce corpus, mais rejette sept fenêtres de parole réelle. Elle n'est pas retenue.

Le candidat WebRTC rejette les quatre fenêtres du silence confirmé et deux des trois fenêtres du bruit blanc. Une fenêtre de bruit blanc et celle du ventilateur restent admises. Cela ne signifie pas que le moteur aura 75 % de fausses alertes en moins sur le terrain : ces chiffres portent sur huit fenêtres choisies pour reproduire des problèmes connus, pas sur une mesure indépendante d'alertes.

Au total : 36 variantes WebRTC et 12 variantes de confirmation Silero ont été exécutées. Les résultats agrégés de toutes les variantes figurent dans `simulation-admission/results-summary.json`.

## Candidat WebRTC

Bibliothèque de simulation `webrtcvad-wheels==2.0.14.post1`, mode 1, audio mono PCM16 à 16 kHz, trames de 30 ms. La copie destinée au vérificateur reçoit un gain maximal de 4, borné par le pic. Ce gain **ne modifie ni le microphone ni le signal transmis à Qwen**.

La fenêtre est confirmée si WebRTC compte au moins 180 ms d'activité positive cumulée, dont une séquence positive d'au moins 60 ms. Ce n'est pas un seuil de volume et les 180 ms ne constituent pas un nouveau délai d'attente : le vérificateur travaille ici après formation de la fenêtre complète. L'effet réel sur le délai d'une urgence devra être mesuré dans une implémentation Android.

La simulation ne valide pas une reconnaissance rapide des mots d'urgence avant la fin d'une phrase. Elle vise d'abord à éviter certains appels Qwen inutiles, tout en conservant les fenêtres contenant la parole faible.

## Vérifications de sensibilité

Le candidat a été réévalué sur les mêmes clips avec neuf combinaisons : amplitude -6, 0 et +6 dB ; décalage de cadrage 0, 10 et 20 ms. Ces transformations affectent seulement les copies fournies au vérificateur.

- Les douze fenêtres de phrases et les quinze contrôles synthétiques restent admis dans les neuf combinaisons.
- Six fenêtres négatives sur huit sont rejetées dans huit combinaisons ; cinq sur huit dans la dernière.

Ces copies transformées ne sont pas de nouveaux enregistrements indépendants. Elles vérifient une sensibilité locale à l'amplitude et au cadrage, sans prouver la robustesse à d'autres bruits, pièces ou téléphones.

## Limites et prochaine étape technique

Le corpus positif utilise principalement une phrase répétée par une personne et un téléphone. Les 48 configurations ont été comparées sur le même petit corpus : il n'y a pas de jeu de validation tenu à l'écart. Le candidat peut donc être trop adapté à ces exemples.

La première étape d'intégration raisonnable serait un mode d'observation Android : calculer cette confirmation, inscrire ses décisions et conserver le chemin actuel pour mesurer les désaccords sur des voix humaines et des mots d'urgence réels. Un rejet effectif ne devra être activé qu'après vérification de la sensibilité et des deux faux résiduels. Cette intégration n'est pas incluse dans la livraison de simulation.

Le rôle des décisions doit rester explicite : ce vérificateur n'est ni un reconnaisseur de mots, ni une règle d'urgence, ni la preuve qu'une personne s'adresse au téléphone. Les mots reconnus et les alertes Guardian restent des étapes distinctes.

## Reproduction

Les scripts Python ne lisent que des fichiers locaux, ne déclenchent aucune alerte et refusent d'écraser un résultat existant. Dépendances : numpy, `sherpa-onnx==1.13.8`, `webrtcvad-wheels==2.0.14.post1`.

Créer un manifeste JSON privé :

```json
[
  {"id":"exemple-negatif","label":"negative","condition":"silence","path":"/chemin/prive/segment.wav"},
  {"id":"exemple-positif","label":"positive","condition":"5m","path":"/chemin/prive/phrase.f32"}
]
```

Les `.wav` doivent être PCM16 mono à 16 kHz ; les `.f32` sont des flottants 32 bits little-endian mono à 16 kHz. Les labels acceptés sont `negative`, `positive`, `short-word`.

```bash
python simulation-admission/evaluate.py \
  --manifest /chemin/prive/manifest.json \
  --silero /chemin/vers/silero_vad.onnx \
  --family webrtc \
  --output /chemin/prive/webrtc-results.json
```

Remplacer `webrtc` par `silero` et choisir une nouvelle sortie pour la seconde famille. `stress_webrtc.py` rejoue les neuf transformations du candidat. Les sorties détaillées contiennent des empreintes des données : elles restent privées. Aucun WAV, chemin privé ou identifiant de session utilisateur n'est publié.
