# Filtres classiques, deuxième étude

[Rapport complet](../ETUDE-FILTRES-CLASSIQUES-20261026.md). Outils d’hôte ; le moteur Android reste inchangé. numpy/scipy, sherpa-onnx1.13.8 pour l’ASR et le JNI FireRed pour les confirmations. Les sorties contiennent audio et transcriptions privés.

`filters.configs()` donne les26 réglages fixes ; `filters.apply(float32_mono16k, config)` retourne le même nombre d’échantillons. Les filtres ne reçoivent aucun texte. L’ancienne référence est explicitement non causale au démarrage. Les nouveaux réglages gardent l’original jusqu’à3s, puis font un fondu100ms. Leur calibration présuppose du bruit sans parole entre0,5et3s. Les variantes adaptatives utilisent une estimation de présence de parole approximative, pas une implémentation MCRA/OM-LSA.

```bash
python -m unittest discover -s etude-filtres-classiques -p 'test_*.py'
```

Pipeline de l’étude (manifestes et chemins privés) :

1. `corpus.py --recent /private/latest-comparison --old /private/denoise-wide --output /private/corpus.json` assemble les37 candidats uniques ; ne conserve qu’un préroll par candidat ancien. Les textes et annotations sont ceux des tests précédents.
2. `prepare_cases.py --corpus /private/corpus.json --output /private/screen --phase screen` traite les3prises récentes et extrait3paroles bruitées+5négatifs. Les configurations et les deux manifestes ASR sont figés avant inférence.
3. Utiliser `../etude-reconnaisseurs/benchmark.py` sur chaque manifeste pour Qwen (2threads, français, limites512/256). La confirmation réelle vient de `FireRedNativeTestKt` avec la politique de production. Ne pas transformer les résultats ASR forcés en sorties autorisées.
4. `evaluate.py --results ... --confirmation confirmations.tsv --output scores.json --select selected.json` classe sur les admissions, écarte les faux candidats et choisit au plus4familles nouvelles, plus les2références. Les confirmations manquantes/dupliquées sont des erreurs.
5. `prepare_cases.py --phase validate --selected selected.json ...` prépare6paroles récentes calmes+15anciennes+8négatifs. Pas de retouche de paramètres à cette étape.
6. `transfer.py` utilise les anciens manifestes de prises bruitées (positions de consignes), avec deux coupes fixes par énoncé. Les deux coupes ne sont pas des observations indépendantes.
7. Le rejeu intégral utilise `../etude-tests-recents/ExactReplay.kt`, avec les vrais index de blocs du téléphone, pour les deux premières méthodes du classement initial. Toutes les nouvelles fenêtres admises sont décodées. Le score par énoncé tient compte de l’ordre et les textes hors fenêtres parlées originales sont comptés séparément.
8. `summarize_study.py --root /private/study --baseline-study /private/latest-comparison --output results` produit les agrégats. `stress.py` est un contrôle synthétique de changement de bruit.

`delayed_compatibility.py` est un contrôle supplémentaire de disponibilité de l’ancienne formule, pas une nouvelle recherche de réglage. Il attend3,04s, amorce les états sur le buffer initial, et finit le fondu à3,14s. Les candidats commençant avant3,14s prennent entièrement la voie originale (confirmation et ASR). Les autres correspondent exactement à l’ancien PCM ; leurs résultats sont réutilisés sans être comptés comme de nouvelles inférences. La calibration reste invalide si elle contient de la parole.

Les agrégats incluent les effectifs réellement testés. Seules six configurations ont passé le contrôle complet24paroles/13négatifs ; les autres ne possèdent que le premier écran3paroles/5négatifs. Aucun jeu aveugle indépendant et aucune qualification Android ne sont revendiqués. Ne pas publier les manifestes nominatifs, échantillons ou résultats privés.
