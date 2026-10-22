# Débruitage léger et renforcé sur les WAV Guardian

## Conclusion

**Sur ce corpus, conserver l'audio original comme entrée principale de Qwen.**
Aucun des trois débruiteurs testés ne dépasse l'original sur les phrases, notamment
à 5 m. GTCRN avec préparation du niveau apporte un gain sur deux mots synthétiques,
mais dégrade trois phrases précédemment exactes. Cela justifie une recherche de
voie complémentaire, pas un remplacement systématique de l'original.

Ces essais sont exécutés sur CPU avec les vrais fichiers existants, sans changer
le téléphone ni le code de production. Les métriques sont reproductibles avec
[les scripts](etude-debruitage/README.md) ; [agrégats](etude-debruitage/resultats-agreges.json),
[modèles et paramètres](etude-debruitage/modeles-parametres.json),
[contrôles numériques](etude-debruitage/validation.json).

## Comparaison principale

Même Qwen3-ASR 0.6B INT8, mêmes limites de fenêtres, même durée, français forcé,
même cache vérifié et mêmes paramètres de décodage. Il ne s'agit pas d'une
comparaison de téléphones ou de nouveaux enregistrements.

Neuf configurations sur 35 fenêtres, plus trois contrôles sur 12 phrases :
**351 comparaisons appariées**, dont 315 nouveaux décodages Qwen et 36 résultats
en cache. Deux décodages indépendants supplémentaires contrôlent la répétabilité.

35 fenêtres par configuration : 12 phrases enregistrées (trois à 1 m, trois à
3 m, six à 5 m, 73,2 s), huit fenêtres suspectes confirmées sans voix (36,7 s),
15 mots courts synthétiques (71,93025 s). Les fenêtres sans voix sont choisies
parmi les problèmes antérieurs ; elles ne représentent pas un échantillon aléatoire.
Les mots synthétiques ne sont pas de vraies urgences enregistrées à 5 m.

Le gain est appliqué **avant** le filtre puis retiré **après** ; Qwen ne bénéficie
donc pas simplement d'un son plus fort. Pour chaque fenêtre, il est borné à 16,
64 ou 256 selon l'essai, avec crête limitée à 0,8 et sans atténuer l'entrée.
Cette normalisation utilise la fenêtre complète : ce n'est pas encore un AGC causal.

| Audio envoyé à Qwen | Phrases exactes /12 | Dont 5 m /6 | Mots synthétiques exacts /15 | Fenêtres sans voix acceptées par FireRed sur la sortie filtrée /8 |
|---|---:|---:|---:|---:|
| **Original** | **11** | **5** | **6** | **0** |
| GTCRN sans gain préalable | 8 | 2 | 6 | 0 |
| GTCRN, gain max 16 puis restauration | 9 | 3 | **8** | 1 |
| UL-UNAS sans gain préalable | 9 | 3 | 3 | 2 |
| UL-UNAS, gain max 16 puis restauration | 8 | 3 | 3 | 5 |
| DeepFilterNet3, CLI sans gain préalable | 0 | 0 | 1 | 0 |
| DeepFilterNet3, gain max 16 puis restauration | 4 | 0 | 4 | 0 |
| DeepFilterNet3, gain max 64 puis restauration | 6 | 2 | 4 | 0 |
| DeepFilterNet3, gain max 256 puis restauration | 6 | 2 | 4 | 1 |

Exact = égalité du texte après normalisation de casse, accents et ponctuation.
Les décomptes sont appariés sur les mêmes entrées. GTCRN gain 16 corrige une
phrase que l'original ratait, mais en dégrade trois autres : on ne peut pas
sélectionner la bonne sortie en connaissant à l'avance le texte attendu.
Sur les mots synthétiques, il corrige deux échecs sans perdre les six réussites
de l'original. Ce signal est intéressant mais provient d'une seule voix TTS.

Les 11/12 de cette référence ne contredisent pas les 12/12 de l'étude de découpage
complet : les frontières de segments sont celles du corpus fixe antérieur,
différentes de celles du candidat entièrement FireRed avec queue de 1,2 s.

## Ce que les contrôles changent dans l'interprétation

