# Guardian Voice : comparaison du débruitage et des calibrations, 26 octobre 2026

## Décision

Conserver la référence v3.4.3 pour la prochaine comparaison téléphone. Aucun traitement testé ne résout à lui seul le bruit d’aspirateur sans contrepartie. Ne pas remplacer automatiquement GTCRN par DeepFilterNet, ni brancher MVDR avant FireRed en production sur la base de cette étude.

Deux pistes méritent un prototype comparatif : débruitage léger d’un segment déjà confirmé, et extraction spatiale avec validation indépendante de la présence de voix. La seconde a davantage de potentiel sur la prise stéréo, mais produit actuellement trop de transcriptions supplémentaires.

## Référence et portée

La référence est le **rejeu local du candidat v3.4.3**, pas l’ancien résultat affiché sur le téléphone : gain adaptatif de capture désactivé, canal maintenu pendant la parole dans le rejeu stéréo, gain de segment à 1. La correction de calibration du silence numérique est conservée. Cette étude ne change pas le moteur Android livré.

FireRed et Qwen sont exécutés localement. FireRed conserve ses paramètres existants, dont seuil 0,5, pré-roll de 2 s, fin de parole de 1,2 s et plafond de 8 s. Le rejeu utilise le JNI natif et les règles de segmentation existantes. Qwen 0,6B int8 reçoit du mono 16 kHz, sans amplification ASR supplémentaire, français, CPU 2 threads, mêmes limites de génération. Les empreintes des modèles et paramètres sont dans `etude-bruit-elargie/results/recognizer.json`.

Corpus : 15 énoncés au calme (dont six mots courts et trois phrases de l’ancienne prise à 5 m), neuf phrases sous aspirateur dans trois prises avec sources audio différentes, et 625,7 s sans parole (125,7 s récents et cinq témoins de 100 s plus anciens). Les prises sous aspirateur sont annoncées à 3 m ; leur environnement est distinct des prises au calme. Les témoins anciens peuvent avoir subi l’ancien gain de capture. Les langues autres que le français, d’autres téléphones et les bruits mobiles restent à valider.

**69 configurations exécutées avec succès, 714 décodages Qwen** : 414 décodages de calibration, 78 issus des flux complets, 124 de candidats réels traités par DeepFilterNet, 98 de contrôles complémentaires. Ces nombres décrivent des essais sur les mêmes prises, pas 714 exemples indépendants. Une configuration DF2 supplémentaire a échoué au chargement/exécution.

## Familles et calibrations réellement exécutées

| Famille | Variantes examinées |
| --- | --- |
| Capture/référence | Source mono existante, canal stéréo 0 ou 1, moyenne des canaux, sélection v3.4.3 maintenue |
| Filtrage classique | Passe-haut 150/300/450 Hz ; trois encoches stationnaires ; soustraction spectrale ; Wiener stationnaire et estimation dirigée par décision |
| Wiener | Sur-soustraction 0,5/1/2 ; plancher 0,25/0,5/0,75 ; lissage fréquentiel et temporel |
| SpeexDSP 1.2.1 | Suppression −6/−12/−24/−36 dB ; AGC désactivé ; variante avec normalisation de fenêtre |
| RNNoise v0.1 | Plein traitement et mélange 50 % avec l’original |
| GTCRN | Plein traitement et mélange 50 % avec l’original |
| DeepFilterNet3, CLI 0.5.6 | Modèle standard et faible latence ; limites d’atténuation de 6 à 100 dB ; normalisations plafonnées de ×1 à ×32 ; seuil interne par défaut et variante −40 dB |
| Multicanal MWF | Facteur de bruit 0,5/1/2 ; référence canal 0 ou 1 |
| Multicanal MVDR | Lissage de covariance 0,8 à 0,97 ; régularisation 0,003 à 0,1 ; référence 0/1 ; mélange original/traité |
| Placement dans la chaîne | Avant FireRed pour les traitements continus ; après découpage pour les traitements par fenêtre ; confirmation originale ou retraitée ; secours sur candidats rejetés |
| Contexte du segment | Fenêtres guidées de 8 s à deux positions ; candidats réels avec début original ou prolongé de 1 s vers le passé, longueur plafonnée à 8 s |

Le profil de bruit spectral/spatial utilise les premières 0,5–3 s, supposées sans parole par le protocole. Cela donne un avantage expérimental qu’une application démarrée pendant une conversation ne possède pas automatiquement. Le traitement STFT utilise un contexte de bloc ; la présente implémentation d’étude n’est pas un composant Android temps réel.

