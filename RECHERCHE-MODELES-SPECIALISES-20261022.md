# Détecteurs spécialisés pour Guardian Voice — 22 octobre 2026

L'architecture retenue est conservée dans [le mémo du projet](MEMO-ARCHITECTURE-GUARDIAN.md).
La recherche porte sur une voie indépendante de détection de mots/phrases d'urgence,
complémentaire à FireRed → confirmation FireRed → Qwen local. Aucun changement du
module livré ni du téléphone n'est effectué dans cette étude.

## Conclusion de travail

Il n'y a pas, dans les sources vérifiées ici, de modèle ouvert prêt à installer
qui démontre déjà la détection fiable des urgences françaises à 5 m sur téléphone.
Les trois voies prioritaires sont : **Porcupine personnalisé pour un premier
comparatif Android**, **microWakeWord personnalisé pour un modèle compact maîtrisé**,
et **Sherpa KWS français pour vérifier une voie native compatible avec notre outillage**.
Ce sont des priorités d'expérimentation, pas un classement de précision mesurée.
Rhino mérite un comparatif séparé si l'on veut reconnaître des intentions bornées.

La recherche couvre les familles ci-dessous et leurs versions publiées repérées.
L'inventaire des tags est joint ; tous les anciens binaires et tous les poids
n'ont pas été exécutés. Une version du SDK, une version de poids et une version
de manifeste ne sont pas interchangeables.

## Catalogue vérifié

