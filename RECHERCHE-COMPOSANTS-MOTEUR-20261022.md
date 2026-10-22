# Choix des composants du moteur Guardian — 22 octobre 2026

## Décision issue de la recherche documentaire

**FireRed + Qwen reste notre référence mesurée, pas un vainqueur universel.**
Les alternatives prioritaires à comparer sont **FireRed + Parakeet TDT 0.6B v3**
et **FireRed + Whisper multilingue**. Pour les langues européennes moins couvertes,
**Omnilingual ASR CTC 300M v2** mérite une étude de faisabilité mobile.
Aucun nouveau benchmark acoustique de ces reconnaisseurs n'a été exécuté ici.

La contrainte « toutes les langues européennes » change le choix : Qwen3-ASR
seul ne la satisfait pas dans sa couverture déclarée. Il faut une matrice de
langues, pas uniquement choisir le meilleur score français. Les 24 langues
officielles de l'UE ne représentent pas toutes les langues d'Europe.

Cette recherche utilise les sources officielles accessibles, sans moteur de
recherche général disponible. [Sources et empreintes](recherche-composants/sources.json).
Les déclarations des auteurs ne sont pas des résultats Guardian. Aucune voix
utilisateur n'est envoyée à un fournisseur ; aucun moteur payant n'est proposé.

## 1. Détection de parole et découpage

