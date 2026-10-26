# Comparaison élargie sur les trois derniers tests téléphone

## Résultat utile

Une configuration **Wiener léger → confirmation FireRed → Qwen**, sur les fenêtres proposées par le détecteur original, restitue les **9 phrases exactes sur 9** des trois derniers tests, dont les trois phrases sous aspirateur. Elle conserve les huit succès observés sur le téléphone. Les cinq fenêtres négatives proposées par le détecteur original sur ces enregistrements sont toutes rejetées après traitement.

Cela ne suffit pas à en faire le traitement par défaut : le même filtre appliqué à quinze anciens énoncés au calme donne **13/15**, contre **15/15** sans filtre, et rouvre un ancien candidat négatif au calme. Les six mots courts sont conservés ; deux longues phrases régressent, dont une à 5 m. Il faut donc étudier un déclenchement réservé au bruit et une voie originale de référence.

Aucun changement du moteur Android ou de son gain n’est livré par cette étude. La v3.4.5 multicanale reste distincte : les trois tests reçus ici étaient en mono imposé. Les autres microphones ne peuvent pas être reconstitués depuis leurs WAV.

## Étendue exécutée

**1 091 décodages locaux**, sans envoi des voix à un service externe :

| Essai | Décodages |
| --- | ---: |
| Qwen, 40 configurations × 14 candidats réels | 560 |
| Parakeet v3, Whisper Small, Whisper Turbo : original et GTCRN | 84 |
| Zipformer français 2025 : original, GTCRN et huit traitements supplémentaires | 140 |
| Zipformer français mobile 2023 : même base et traitements supplémentaires | 140 |
| Huit traitements sur les trois phrases bruitées pour Parakeet et les deux Whisper | 72 |
| Recherche par faisceau, Zipformer 2025, largeur 4 | 28 |
| Rejeu intégral, puis Qwen sur toutes les fenêtres admises | 28 |
| Anciennes phrases/mots courts/5 m, filtre et référence, plus contrôles négatifs | 39 |
| **Total** | **1 091** |

Les 560 candidats transformés ont également été soumis à la confirmation FireRed native. Deux variantes de VAD supplémentaires, Silero et TEN, ont été exécutées sur les fenêtres originales et GTCRN, avec gains maximaux 1/16/64 : 168 décisions de contrôle. Ce ne sont pas 168 essais de segmentation intégrale.

Le corpus récent contient neuf phrases prononcées, mais seulement trois textes différents. Les anciens quinze énoncés ajoutent six mots courts et trois répétitions à 5 m. La quantité de décodages ne constitue pas autant de données indépendantes. La grille est exploratoire et choisie sur ces mêmes données, sans jeu aveugle inédit.

## Traitements examinés

Quarante configurations fixes, appliquées à chaque prise :

- Original ; normalisation scalaire bornée ×2/×4/×8, sans débruitage.
- Passe-haut 100/150/300/450 Hz ; trois encoches sur les pics de bruit stationnaire.
- Wiener : sur-soustraction 0,5/1/2 et plancher 0,25/0,5/0,75 ; quatre variantes d’estimation dirigée par décision.
- SpeexDSP : suppression −6/−12/−24/−36 dB.
- RNNoise v0.1 complet et mélange 50 %.
- GTCRN réellement enregistré sur téléphone : traitement complet et mélanges 25/50/75 % avec l’original, sans décalage entre voies.
- DeepFilterNet3 standard, normalisations bornées ×8/×16 et atténuations 6/9/12/18/24 dB ; modèle faible latence à 12 dB.
- UL-UNAS complet et mélange 50 %.

Les traitements continus conservent leur contexte sur la prise complète ; les variantes DF3 et de gain scalaire sont explicitement des traitements de fenêtres terminées. Les modèles ne reçoivent ni les textes attendus ni un vocabulaire composé de ces phrases. Aucun remplacement manuel de mots n’est évalué comme une amélioration.

