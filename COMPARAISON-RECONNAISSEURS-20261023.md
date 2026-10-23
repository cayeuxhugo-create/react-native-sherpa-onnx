# Qwen, Parakeet v3 et Whisper sur les enregistrements Guardian

Étude du 23 octobre 2026, exécutée localement, sans service de transcription,
sans changement du téléphone ni du module Android livré. Tous les poids sont
téléchargés depuis les publications publiques Sherpa ; aucun WAV utilisateur
n'est envoyé à un fournisseur ni publié avec cette étude.

## Protocole

Quatre variantes réellement exécutées : Qwen3-ASR 0.6B avec notre export INT8,
Parakeet TDT 0.6B v3 INT8, Whisper small INT8, Whisper turbo INT8. Runtime commun
Sherpa-ONNX 1.13.8, CPU deux threads, recherche gloutonne, un modèle par processus.
Whisper est testé ici via Sherpa/ONNX, **pas via whisper.cpp**. Les différences
entre exports et runtimes font partie des limites de cette comparaison.

Deux jeux de frontières sur les mêmes enregistrements :

- **Fenêtres fixes** de l'étude des débruiteurs : 12 phrases enregistrées (trois
  à 1 m, trois à 3 m, six à 5 m), 15 mots courts synthétiques et huit fenêtres
  connues sans voix.
- **Sorties FireRed** conservées de l'étude des flux complets : 12 fenêtres de
  phrases et 15 de mots synthétiques, admises par FireRed streaming + confirmation
  non-streaming, queue de 1,2 s. Le VAD n'est pas recalculé : seul le reconnaisseur
  est substitué sur les fenêtres déjà produites. Aucune fenêtre négative n'avait
  été admise par cette chaîne dans les 500 s précédemment étudiées.

Chaque modèle traite les mêmes 62 fenêtres, d'abord sans gain puis avec un gain
max 64 borné par la crête à 0,8, sans débruitage : **496 décodages de comparaison**,
tous nouvellement exécutés, sans réutilisation des anciens textes en cache.
Les fenêtres se recouvrent entre les deux jeux : ce ne sont pas 496 observations
indépendantes ou de nouveaux locuteurs. Un warmup commun de deux secondes de
zéros par processus est exclu des scores.

Qwen utilise le paramètre de langue French, Whisper fr/transcribe, Parakeet
identifie lui-même la langue : les interfaces ne permettent pas un forçage
équivalent. Ce n'est donc pas une évaluation d'identification multilingue en
aveugle. Qwen : 512 tokens totaux, 256 nouveaux ; Whisper : padding interne
Sherpa par défaut ; pas de hotwords ni de modèle de langage ajouté.

Le gain est identique pour tous les modèles, calculé sur la crête de la fenêtre
complète ; c'est un contrôle hors ligne, pas encore un AGC causal sur téléphone.
Il ne change pas le rapport signal/bruit. Les fenêtres de phrases fixes ont des
niveaux RMS approximatifs entre -70 et -54 dBFS : ce contexte de voix très faible
est important pour interpréter les résultats.

## Résultats

| Modèle | Gain max | Phrases fixes /12 | Phrases FireRed /12 | Dont FireRed 5 m /6 | Mots synthétiques FireRed /15 | Textes sur les 8 négatifs forcés |
|---|---:|---:|---:|---:|---:|---:|
| Qwen3-ASR 0.6B | 1 | 11 | 12 | 6 | 6 | 8 |
| Parakeet TDT 0.6B v3 | 1 | 3 | 3 | 0 | 1 | 0 |
| Whisper small | 1 | 10 | 8 | 2 | 6 | 7 |
| Whisper turbo | 1 | 11 | 11 | 5 | 6 | 0 |
| Qwen3-ASR 0.6B | 64 | 12 | 12 | 6 | 6 | 7 |
| Parakeet TDT 0.6B v3 | 64 | 8 | 8 | 2 | 2 | 0 |
| Whisper small | 64 | 9 | 9 | 3 | 8 | 3 |
| Whisper turbo | 64 | 11 | 11 | 5 | 6 | 0 |

Mesures hôte avec préparation du niveau max 64, même corpus FireRed ; deux threads CPU.

| Modèle | Poids utilisés (Mio) | Pic RSS du processus (Mio) | Temps médian par phrase, préparation + décodage (s) |
|---|---:|---:|---:|
| Qwen3-ASR 0.6B | 941 | 1835 | 1.527 |
| Parakeet TDT 0.6B v3 | 639 | 1051 | 0.575 |
| Whisper small | 358 | 975 | 1.515 |
| Whisper turbo | 989 | 1635 | 3.457 |

Les phrases et mots sont évalués par égalité du texte après normalisation de la
casse, des accents et de la ponctuation. Les erreurs de mots et les détails par
niveau synthétique figurent dans les [agrégats](etude-reconnaisseurs/resultats-agreges.json).
Les quinze mots sont cinq expressions générées par une seule voix TTS à trois
niveaux : ils ne valident pas la détection de vraies urgences courtes à 5 m.