| Candidat | Spécialité documentée | Notre décision |
|---|---|---|
| [FireRedVAD](https://github.com/FireRedTeam/FireRedVAD) streaming + non-streaming | Détection multilingue ; 97,57 % F1 non-streaming sur FLEURS-VAD-102 selon ses auteurs ; runtime NCNN | Référence actuelle : meilleurs résultats de phrases sur nos replays. La confirmation et le découpage doivent être évalués ensemble |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Petit modèle, ONNX/JIT, entraînement annoncé sur plus de 6 000 langues ; licence MIT | Comparateur de robustesse et de coût ; sa présence dans la chaîne précédente a mieux préservé certains mots synthétiques, sans gagner sur les phrases distantes |
| [TEN VAD](https://github.com/TEN-framework/ten-vad) | C/Java Android, pas de 10/16 ms, faible latence et coût annoncé | Comparateur de latence, pas de raison démontrée de le remettre à la place de FireRed dans notre chaîne |

Le tableau FLEURS de FireRed compare les sorties VAD, pas la transcription à 5 m
ni les alertes d'urgence. Un VAD multilingue ne reconnaît pas les mots. Aucun des
trois n'est déclaré ici meilleur dans toutes les situations.

## 2. Réduction du bruit, voix distante et capture

| Candidat | Spécialité | Limite et priorité |
|---|---|---|
| [GTCRN](https://github.com/Xiaobin-Rong/gtcrn) | Très faible coût : 48,2 k paramètres, 33 MMAC/s selon le dépôt ; intégration Sherpa existante | Notre référence légère ; conserver la comparaison avec l'audio original |
| [UL-UNAS](https://github.com/Xiaobin-Rong/ul-unas) | Recherche d'architecture ultra légère, checkpoints et inférence ONNX streaming annoncés en 2026 | Premier challenger de GTCRN à faible coût ; pas encore testé sur nos WAV ; licence du checkpoint/portage à vérifier avant livraison |
| [DeepFilterNet 2/3](https://github.com/Rikorose/DeepFilterNet) | Amélioration de parole temps réel pleine bande à 48 kHz ; bibliothèque Rust | Challenger pour le bruit ; capture/rééchantillonnage et coût Android à mesurer. Amélioration perceptuelle différente d'un gain de reconnaissance |
| [RNNoise](https://github.com/xiph/rnnoise) | Baseline C légère et éprouvée d'atténuation du bruit | Contrôle simple de coût/qualité, sans raison de supposer qu'il surpasse GTCRN |
| [H-GTCRN](https://github.com/Max1Wz/H-GTCRN) | Deux canaux, WPE + AuxIVA + GTCRN ; variantes masque sur mélange original ou sur IVA | Piste conditionnelle de bruit/réverbération, seulement si Android fournit deux vrais canaux exploitables ; pas un remplacement mono universel |

À 5 m, la réverbération et la capture peuvent être plus déterminantes que le
débruitage. Une copie mono dupliquée en stéréo ne permet pas de séparation spatiale.
Le système actuel sélectionne déjà des configurations de capture : mesurer les
sources et routes réelles reste nécessaire pour chaque téléphone.

Un gain numérique amplifie aussi le bruit ; il ne crée pas un meilleur rapport
signal/bruit. Un débruiteur peut supprimer les consonnes faibles. Il faut donc
mesurer la reconnaissance sur **original et copie traitée**, sans remplacer
d'office l'original envoyé à Qwen. Ni gain plus élevé ni empilement de filtres
n'est une amélioration prouvée.

## 3. Reconnaissance multilingue locale

| Modèle / variante exacte | Spécialité et langues documentées | Position pour Guardian |
|---|---|---|
| [Qwen3-ASR 0.6B](https://github.com/QwenLM/Qwen3-ASR) | Compromis taille/précision ; 30 langues et 22 dialectes chinois ; notre export INT8 existe | Référence mesurée pour les phrases distantes ; ne couvre pas toute l'Europe |
| Qwen3-ASR 1.7B | Plus grosse variante, scores supérieurs annoncés par les auteurs | Comparateur de précision pour isoler la limite du 0.6B ; mêmes langues, consommation mobile et export à établir |
| [Parakeet TDT 0.6B v3](https://github.com/NVIDIA/NeMo) | Reconnaissance de 25 langues européennes annoncée par NVIDIA ; TDT intéressant pour un décodage rapide | Challenger prioritaire à Qwen pour coût/précision. Pas de preuve de supériorité sur notre voix à 5 m ; vérifier la licence des poids et le runtime/export exacts |
| Canary 1B v2 | Reconnaissance et traduction sur 25 langues européennes selon NVIDIA | Autre comparateur de précision multilingue ; plus lourd, sans garantie mobile. Ne pas confondre avec Canary-Qwen 2.5B présenté sur un benchmark anglais |
| [Canary 180M Flash INT8](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/nemo/canary.rst) | Export Sherpa documenté, encodeur ~127 Mio et décodeur ~71 Mio ; EN/DE/FR/ES | Contrôle mobile compact pour ces quatre langues, insuffisant seul pour notre couverture européenne |
| [Whisper small / medium / large-v3 / turbo](https://github.com/openai/whisper), [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Large couverture linguistique, licence MIT code/poids ; Android et quantification documentés dans whisper.cpp | Comparateur indispensable de couverture ; small comme contrôle compact, turbo comme compromis, large-v3 comme référence lourde. Ordre de précision sur nos audios inconnu ; vérifier aussi les inventions sans parole |
| [Omnilingual ASR CTC 300M v2](https://github.com/facebookresearch/omnilingual-asr) | Plus de 1 600 langues ; variante CTC plus légère que les LLM de la même famille | Meilleure piste de ce relevé pour élargir la couverture linguistique ; pas de runtime Android validé ici, pas un remplacement prêt à installer |
| Omnilingual CTC 1B/3B/7B v2 ; LLM v2 / Unlimited v2 | Famille orientée couverture très large et précision ; variantes de tailles différentes | Références de recherche lourdes. Attention : « LLM 300M » totalise ~1,63 milliard de paramètres dans le tableau officiel, ce n'est pas le CTC 300M |
| [Moonshine Voice](https://github.com/moonshine-ai/moonshine) | Reconnaissance embarquée/streaming, Android annoncé ; modèles récents MIT selon README | Piste de latence à suivre ; liste exacte des langues et checkpoint français non vérifiés ici, donc pas de recommandation comme moteur européen complet |

Parakeet **v2**, certains Parakeet streaming/unified et Canary-Qwen peuvent être
anglais seulement : le nom de famille ne garantit pas les langues de **v3**.
Les scores de leaderboards anglais ne permettent pas de classer ces modèles sur
les urgences multilingues. Les tailles VRAM NVIDIA publiées pour Whisper ou
Omnilingual ne sont pas des mesures de RAM Android avec quantification.

## 4. Vérification concrète de la couverture

La table officielle Qwen ne comprend notamment pas le bulgare, le croate,
l'estonien, l'irlandais, le letton, le lituanien, le maltais, le slovaque et le
slovène : **15/24 langues officielles UE déclarées**, pour les deux tailles Qwen.

Le tokenizer Whisper contient des identifiants pour **23/24** de ces langues,
sans l'irlandais. La présence d'un token indique une possibilité de conditionnement,
pas un niveau de précision. La version du checkpoint doit aussi être fixée.

La liste Omnilingual comprend ces langues, avec `ekk_Latn` pour l'estonien standard,
ainsi que, par exemple, `gle_Latn`, `bre_Latn`, `eus_Latn`, `cat_Latn`, `ltz_Latn`
et `fry_Latn`. Cela en fait un candidat de couverture, pas une garantie de qualité
pour chaque langue régionale ou variante. [Audit UE24](recherche-composants/couverture-ue24.json).

## 5. Combinaisons à départager

1. **Référence : FireRed streaming → confirmation FireRed → Qwen 0.6B INT8.**
2. **Challenger calcul/précision : même découpage → Parakeet TDT 0.6B v3.**
3. **Challenger couverture : même découpage → Whisper multilingue quantifié.**
4. **Étude de couverture étendue : même découpage → Omnilingual CTC 300M v2.**

Tester d'abord les reconnaisseurs sur les mêmes segments correctement délimités,
puis sur les flux complets. Tester séparément original/GTCRN/UL-UNAS/DeepFilterNet,
sans changer à la fois débruitage, découpage et reconnaisseur : sinon la cause
d'une amélioration ou d'une régression restera inconnue.

Les critères de décision sont rappel des mots d'urgence et des mots courts,
reconnaissance des phrases par langue/distance/bruit, faux textes et faux appels
sur absence de parole, délai total, mémoire et batterie sur téléphone. Les
résultats actuels de phrases françaises ne suffisent pas à fixer ces choix.

**Aucun composant n'est remplacé sur cette seule recherche.** La prochaine
comparaison prioritaire est Parakeet v3 contre Qwen, puis Whisper pour les langues
manquantes ; Omnilingual nécessite d'abord de vérifier son déploiement mobile.
