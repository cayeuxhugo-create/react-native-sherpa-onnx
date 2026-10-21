# Essais complémentaires : viser huit rejets sans perdre la parole distante

## Conclusion

**Le résultat 8/8 rejets, 12/12 phrases admises et 15/15 mots courts synthétiques admis a été obtenu sur les fichiers d'origine. Mais le candidat perd de la parole à 5 mètres lorsqu'on perturbe légèrement les entrées. Il n'est donc pas validé comme filtre bloquant.**

Le meilleur résultat sur les fichiers connus ne suffit pas à choisir une correction pour un moteur d'urgence. L'étude conserve les résultats défavorables, sans changer les règles après les vérifications pour ne montrer que le 8/8.

Aucun changement du microphone, de Qwen, du module Android ou des règles d'alerte n'a été effectué. Les tests portent sur l'admission acoustique de fenêtres complètes, pas sur la transcription correcte ou les alertes.

## Corpus

Même corpus de départ que la première simulation : huit fenêtres suspectes issues des tests de silence/bruit, douze fenêtres de vraies phrases dont six à 5 mètres, et quinze mots courts synthétiques à trois niveaux. Les mots courts sont évalués pour leur admission, pas pour leur bonne reconnaissance par Qwen.

Les fenêtres sont originales et restent inchangées. Les renforcements de gain et les filtres s'appliquent uniquement aux copies utilisées par les vérificateurs. Le corpus reste petit, principalement une phrase répétée par une personne sur un téléphone, sans jeu réel indépendant tenu à l'écart.

## Vérifications supplémentaires exécutées

Soixante-deux configurations supplémentaires ont été exécutées :

- Douze confirmations Silero avec seuils 0,25 / 0,5 / 0,75, durées minimales 128 / 192 ms et gains maximum 16 / 64.
- Dix-huit confirmations TEN VAD avec les mêmes seuils et durées, gains maximum 4 / 16 / 64.
- Vingt-quatre confirmations WebRTC avec des bandes 80–4 000 Hz, 150–4 000 Hz et 300–3 400 Hz, modes 1 / 2, gains maximum 4 / 16 et supports 180 / 300 ms.
- Huit confirmations Silero plus sensibles : seuils 0,1 / 0,15, durées 96 / 128 ms et gains maximum 64 / 256.

Aucun de ces réglages simples ne fournit à lui seul les huit rejets tout en conservant les douze phrases et les quinze mots synthétiques. Les filtres de bande n'améliorent pas le compromis du candidat WebRTC initial sur l'ensemble des critères.

## Combinaison donnant 8/8 sur les fichiers d'origine

La règle testée est :

```text
admission = WebRTC ET (Silero OU TEN)
```

Configurations fixes :

- WebRTC : mode 1, gain interne maximum 4, activité positive cumulée d'au moins 180 ms et séquence positive d'au moins 60 ms.
- Silero sur l'original : seuil 0,5, durée minimale de parole 128 ms, gain interne maximum 16, support positif d'au moins 100 ms.
- TEN sur l'original : seuil 0,5, durée minimale de parole 128 ms, gain interne maximum 64, support positif d'au moins 100 ms.

Les gains sont bornés par le pic ; ils ne modifient pas la capture ni le PCM envoyé à Qwen. Chaque vérificateur recommence avec un état vierge sur une fenêtre déjà complète. Ce protocole ne valide pas l'utilisation des mêmes états en continu entre toutes les fenêtres.

| Sur les fichiers d'origine | WebRTC initial | Combinaison fixe |
|---|---:|---:|
| Fenêtres suspectes rejetées | 6/8 | **8/8** |
| Fenêtres de phrases admises | 12/12 | **12/12** |
| Mots courts synthétiques admis | 15/15 | **15/15** |

Les trois modèles n'ont pas les mêmes erreurs : TEN récupère une phrase faible rejetée par Silero ; WebRTC élimine des bruits encore admis par les modèles neuronaux. La combinaison n'utilise ni identifiant de fichier, ni distance, ni mot reconnu pour prendre sa décision. Elle a néanmoins été choisie après observation de ce petit corpus : son succès n'est pas une validation indépendante.

TEN n'est pas redistribué. Sa licence comporte des conditions supplémentaires qui doivent être examinées avant une éventuelle intégration dans un produit. Il reste ici un outil de recherche locale.

## Vérifications de sensibilité avec règle fixe

Neuf combinaisons ont été exécutées : amplitude -6 / 0 / +6 dB et décalage de cadrage 0 / 10 / 20 ms. La règle précédente n'est pas modifiée entre ces vérifications.