RNNoise v0.2 n’a **pas** été évalué : téléchargement officiel des poids refusé HTTP 403. DeepFilterNet2 n’a **pas** de score d’intelligibilité : opération reshape non prise en charge par ce runtime Tract. Ce ne sont pas des preuves de mauvaise qualité des modèles.

WebRTC NS, traitements matériels OEM, AEC avec référence de lecture, WPE/déréverbération, modèles multicanaux appris et modèles récents indisponibles dans cet environnement restent hors mesure. L’AEC répond surtout à l’écho d’un son joué par le téléphone ; ce n’est pas un remplacement général du débruitage d’aspirateur. Il ne s’agit donc pas d’un classement exhaustif de tous les systèmes du marché.

## Résultat avec le découpage réel

Critère : énoncé entièrement exact après normalisation de casse et ponctuation, dans l’ordre. Les accents et différences de mots restent comptés. Une transcription incorrecte est distincte d’une fausse alerte d’urgence : l’étude n’évalue pas les règles métier d’alerte.

| Chaîne/politique fixe | Calme, exacts / 15 | Aspirateur, exacts / 9 | Transcriptions non vides, calme / bruit | Sans parole : transcriptions |
| --- | ---: | ---: | ---: | ---: |
| Référence v3.4.3 rejouée | 15 | 1 | 15 / 4 | 0 |
| Wiener avant FireRed, sur-soustraction 2, plancher 0,5 | 14 | 3 | 16 / 8 | 0 |
| DF3 atténuation 9 dB, normalisation max ×8, sur les seuls segments déjà acceptés | 15 | 2 | 15 / 4 | 0 |
| DF3 même réglage, autorisation selon confirmation retraitée | 14 | 2 | 15 / 9 | 0 |
| DF3 18 dB, max ×16, autorisation selon confirmation retraitée | 13 | 1 | 14 / 9 | 0 |
| Wiener sur candidats d’origine, autorisation selon confirmation retraitée | 15 | 2 | 16 / 8 | 0 |

Les résultats agrégés de toutes les politiques sont conservés dans `etude-bruit-elargie/results/policies-aggregate.json`. Le « secours » garde le résultat original s’il était accepté ; sinon il autorise un candidat confirmé après traitement. Aucune branche n’est choisie en fonction du texte attendu.

La réouverture des candidats rejetés augmente les appels Qwen sans résoudre l’intelligibilité. Selon la variante, elle produit aussi une transcription supplémentaire dans une prise au calme. L’absence de transcription sur les 625,7 s de témoins ne permet donc pas de conclure à l’absence générale de faux positifs. Les textes obtenus en forçant Qwen sur des fenêtres rejetées ne sont jamais comptés comme sorties effectivement autorisées.

Le prolongement d’une seconde vers le passé n’apporte pas de gain d’exactitude dans le bruit pour ces deux calibrations DF3 ; certaines variantes perdent une réponse au calme. Il s’agit d’un test sur les candidats existants, pas d’un nouveau rejeu intégral du segmenter avec un pré-roll à 3 s. Aucun changement global du pré-roll ou de la durée de silence n’est justifié ici.

### Multicanal sur la prise stéréo complète

| Traitement avant FireRed | Phrases exactes / 3 | Transcriptions non vides |
| --- | ---: | ---: |
| Sélection v3.4.3 maintenue | 1 | 2 |
| Canal 0 | 1 | 3 |
| Canal 1 | 0 | 1 |
| MVDR, lissage 0,9, régularisation 0,01, référence 0 | 2 | 9 |
| MVDR, lissage 0,95, régularisation 0,03, référence 0 | 2 | 8 |
| MWF, facteur 1, référence 0 | 1 | 11 |

MVDR améliore deux phrases, mais autorise beaucoup plus de décodages, dont des sorties incorrectes/supplémentaires. Ces sorties ne sont pas toutes des voix inventées dans un silence annoté : certaines correspondent à de la parole mal reconnue ou répétée. Sans annotation temporelle manuelle, ne pas transformer ce nombre en taux de fausses détections vocales. Il manque aussi une longue prise stéréo indépendante sans parole pour valider cette branche.

## Pourquoi les fenêtres guidées étaient plus prometteuses

Sur la prise mono VOICE_RECOGNITION, DF3 18 dB avec normalisation max ×16 obtient 4 fenêtres exactes sur 6. Une des deux positions de découpage donne même les trois phrases exactes. L’autre position conserve des erreurs. Au total, une seule phrase est exacte aux deux positions. Sur les candidats réellement produits par le moteur, ce même réglage ne donne qu’une phrase exacte sur les neuf phrases bruitées des trois prises.