Les traitements stationnaires utilisent le début de la prise, entre 0,5 et 3 secondes, comme estimation de bruit sans parole, conformément au protocole. Cette hypothèse doit devenir une calibration contrôlée dans un moteur réel ; elle n’autorise pas à ignorer les premières secondes d’une écoute d’urgence.

RNNoise récent, Omnilingual et d’autres moteurs non installés ne sont pas considérés comme évalués. L’accès au catalogue public supplémentaire demandé a échoué HTTP 403. DF2 était incompatible avec le runtime local lors de l’étude précédente. Les modèles cloud, AEC avec référence de lecture, WPE et autres systèmes non exécutés ne figurent pas dans le classement. Il ne s’agit pas d’un tour exhaustif de tous les produits du marché.

## Résultats Qwen sur les candidats réellement proposés par le téléphone

Exactitude après normalisation de casse et ponctuation ; les différences de mots et d’accents restent des erreurs. Les textes et audio restent privés.

| Traitement | Calme / 6 | Aspirateur / 3 | Total / 9 | Textes négatifs autorisés / 5 |
| --- | ---: | ---: | ---: | ---: |
| Original, sur l’hôte | 6 | 1 | 7 | 0 |
| Gain ×2, ×4 ou ×8 | 6 | 1 | 7 | 0 |
| Wiener, facteur 1, plancher 0,5 | **6** | **3** | **9** | **0** |
| DF3 léger, max ×8, atténuation 9 dB | 6 | 2 | 8 | 0 |
| UL-UNAS mélangé à 50 % | 6 | 2 | 8 | 0 |
| GTCRN mélangé à 25 % | 6 | 2 | 8 | 0 |
| GTCRN intégral du téléphone | 4 | 0 | 4 | 1 |
| RNNoise v0.1 intégral | 6 | 0 | 6 | 0 |

D’autres filtres classiques atteignent 8/9. Les agrégats de toutes les configurations sont fournis sous `etude-tests-recents/results/asr-aggregate.json`. Un texte obtenu en forçant l’ASR sur un candidat négatif n’est pas compté comme une sortie autorisée : la confirmation vocale est analysée séparément. Par exemple, le Wiener gagnant produit encore des textes sur quatre des cinq négatifs si l’on force Qwen, mais FireRed les rejette tous.

### Différence avec le téléphone

La voie originale avait obtenu **8/9 sur téléphone**, et non 7/9. Le rejeu natif reproduit exactement les quatorze fenêtres audio proposées et leurs décisions originales d’admission. Cependant, Qwen sur l’hôte ne restitue pas systématiquement le même texte que Qwen Android dans le bruit. L’origine précise de cette différence n’est pas isolée ; backend et arithmétique peuvent différer. Les résultats de l’hôte ne doivent donc pas être présentés comme une mesure sur Android.

Le résultat 9/9 concerne des fenêtres produites par le vrai détecteur original, pas un découpage choisi en fonction de la phrase. Le traitement retenu conserve l’état spectral sur toute la prise et prélève les mêmes intervalles. La relecture sans filtre a vérifié bit à bit les quatorze candidats et l’identité des décisions d’admission avec le téléphone.

## Le placement du filtre change le résultat

Trois chaînes ont été rejouées avec le JNI FireRed réel et le segmenter de production, en respectant **les longueurs exactes des blocs du téléphone**, y compris les lectures partielles. Aucune fin n’est complétée artificiellement.

| Chaîne complète | Calme, exacts / 6 | Bruit, exacts / 3 | Sorties non vides dans la prise bruitée |
| --- | ---: | ---: | ---: |
| Original → détection/découpage → Qwen original | 6 | 1 | 2 |
| Wiener facteur 1/plancher 0,5 → détection/découpage → Qwen | 6 | 1 | 4 |
| Wiener facteur 0,5/plancher 0,75 → détection/découpage → Qwen | 6 | 2 | 4 |

Le filtrage placé avant le détecteur modifie les frontières et fait admettre une fenêtre supplémentaire en fin de prise bruitée, avec un texte parasite. Il perd ainsi l’avantage obtenu sur les intervalles d’origine.