| Condition | Suspects rejetés | Phrases admises | Mots synthétiques admis |
|---|---:|---:|---:|
| Origine, sans décalage | 8/8 | 12/12 | 15/15 |
| Niveau -6 dB, selon cadrage | 8/8 | **8 à 9/12** | 15/15 |
| Niveau d'origine, décalage 10 ou 20 ms | 8/8 | **11/12** | 15/15 |
| Niveau +6 dB, selon cadrage | **7 à 8/8** | 12/12 | 15/15 |

Les phrases perdues à -6 dB sont à 5 mètres. Une petite variation du cadrage suffit aussi à faire perdre une phrase à niveau inchangé. La combinaison ne peut donc pas être activée pour bloquer des urgences sur la seule base du résultat initial.

## Contrôles supplémentaires avec bruit

Un générateur déterministe (graine 20261021) produit 57 nouveaux contrôles :

- Dix-huit bruits synthétiques : blanc, rose, vent simulé, ventilateur simulé, bips et impacts ; trois niveaux chacun, huit secondes par contrôle.
- Vingt-quatre copies de phrases réelles avec bruit rose ajouté : rapports signal/bruit globaux de 20 et 10 dB.
- Quinze copies de mots synthétiques avec bruit rose ajouté à 10 dB.

Résultat de la combinaison fixe :

| Contrôles | Résultat |
|---|---:|
| Bruits synthétiques rejetés | 17/18 |
| Phrases avec bruit ajouté admises | 21/24 |
| Mots synthétiques avec bruit ajouté admis | 15/15 |

Un bip très faible est encore admis. Trois copies de phrases à 5 mètres sont rejetées. Ces mélanges ne sont pas de nouvelles prises de son réelles ; l'admission d'une phrase mélangée ne garantit pas non plus que Qwen la comprend correctement.

## Adaptation expérimentale du niveau des copies neuronales

Pour essayer de réduire la sensibilité au niveau sonore, trois cibles RMS ont été testées sur les copies des modèles neuronaux : -40, -30 et -20 dBFS, avec gain maximum 512 et pic borné à 0,8. WebRTC conserve l'original. Le PCM de Qwen reste intact.

Les neuf variations d'amplitude et de cadrage ont été exécutées pour chaque cible, soit 27 vérifications. Aucune cible ne conserve simultanément huit rejets, douze phrases et quinze mots synthétiques dans toutes les variations. Cette adaptation n'est pas retenue comme correction.

## Ce que nous retenons

Le 8/8 est atteignable sur les exemples d'origine, mais pas encore avec la robustesse requise par les essais supplémentaires. La simulation WebRTC seule conserve mieux la sensibilité dans ces variations, au prix de deux fenêtres suspectes résiduelles. Il serait prématuré de remplacer ce compromis par une combinaison plus stricte au risque de perdre les urgences faibles.

Un prototype en observation peut comparer ces confirmations sur le téléphone en conservant les décisions et les entrées exactes, sans bloquer les segments tant que les désaccords ne sont pas résolus. Les mots d'urgence doivent aussi être testés avec des voix humaines réelles : les quinze contrôles synthétiques ne valident pas cette fonction.

## Reproduction et vérification

Scripts sous `simulation-admission-extra/` :

- `evaluate_neural.py` : grilles neuronales, option `--sensitive` pour les huit variantes Silero supplémentaires.
- `evaluate_bands.py` : filtres de bande sur les copies de confirmation.
- `evaluate_fusion.py` : règle fixe, option `--stress` pour les neuf perturbations.
- `evaluate_normalized.py` : trois cibles RMS et neuf perturbations chacune.
- `prepare_controls.py` : génération des 57 contrôles supplémentaires, sorties privées.
- `results-summary.json` : résultats agrégés sans voix ni identifiant de session.

Dépendances : numpy/scipy, Sherpa ONNX 1.13.8, webrtcvad-wheels 2.0.14.post1, modèles Silero et TEN fournis séparément. Le schéma du manifeste privé est celui décrit dans `SIMULATION-ADMISSION-20261021.md`.

Les décisions de la combinaison sans perturbation ont été comparées aux trois évaluations individuelles : elles concordent pour les 35 fenêtres. Les 57 contrôles ont été régénérés par le reproducteur public et comparés octet par octet. Les empreintes des 35 entrées initiales restent inchangées.

Aucun fichier audio, chemin privé ou identifiant de session utilisateur n'est publié. Les sorties détaillées et les manifestes contenant les chemins locaux restent privés. Les scripts refusent les sorties déjà existantes. Aucun nouvel APK n'est fourni par cette étude.