MVDR obtient 4/6 fenêtres exactes sur la stéréo et deux phrases exactes aux deux positions. La première phrase conserve des différences de mots. Les meilleurs résultats guidés ne suffisent donc pas à choisir un moteur de production. Ils montrent que de l’information vocale récupérable existe dans certaines portions, mais aussi que le contexte du débruiteur et la segmentation influencent fortement Qwen.

Les meilleurs Wiener, Speex, RNNoise et GTCRN de cette grille ne donnent pas trois phrases robustement exactes. Le classement complet, avec erreur en mots et exactitude aux deux découpages, est dans `etude-bruit-elargie/results/ranking.json`. Les résultats des prises mono et stéréo ne constituent pas une comparaison à entrée identique entre toutes les familles.

## Gain et calibration

Un gain scalaire multiplie voix et bruit et ne crée pas de rapport signal/bruit supplémentaire. Une décision VAD peut autoriser une normalisation du segment, mais ne sépare pas à elle seule les échantillons de voix des échantillons de bruit. Une amplification sur les seules composantes vocales exige une estimation de masque/séparation, avec risque de supprimer des syllabes faibles.

Dans le code de DeepFilterNet testé, une énergie moyenne inférieure à 1e−7 fait sortir un bloc nul sans faire avancer le traitement interne. Une normalisation constante **avant ce modèle**, puis son annulation en sortie, aide à éviter ce régime sur un signal faible. Cela explique l’intérêt de tester différents niveaux d’entrée ; cela ne justifie pas de monter le gain général de capture. Les variantes à traitement plus fort n’ont pas systématiquement mieux reconnu la parole.

Le réglage le moins régressif de cette série est DF3 limité à 9 dB sur les seuls segments déjà confirmés, normalisation max ×8 : calme 15/15 conservé, bruit 2/9 contre 1/9. Le progrès est trop petit, sur un corpus trop limité, pour en faire le chemin principal. La normalisation est bornée par la crête à 0,8 ; elle n’est pas une amplification permanente du microphone.

## Suite technique recommandée

1. Garder les corrections de capture v3.4.3 et comparer sur téléphone à source audio identique : la qualité de capture précède le choix du débruiteur. Ne pas sélectionner le canal sur son seul volume ; une machine peut être plus forte qu’une voix.
2. Prototyper une branche spatiale en diagnostic, avec estimation de qualité vocale et maintien du canal. L’admission FireRed doit être validée séparément : les neuf sorties du MVDR montrent qu’un gain d’intelligibilité peut dégrader le comportement global.
3. Comparer un traitement neural à état continu sur toute la prise, avec fenêtres identiques à l’original pour mesurer l’effet du contexte. Le DF3 évalué ici traite chaque fenêtre depuis un état neuf ; son résultat n’est pas une mesure d’un DF3 continu Android.
4. Conserver l’original pour le contrôle et tester DF3 léger uniquement sur segments confirmés, sans réouvrir par défaut les candidats rejetés. Mesurer exactitude, mots courts, faux textes, latence, RAM et batterie avant toute activation générale.
5. Valider sur des prises inédites et au moins un autre téléphone ; ensuite les langues européennes visées. Multiplier les réglages sur les mêmes trois phrases augmente le risque de surajustement.

Les bibliothèques locales examinées évitent un coût de reconnaissance cloud par minute. DeepFilterNet est sous double licence MIT/Apache-2.0 ; RNNoise et SpeexDSP utilisent des licences permissives de type BSD. Vérifier séparément les licences des poids et obligations de redistribution des dépendances retenues. Le coût matériel Android reste à mesurer. Les 124 fenêtres DF3 ont pris environ 97,7 s de préparation sur l’hôte pour 636,2 s d’audio cumulé, avec création d’un processus par fenêtre ; ce n’est pas une mesure de consommation ou de latence du téléphone.

## Vérification et livrables

Trois tests numériques reproductibles passent : reconstruction STFT/iSTFT, calculs finis sur silence et canaux identiques, scoring respectant l’ordre. Contrôles supplémentaires exécutés sur l’hôte : délai Speex 20 ms, délai RNNoise 10 ms et silence, bypass DF3 à atténuation nulle sans compensation de délai. Le bypass DF3 n’a pas le délai du modèle actif. Les sorties traitées sont vérifiées de longueur attendue et finies.

Versions natives : RNNoise v0.1 `cdf196b1e9de2f8ff1003328ebf9a4316477429d` ; SpeexDSP 1.2.1 `1b28a0f61bc31162979e1f26f3981fc3637095c8` ; sherpa-onnx 1.13.8 ; DeepFilter CLI 0.5.6. Les paramètres de la grille sont fournis avec les résultats. Aucun WAV, transcription, identifiant d’enregistrement ou chemin privé n’est publié.