| Famille | Versions / variantes repérées | Français et intérêt pour Guardian | Limite concrète / état de l'essai |
|---|---|---|---|
| [Porcupine](https://github.com/Picovoice/porcupine) | Générations 1.x, 2.0–2.2, 3, 4 ; Python 4.0.3, Android Maven 4.0.2 | Français, Android/React Native ; mots-clés personnalisés `.ppn` via Console | AccessKey et conditions des binaires/modèles à vérifier ; activation en zone blanche non validée. Aucun benchmark sans modèle personnalisé |
| [Rhino](https://github.com/Picovoice/rhino) | 1.x, 2.0–2.2, 3, 4, 4.1 ; Python/Android 4.1.0 | Français ; intentions et grammaire de domaine dans un contexte `.rhn` | AccessKey ; une intention de grammaire n'est pas une compréhension générale du danger. Non testé |
| [Sherpa KWS](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) | API 1.13.8 ; GigaSpeech 3.3M 2024-01-01 mobile/non mobile ; WenetSpeech équivalents ; zh-en 3M 2025-12-20 | Moteur transducer streaming ; adaptation des exports français 2023 mobile INT8 et Kroko 2025-08-06 examinée | Poids KWS dédiés du catalogue : anglais/chinois, pas français. Replays français réellement exécutés, résultats ci-dessous |
| [microWakeWord](https://github.com/kahrendt/microWakeWord) | MixConv streaming ; modèles ESPHome manifestes v1/v2 ; Alexa, Hey Jarvis, Okay Nabu ; v2 ajoute Hey Mycroft et VAD ; publication `stop`, `stop-20241016.2`, `okay_nabu_20241226.3` | Petite architecture personnalisable, export TFLite/INT8 ; bon candidat d'entraînement dédié | Manifestes consultés : `trained_languages: en`. « stop » n'est pas une validation française. Code/poids du dépôt ESPHome Apache-2.0 ; prétraitement et port Android à reproduire. Non exécuté |
| [openWakeWord](https://github.com/dscripka/openWakeWord) | 0.1.1, 0.2, 0.3/0.3.1, 0.4, 0.5/0.5.1, 0.6.0 ; poids inclus v0.1, backbone téléchargé depuis 0.5.1 | Embeddings + classifieur personnalisé ; entraînement français à construire | Modèles fournis anglais. Code Apache-2.0, poids inclus CC BY-NC-SA 4.0 : pas un choix commercial libre par défaut. Non exécuté |
| [Rustpotter](https://github.com/GiviMAD/rustpotter) | 1.x, 2, 3.0.0–3.0.2 ; CLI 3.0.2 ; références MFCC/DTW ou modèle neuronal | Personnalisation à partir de références ou d'un corpus ; intéressant comme baseline acoustique indépendante | Pas de poids français d'urgence identifiés ; wrapper Android non validé. CLI installé et aide vérifiée, aucun résultat acoustique revendiqué |
| [Vosk](https://github.com/alphacep/vosk-api) | Python 0.3.45, tag source 0.3.50 ; FR small 0.22 41 Mo, FR 0.22 1,4 Go, pguyot 0.3 39 Mo, linto 0.6/2.2.0 1,5 Go | Reconnaissance française locale, grammaire restreinte ; small 0.22 adapté mobile | ASR contraint plutôt que KWS dédié. Poids non accessibles depuis cet environnement. 0.22 Apache-2.0 ; pguyot NC-SA ; linto AGPL selon catalogue officiel |
| [PocketSphinx](https://github.com/cmusphinx/pocketsphinx) | 5.0.0–5.0.4, 5.1.0, 5.1.1 | Recherche de mots-clés / grammaire locale légère | Nécessite acoustique et dictionnaire français ; modèle anglais fourni ne convient pas. Poids français/version exacte non vérifiés ici ; non testé |
| [WakeNet / ESP-SR](https://github.com/espressif/esp-sr) | WakeNet9, 9s, 9l, 10, 10s ; WakeNet10 w16a16/w8a16 ; README annonce ESP-SR 2.6.0 pour 10s | Français annoncé en septembre 2026 ; `wn9l_fr_bonjouresp_tts3`, `wn10_fr_bonjouresp` disponibles au catalogue | Cible ESP32 ; licence ESPRESSIF MIT consultée autorise les produits Espressif, ne pas supposer un droit de portage Android. MultiNet mn5q8/6/7 reste anglais/chinois. Non exécuté |
| [WeKws](https://github.com/wenet-e2e/wekws) | Toolkit entraînable ; recettes Hey Snips, Google Speech Commands, Hi Miya, Hi Xiaowen | Base de recherche pour apprendre des mots-clés ; Android figure dans la feuille de route | Aucun checkpoint français d'urgence validé ici ; compatibilité effective Android à établir. Non exécuté |
| [SpeechBrain](https://github.com/speechbrain/speechbrain/tree/develop/recipes/Google-speech-commands) | Google Speech Commands v0.02 ; 12/35 classes ; x-vector et LEAF | Baselines de classification de commandes, transférables avec entraînement | Classes anglaises ; précision sur clips GSC non comparable aux faux déclenchements par heure. Non exécuté |
| [Mycroft Precise](https://github.com/MycroftAI/mycroft-precise) | 0.1.0/0.1.1, 0.2.0, 0.3.0 | Détecteur entraînable, piste historique | Pas de modèle français d'urgence vérifié ; maintenance/stack ancien à évaluer. Non exécuté |

Deux pistes adjacentes ne sont pas présentées comme modèles spécialisés validés :
Whisper.cpp en mode commandes reste un ASR général ; la recette MatchboxNet/NeMo
doit être vérifiée davantage (chemins de configuration recherchés renvoyant 404).
Le catalogue n'est donc pas une certification d'exhaustivité de tous les systèmes.

## Essais réellement exécutés : Sherpa KWS français

Script : [test_french_transducer_kws.py](recherche-specialistes/test_french_transducer_kws.py).
Exécution CPU hôte, un thread, sans dépendre du VAD principal, blocs de 50 ms.
Résultats agrégés et empreintes de modèles dans `recherche-specialistes/`.

Corpus par configuration : cinq enregistrements sans voix (500 s), quatre
enregistrements de parole ordinaire ne contenant pas les mots cibles (203,24 s),
et quinze mots courts synthétiques : aide, au secours, non, oui, stop, chacun à
trois niveaux. **Aucun positif réel d'urgence à 5 m dans ce corpus.** Les phrases
réelles à 5 m constituent ici des négatifs lexicaux, pas un test de rappel d'urgence.

Les exports ASR français ne comprennent pas leur tokenizer SentencePiece `.model`.
Le script énumère donc les décompositions valides de la table de tokens pour
chaque mot, sans tronquer la liste. C'est une adaptation expérimentale : ni un
modèle KWS français officiellement entraîné, ni une reproduction garantie du
tokenizer d'origine. Pas de silence artificiel ajouté en fin d'entrée.

Kroko 2025, réglage standard sans amplification : **0/15 mots synthétiques
détectés**. Avec score 2, seuil 0,15 et gain plafonné à 64 : **1/15**, sans
déclenchement sur les 703,24 s négatives. Le réglage standard utilise score 1,
seuil 0,25. Ces résultats insuffisants empêchent de recommander cette adaptation
telle quelle. Zéro faux sur moins de douze minutes n'est pas une validation de
fiabilité ; le manque de sensibilité rend aussi ce résultat peu probant.

Le modèle français 2023, avec encodeur et joiner INT8 (décodeur flottant),
reconnaît **2/15 mots synthétiques** au réglage sensible avec gain max 64,
également sans événement sur les 703,24 s négatives. Au total : 72 replays,
soit trois configurations sur 24 entrées, sans indépendance entre replays.
Ne pas extrapoler à son encodeur FP32, non exécuté dans cette étude. Il faut une
calibration positive avec parole claire et tokenizer officiel avant de conclure
sur l'aptitude intrinsèque du modèle ou de multiplier les réglages.

## Décision et protocole pour départager les candidats

Conserver Qwen pour la précision des phrases et l'architecture mémorisée pour
le découpage ; ne pas les remplacer sur la foi des promesses des catalogues.
Préparer une voie spécialisée qui lit le flux et peut conserver son propre audio
avant/après détection, même si le VAD principal rejette ce passage.

Comparer d'abord Porcupine personnalisé, microWakeWord entraîné en français et
Sherpa KWS correctement calibré avec les **mêmes mots cibles et le même corpus**.
Séparer locuteurs, pièces et téléphones entre entraînement, réglage et évaluation.
Inclure les vrais mots courts/phrases à 1, 3 et 5 m, voix douce, bruit avec parole,
conversation ordinaire et longues périodes sans voix. Les exemples synthétiques
servent de contrôle, pas de preuve de rappel à distance.

Mesurer rappel par mot/distance, faux candidats par heure, fausses alertes après
confirmation, délai d'alerte, mémoire et coût sur les téléphones. Distinguer mot
réellement prononcé dans une conversation et situation d'urgence : « aide aux
devoirs » contient bien le mot, mais ne justifie pas automatiquement une alerte.
Fixer les objectifs de validation avant de choisir les seuils sur le test final.
La liste actuelle de cinq mots n'est pas une configuration d'urgence de production.

## Traçabilité et limites

L'inventaire des versions Git et commits consultés est conservé dans
[versions-depots.json](recherche-specialistes/versions-depots.json). Les sources
des conclusions sont les dépôts officiels liés ci-dessus, leurs README, les
métadonnées PyPI/Maven et le [catalogue français Vosk](https://github.com/alphacep/vosk-space/blob/master/models.md).
Les noms de versions ne prouvent ni la disponibilité de chaque poids ni son droit
d'usage. Les restrictions de licence pertinentes sont signalées, sans avis juridique.
Les accès bloqués aux hébergeurs de poids n'ont pas été contournés.

Aucun WAV, contenu de conversation ou identifiant d'enregistrement utilisateur
n'est publié. Aucun test Android, autonomie ou écoute multi-micro n'est revendiqué.