La piste 9/9 est donc : **détection/découpage sur l’original ; filtre continu en parallèle ; extraction du même intervalle filtré ; confirmation et ASR sur ce segment**. La voie filtrée ne doit pas créer seule ses propres débuts de phrase. Une réouverture de candidats rejetés reste expérimentale : le test plus ancien au calme montre déjà qu’elle peut introduire une erreur.

## Autres reconnaisseurs

| Modèle sur les fenêtres originales | Calme, exacts / 6 | Bruit, exacts / 3 |
| --- | ---: | ---: |
| Qwen 0,6B int8, hôte | 6 | 1 |
| Parakeet TDT v3 0,6B int8 | 6 | 0 |
| Whisper Turbo int8, français imposé | 6 | 0 |
| Whisper Small int8, français imposé | 6 | 0 |
| Zipformer français 2025 | 5 | 0 |
| Zipformer français mobile 2023 | 6 | 0 |

Les variantes supplémentaires ne détrônent pas la combinaison Qwen/Wiener sur les trois dernières prises. Le modèle français 2025 récupère néanmoins entièrement la première phrase bruitée avec Wiener facteur 2/plancher 0,5 ; sa précision globale reste inférieure. La recherche par faisceau de largeur 4 n’améliore pas le résultat global par rapport au décodage glouton dans ce contrôle.

Les Zipformer français sont des références françaises, pas une proposition de modèle unique couvrant toutes les langues européennes. Les autres langues ne sont pas évaluées ici. Les temps enregistrés sur l’hôte, avec plusieurs jobs indépendants concurrents, ne constituent pas un benchmark de latence Android ni de batterie. Les poids et paramètres des modèles testés sont identifiés dans `results/models.json`.

## Contrôles de non-régression

Le filtre gagnant est appliqué sans retouche aux anciennes prises au calme :

| Traitement | Énoncés exacts / 15 | Mots courts exacts / 6 | Ancien candidat négatif autorisé |
| --- | ---: | ---: | ---: |
| Original | **15** | **6** | **0** |
| Wiener permanent | 13 | 6 | 1 |

Les deux erreurs concernent des phrases longues, dont une à 5 m. Les sept autres anciens candidats négatifs testés restent rejetés avec le Wiener. Il n’existe donc pas de preuve d’un taux de faux positifs nul : un contre-exemple est présent dans ce même corpus.

## Décision technique

1. **Conserver Qwen et la segmentation originale.** Un gain plus élevé ou un seuil abaissé ne résout pas le problème à lui seul.
2. Étudier Wiener léger comme **voie réservée au bruit**, sur les candidats originaux. Conserver l’original pour le contrôle et éviter son activation au calme. L’activation automatique doit être calibrée et validée, et non décidée à partir du texte attendu.
3. Ne pas mettre le Wiener en permanence devant FireRed. Le rejeu intégral montre la régression de découpage et le texte supplémentaire.
4. Valider d’abord les canaux réellement reçus par la v3.4.5. Les niveaux et spectres peuvent changer lorsque la source passe du mono traité à une autre route multicanale ; un réglage trouvé sur ces prises ne se transfère pas automatiquement.
5. Vérifier sur des enregistrements inédits, autres téléphones, autres voix et langues. Le résultat 9/9 est une piste technique mesurée, pas une qualification de production.

Un Wiener léger évite l’ajout d’un second modèle neural de débruitage, mais son coût et ses transitions doivent être mesurés sur Android. Aucune économie de batterie ou supériorité universelle n’est revendiquée. Les licences des poids, notamment des comparateurs français, doivent être examinées avant un usage commercial.

## Livrables

Scripts reproductibles et résultats agrégés sous `etude-tests-recents/`. Les manifestes, WAV, textes reconnus, noms d’enregistrements et empreintes vocales restent privés. Les tests du scoring passent ; le rejeu exact a été compilé puis exécuté contre le JNI réel. Aucun APK ni déploiement n’est effectué par cette étude.