Les huit négatifs sont volontairement envoyés aux reconnaisseurs sans VAD pour
isoler leur comportement sur du bruit. Un texte produit n'est **ni une alerte
Guardian ni un appel qui aurait nécessairement eu lieu après FireRed**. Zéro
texte sur huit fenêtres sélectionnées ne prouve pas l'absence d'hallucinations.

## Interprétation

**Pour nos phrases françaises distantes, conserver FireRed + Qwen comme référence.**
Qwen est le seul des quatre à atteindre 12/12 sur les sorties FireRed, dont 6/6
à 5 m, sans amplifier son entrée. Parakeet v3 ne justifie pas de remplacement :
la préparation du niveau le fait passer de 3/12 à 8/12, mais il reste à 2/6 à 5 m
et ses mots synthétiques restent faibles. Sa détection automatique de langue
peut le désavantager sur les mots isolés face aux deux modèles conditionnés en français.

Whisper turbo est le challenger le plus proche sur les phrases, à 11/12 et 5/6
à 5 m ; il ne gagne pas sur les mots FireRed et demande ici davantage de temps
de calcul. Il produit cependant zéro texte sur les huit négatifs forcés :
c'est un résultat intéressant de rejet, distinct de la précision des phrases.
Ne pas présenter Qwen comme intrinsèquement plus sûr sur l'absence de voix : il
produit du texte sur 8/8 négatifs sans gain et 7/8 avec gain lorsqu'on le force.
La confirmation avant Qwen reste donc essentielle dans cette architecture.

Whisper small est beaucoup plus compact et monte à 8/15 mots synthétiques avec
préparation du niveau, mais n'obtient alors que 9/12 phrases et 3/6 à 5 m.
Ce compromis ne justifie pas de le choisir seul pour remplacer Qwen.

**Un contrôle de niveau mérite davantage d'attention qu'un débruitage systématique.**
Sur les anciennes fenêtres, Qwen passe de 11/12 à 12/12 phrases et de 6/15 à 7/15
mots avec le gain borné ; sur les sorties FireRed il reste à 12/12 et 6/15.
Il n'y a donc pas de gain démontré sur la chaîne FireRed déjà retenue. Tester un
AGC causal sur d'autres prises de son serait nécessaire avant tout changement.
Le choix max 64 est un contrôle exploratoire commun, pas un seuil de production
optimisé ni une preuve qu'amplifier davantage améliore la voix à distance.

Le découpage influence aussi la comparaison : un modèle peut gagner sur les
anciennes fenêtres et perdre sur celles de FireRed. Les 12/12 antérieurs de
Qwen concernent les frontières FireRed ; les 11/12 des fenêtres fixes ne sont
pas une régression du code. Les 35 textes de la passe Qwen brute sur fenêtres
fixes reproduisent exactement ceux de l'étude précédente, sans cache.

## Coût, couverture et limites

Le pic RSS et les temps ci-dessus mesurent des processus de recherche sur un
AMD EPYC, avec chargement et warmup exclus du temps par phrase. Ils ne sont pas
des mesures de batterie, de délai d'alerte complet ou de RAM d'application Android.
Le temps par phrase des passes gain 64 inclut création du flux, acceptation de
l'audio et décodage, hors lecture disque. Le temps de décodage seul est aussi
conservé dans les agrégats ; ne pas mélanger ces deux périmètres.

Les variantes INT8 testées ne représentent pas tous les checkpoints de ces
familles, ni leurs performances GPU/NPU ou whisper.cpp. Le coût commercial et
les conditions de redistribution des poids/exportations doivent être validés
pour la version livrée ; aucun abonnement ou service payant n'est utilisé ici.

**Tous ces tests sont français.** Ils ne départagent pas la précision dans toutes
les langues européennes. L'audit précédent reste valable : Qwen ne déclare que
15 des 24 langues officielles de l'UE ; Whisper possède des identifiants pour 23,
sans l'irlandais ; Parakeet v3 annonce 25 langues européennes, pas toutes les
langues d'Europe. Une langue listée n'est pas une garantie de qualité.

Manquent encore des vrais mots d'urgence, de vraies voix douces parlant dans le
bruit, des locuteurs et téléphones supplémentaires et des mesures embarquées.
Les fenêtres suspectes connues sans voix ne constituent pas un échantillon
aléatoire de longues périodes négatives. Aucun choix universel de production
n'est déduit de ce petit corpus.

## Reproduction et confidentialité

[Scripts et paramètres](etude-reconnaisseurs/README.md),
[archives et provenance](etude-reconnaisseurs/provenance.json),
[contrôles](etude-reconnaisseurs/validation.json).
Les agrégats contiennent les empreintes des modèles publics, jamais celles des
voix. Les manifests, WAV et textes détaillés restent privés dans l'espace de travail.
La branche de recherche ne modifie ni `main` ni le moteur Android.