**DeepFilterNet3 : la CLI officielle écrit en PCM16.** Dix des douze fenêtres de
parole produisent une sortie entièrement nulle au niveau initial. La limitation
de quantification et l'atténuation ne permettent donc pas d'attribuer ce 0/12
uniquement à la qualité intrinsèque du réseau. Le source `write_wav_arr2` de la
version 0.5.6 confirme la conversion en entier 16 bits avec troncature.

Avec préparation du niveau max 64/256, aucune fenêtre de parole n'est entièrement
nulle, mais la transcription reste à 6/12. L'essai reste celui de **cette CLI,
ce modèle embarqué et ce frontal**, pas une évaluation d'un runtime float Android.
Un contrôle sur l'audio public fourni par UL-UNAS vérifie par ailleurs que la CLI
DeepFilterNet produit bien une sortie non nulle sur de la parole plus forte.

DeepFilterNet3 traite à 48 kHz ; les autres modèles à 16 kHz. Des contrôles
16→48→16 kHz sans filtre, avec et sans quantification PCM16 et préparation du
niveau, sont exécutés séparément sur les douze phrases. Leurs résultats sont
dans les agrégats : **11/12** pour le rééchantillonnage flottant seul, **10/12**
avec conversion PCM16, **8/12** avec préparation max 256, conversion PCM16 et
restauration. La chaîne de conversion peut donc changer le résultat de Qwen, même
sans débruiteur ; les pertes ne sont pas toutes attribuables au réseau de filtrage.
Deux nouveaux décodages sans cache reproduisent exactement la sortie originale
et celle du contrôle PCM16 préparé sur un cas divergent. Cette divergence est
donc reproduite sur ce cas, sans prouver une stabilité générale. Ces contrôles
ne reconstruisent pas la sortie flottante interne de DeepFilterNet.

Le modèle DeepFilterNet est le type `deepfilternet3`, lookahead
2, vérifié dans le journal de la CLI 0.5.6 ; pas de post-filtre, atténuation limite
100 dB. Cette configuration n'épuise pas les réglages possibles de la famille.

Les sorties conservent exactement la longueur originale. GTCRN est vidé en fin
de fenêtre ; DeepFilterNet reçoit 100 ms de zéros pour sa vidange, retirés avant
Qwen ; UL-UNAS emploie le cadrage centré officiel et une Hann périodique.
L'identité STFT/ISTFT est vérifiée à moins de 1e-6 ; les exports ONNX UL-UNAS
simplifié/non simplifié donnent la même sortie sur douze trames contrôlées.

## Faux textes et portée du VAD

Qwen est volontairement forcé sur toutes les fenêtres, même rejetées par FireRed,
pour isoler l'effet du filtre. Les textes obtenus sur absence de voix ne sont
**pas des alertes effectivement émises par Guardian**.

La dernière colonne montre le risque si l'on remplaçait aussi l'audio examiné
par FireRed par la copie débruitée. La confirmation sur les fenêtres originales
rejette les huit cas ; certaines copies filtrées les font accepter. La chaîne
recommandée continue à préserver l'original : ce tableau ne mesure pas son taux
de fausses alertes en production, ni un taux horaire sur les 500 s précédemment étudiées.

## Décision pour la suite

- Garder l'original pour la reconnaissance principale et sa confirmation.
- Conserver GTCRN comme référence légère ; explorer une seconde lecture contrôlée
  de certains cas, sans sélectionner les sorties avec le texte de référence.
- Ne pas remplacer GTCRN par UL-UNAS sur ces résultats.
- Ne pas ajouter DeepFilterNet3 systématiquement ; un futur essai devra employer
  un chemin float, un niveau maîtrisé et éventuellement une atténuation plafonnée.
- Tester ensuite sur de vraies voix douces avec bruit, réverbération, plusieurs
  locuteurs et téléphones avant de choisir un traitement général.

Limites : état remis à zéro par fenêtre, absence de replay complet avec état
continu pour ces filtres, même locuteur/téléphone pour les phrases, français
seulement, pas de mesure de batterie Android ni de validation de toutes les
langues européennes. Les fichiers originaux sont les PCM destinés à l'ASR ; ils
peuvent déjà refléter le traitement de capture Android. Aucun audio ni texte
utilisateur n'est publié ; les WAV, sorties et transcriptions restent dans
l'espace de travail privé.
