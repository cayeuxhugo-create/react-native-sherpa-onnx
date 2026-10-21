# Recherche élargie : admission de parole faible et urgences hors ligne

Recherche du 21 octobre 2026, fondée sur les dépôts et documents officiels accessibles, puis sur des exécutions locales. Aucun moteur de recherche web général n'est disponible dans cet environnement ; les références consultées sont listées ci-dessous. Aucun audio utilisateur n'est envoyé à un service externe.

## Recommandation issue de la recherche

**FireRedVAD en version non-streaming est le candidat de confirmation le plus prometteur testé ici.** Le rôle proposé est d'examiner la fenêtre originale déjà constituée, avant d'autoriser son décodage par Qwen. La capture, le VAD préliminaire sensible et les PCM de Qwen restent conservés.

Le candidat fournit 8/8 rejets des fenêtres suspectes connues, 12/12 fenêtres de phrases admises et 15/15 mots courts synthétiques admis, dans les neuf premières variations d'amplitude et de cadrage essayées. Cela améliore la stabilité observée par rapport à la combinaison précédente WebRTC/Silero/TEN.

Six contrôles supplémentaires avec cadrage intermédiaire à 5 et 15 ms révèlent encore une phrase perdue à niveau -6 dB dans deux variantes. Il reste donc imparfait : deux admissions suspectes sur 65 fenêtres supplémentaires des enregistrements réels et deux rejets sur 24 copies de phrases avec bruit ajouté. Ce résultat justifie un prototype Android, pas une garantie de fonctionnement sur tous les téléphones ou toutes les urgences.

**Aucun changement du moteur Android n'est livré dans cette recherche.** Les scripts de mesure, les résultats et un téléchargeur vérifié des poids officiels sont fournis.

## Solutions examinées

| Piste | Ce qui a été vérifié | Position pour Guardian |
|---|---|---|
| FireRedVAD | Code et poids officiels, versions streaming et non-streaming, ONNX, frontal et post-traitement ; runtime mobile NCNN documenté | Priorité : confirmation locale d'un segment avant Qwen |
| Earshot | API Python exécutée, réseau compact, API C/C++ documentée, licence MIT/Apache-2.0 | Très léger, mais les configurations essayées perdent trop de nos phrases faibles |
| Vosk | Documentation officielle : français, Android, streaming, vocabulaire configurable ; exemple de grammaire avec `[unk]` | Candidat à évaluer pour une voie dédiée aux phrases d'urgence ; aucun score sur nos WAV n'est revendiqué |
| Sherpa KeywordSpotter | API Kotlin 1.13.8 disponible, scores/seuils et mots configurables | Bibliothèque déjà proche de l'existant ; modèle et encodage français à valider, sans supposer un modèle KWS français prêt à l'emploi |
| Porcupine / Rhino | SDK documentés pour français et Android, commandes personnalisées, traitement local annoncé | Option commerciale à comparer pour détection directe d'urgences ; compte/AccessKey et conditions d'usage à vérifier, aucun essai local effectué |
| openWakeWord | Code et recommandations d'évaluation consultés | Modèles prêts à l'emploi annoncés en anglais ; français à entraîner/valider, poids pré-entraînés sous licence non commerciale |
| FunASR / FSMN-VAD | API et modèle VAD compact documentés ; la table officielle marque le VAD zh/en | Piste supplémentaire, sans validation française ni essai ici ; aucune supériorité revendiquée |
| pyannote.audio | Pipeline et documentation consultés | Utile pour la diarisation ; pas la première priorité pour cette confirmation mobile légère |

Vosk est intéressant pour reconnaître directement les phrases déjà configurées dans Guardian, telles que « au secours », « aide moi » ou « je suis en danger ». Une grammaire restreinte peut toutefois forcer une mauvaise correspondance : la voie de rejet et les fausses activations doivent être mesurées. Aucune phrase d'alerte ni règle existante n'est modifiée.

Les pré-entraînés openWakeWord ne constituent pas une solution française immédiate. Les scores publiés par les fournisseurs sur d'autres benchmarks ne sont pas des performances mesurées pour Guardian.

## Pourquoi FireRed mérite ce nouvel essai

Le dépôt officiel annonce un VAD multilingue et publie un modèle non-streaming d'environ 2,4 Mo. Il fournit également un modèle streaming, de la détection d'événements audio, un export ONNX et un runtime NCNN avec construction Android. Code et fichiers du dépôt consulté portent une licence Apache-2.0.

Les auteurs rapportent 97,57 % de F1 pour le VAD non-streaming sur FLEURS-VAD-102. Cette mesure vient de leur évaluation ; elle ne doit pas être transformée en précision garantie pour Guardian, en taux d'alertes par heure ou en performance mesurée à cinq mètres.

La version **non-streaming** convient ici parce que le segment complet existe déjà avant l'appel Qwen. Elle peut exploiter le contexte du segment pour décider si du bruit ressemble seulement à la parole. Elle ne remplace pas Qwen et ne reconnaît aucun mot d'urgence.

## Exécutions sur notre corpus

Même corpus de départ que précédemment : huit fenêtres suspectes sélectionnées dans les tests déclarés sans parole, douze fenêtres de vraies phrases (1 m, 3 m et deux séries à 5 m), quinze contrôles de mots courts synthétiques. Les sorties sont scorées pour l'admission, pas pour la transcription correcte.

Vingt-huit configurations ont été exécutées :

- FireRed Stream-VAD : seuils 0,4 / 0,5, gains maximum 1 / 4 / 16 / 64.
- FireRed non-stream VAD : mêmes seuils et gains.
- Earshot : seuil 0,5, gains maximum 1 / 4 / 16 / 64 et supports positifs 80 / 160 / 240 ms.

| Exemple de configuration | Suspects rejetés | Phrases admises | Mots synthétiques admis |
|---|---:|---:|---:|
| FireRed streaming, seuil 0,5, gain max. 64 | 3/8 | 12/12 | 15/15 |
| FireRed non-stream, seuil 0,5, gain max. 16 | 8/8 | 10/12 | 15/15 |
| **FireRed non-stream, seuil 0,5, gain max. 64** | **8/8** | **12/12** | **15/15** |
| Earshot, seuil 0,5, gain max. 16, support 160 ms | 4/8 | 7/12 | 15/15 |

Le seul nom d'un modèle récent n'est donc pas une solution. La version, le frontal, le niveau interne et le rôle dans la chaîne changent les résultats.

## Configuration FireRed retenue pour les vérifications supplémentaires

- Modèle officiel `fireredvad_vad.onnx`, non-streaming, issu du commit `c30ec49e8cc69642b0ee65362eba11b9d11c6e54`.
- Frontal officiel : PCM16 mono à 16 kHz, FBank Kaldi 80 bandes, fenêtres de 25 ms, pas de 10 ms, dither nul, `snip_edges=True`, CMVN officielle.
- Post-traitement officiel : lissage cinq trames, seuil 0,5, parole minimale vingt trames (200 ms), silence vingt trames, sans extension/fusion supplémentaire.
- Gain **maximum** 64 sur la copie de confirmation uniquement, limité par le pic à 0,8, sans atténuation du signal déjà fort. Le gain effectif dépend donc du pic.
- Original destiné à Qwen inchangé. Il ne s'agit pas d'augmenter le gain du microphone ou de la transcription.
- État vierge pour chaque fenêtre complète. Le protocole ne simule pas l'utilisation d'un même état neuronal sur un flux continu.

Cette configuration est choisie sur le petit corpus disponible. Les données supplémentaires ne sont pas un jeu indépendant de nouveaux locuteurs ou téléphones.

## Vérification de stabilité

Avec la configuration fixe ci-dessus, les neuf combinaisons -6 / 0 / +6 dB et cadrage 0 / 10 / 20 ms donnent toutes :

- 8/8 fenêtres suspectes rejetées ;
- 12/12 fenêtres de phrases admises, dont les six à 5 mètres ;
- 15/15 contrôles synthétiques admis.

Le pas des caractéristiques étant de 10 ms, six contrôles supplémentaires à 5 et 15 ms ont été exécutés pour tester un décalage entre les trames. Les huit rejets et les quinze mots synthétiques restent conservés dans les six cas. À -6 dB, une phrase à 5 mètres est toutefois perdue dans chacune des deux variantes de cadrage : 11/12 phrases passent. Aux autres niveaux, 12/12 passent. Au total, les trois critères sont simultanément satisfaits dans treize des quinze variantes, pas dans toutes.

C'est une amélioration par rapport à la combinaison précédente, qui perdait trois à quatre phrases à -6 dB. Les perturbations portent sur les mêmes enregistrements : elles n'établissent pas la robustesse à toutes les situations réelles.

## Contrôles supplémentaires et limites observées

Les 57 contrôles générés avant cette recherche ont été rejoués sans changer la configuration :

| Contrôle | Résultat |
|---|---:|
| Bruits synthétiques rejetés | 18/18 |
| Copies de phrases réelles avec bruit rose ajouté, SNR globaux 20 et 10 dB | 22/24 admises |
| Mots synthétiques avec bruit rose ajouté à 10 dB | 15/15 admis |

Leurs définitions sont dans le rapport complémentaire précédent. Les mélanges ne sont pas de nouvelles prises de son, et une fenêtre admise n'est pas forcément correctement transcrite par Qwen.

Les cinq périodes de 100 secondes ayant des WAV valides dans les tests du 21 octobre ont également été découpées en fenêtres de huit secondes maximum : 65 fenêtres, 500 secondes au total. Le troisième bloc, dont les WAV ne correspondent pas au chronomètre, reste exclu. Ces nouvelles frontières ne sont pas les fenêtres exactes produites par le VAD actuel.

FireRed rejette **63/65** de ces fenêtres. Une fenêtre du deuxième silence confirmé et une fenêtre du bruit blanc sont encore admises. Ces résultats empêchent de présenter le modèle comme exempt de faux. Ils concernent un test de confirmation isolé sur ces nouvelles frontières, pas le taux d'alertes final de Guardian. La recommandation porte sur la confirmation des segments préalablement formés par le moteur existant.

## Contrôles du reproducteur

Le frontal CPU a été comparé aux classes officielles de calcul FBank/CMVN, sur silence, bruit et un exemple de parole public : différence maximale nulle sur ces trois contrôles. La version ONNX streaming a aussi été comparée trame par trame et par blocs : différence maximale nulle dans le contrôle exécuté.

Le contrat du post-traitement a été vérifié : il renvoie d'abord des décisions binaires par trame, à convertir en segments. Une liste non vide de zéros n'est pas une détection de parole. Les résultats non-streaming retenus utilisent cette conversion et les contrôles « tout zéro / tout un » passent.

Les empreintes des fichiers d'entrée sont conservées et vérifiées localement ; aucun WAV utilisateur n'est publié. Les 500 secondes supplémentaires sont rapprochées des marqueurs du téléphone, pas de l'horloge du PC. Les résultats agrégés se trouvent dans `recherche-moteur/results-summary.json`.

## Intégration recommandée, encore à réaliser

Chemin proposé :

```text
Capture continue → VAD sensible actuel → segment original
                  → confirmation FireRed → Qwen → règles Guardian existantes
```

La confirmation travaille sur une copie ; Qwen garde l'original qui a donné les bons résultats à cinq mètres. La version non-streaming ajoute un calcul après formation du segment, sans attendre de nouvel audio. Son coût et le délai final restent à mesurer sur téléphone.

L'API VAD actuelle de Sherpa 1.13.8 n'expose pas FireRed directement. L'intégration demande un frontal et un runtime/bridge Android, avec contrôle des ABI et des bibliothèques natives. Le runtime NCNN officiel est une piste mobile ; une intégration ONNX Runtime doit vérifier les dépendances déjà présentes dans l'AAR personnalisé. Les calculs ne doivent pas bloquer la capture, et les réinitialisations/arrêts doivent invalider les confirmations anciennes.

Une première intégration en observation permettrait de mesurer les désaccords avant de bloquer réellement des segments. Les mots d'urgence doivent être enregistrés avec des voix humaines, à différentes distances et dans le bruit ; les quinze mots synthétiques ne valident pas cette fonction. Les routes micro automatiques et le traitement multicanal déjà présents dans Guardian doivent également être comparés sur les appareils : Android ne garantit pas le même traitement d'une source sur tous les téléphones.

Pour la voie dédiée aux urgences, Vosk et les SDK de mots/commandes français restent des pistes distinctes. Ils ne sont pas présentés comme des remplaçants de Qwen validés à cinq mètres.

## Reproduction

Scripts dans `recherche-moteur/` :

- `download_firered.py` : téléchargement du modèle non-streaming, de la CMVN et de la licence depuis le commit officiel fixé ; SHA-256 vérifiés avant écriture dans un nouveau dossier.
- `evaluate_new_vad.py` : FireRed streaming et Earshot.
- `evaluate_firered_nonstream.py` : confirmations non-streaming.
- `stress_firered_nonstream.py` : configuration fixe, neuf variations avec `--stress`, ou nouveau manifeste de contrôles.
- `check_frontend.py` : équivalence des caractéristiques et contrat du post-traitement.

Dépendances effectivement utilisées : ONNX Runtime 1.31.0, kaldi-native-fbank 1.22.4, kaldiio 2.18.1, earshot 1.2.2, numpy ; les scripts partagent les utilitaires de simulation antérieurs. La source FireRed officielle est chargée uniquement pour les classes de post-traitement et les contrôles du frontal ; aucun modèle PyTorch n'est simulé et PyTorch n'est pas nécessaire pour cette exécution ONNX.

Le modèle non-streaming fait 2 388 488 octets. Son SHA-256 est `a226e68e695548754a9ab5480a55bbcb8522aa69111cb97e4f093ddce63ad5dc`. La CMVN porte `c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473`. Les poids ne sont pas inclus dans cette publication.

Le site des modèles Vosk et certains hébergeurs de modèles sont inaccessibles depuis cet environnement (HTTP 403). Les poids FireRed ont été obtenus depuis le dépôt officiel GitHub ; aucune performance Vosk ou commerciale n'est inventée pour compenser cette limite.

## Sources consultées

1. [FireRedVAD — dépôt et résultats publiés par les auteurs](https://github.com/FireRedTeam/FireRedVAD/tree/c30ec49e8cc69642b0ee65362eba11b9d11c6e54). Le benchmark FLEURS-VAD-102 est distinct de nos contrôles Guardian.
2. [FireRed — runtime NCNN et construction Android](https://github.com/FireRedTeam/FireRedVAD/tree/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/runtime/ncnn).
3. [FireRed — calcul FBank/CMVN et post-traitement](https://github.com/FireRedTeam/FireRedVAD/tree/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/fireredvad/core).
4. [Earshot — API, taille annoncée et implémentation](https://github.com/pykeio/earshot). La version Python évaluée est 1.2.2, licence MIT ou Apache-2.0.
5. [Vosk — français, fonctionnement hors ligne, Android et vocabulaire configurable](https://github.com/alphacep/vosk-api) ; [exemple officiel de grammaire et rejet `[unk]`](https://github.com/alphacep/vosk-api/blob/master/python/example/test_words.py).
6. [Sherpa — API Kotlin KeywordSpotter 1.13.8](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/KeywordSpotter.kt).
7. [Porcupine](https://github.com/Picovoice/porcupine) et [Rhino](https://github.com/Picovoice/rhino) : français/Android et commandes personnalisées documentés ; AccessKey, conditions et comportement de validation sans réseau à examiner avant adoption.
8. [openWakeWord — langues, évaluation et licences](https://github.com/dscripka/openWakeWord). Code Apache-2.0 ; pré-entraînés CC BY-NC-SA 4.0 selon le README consulté.
9. [FunASR — modèles FSMN et pipeline](https://github.com/modelscope/FunASR) ; [pyannote.audio](https://github.com/pyannote/pyannote-audio).
10. [Android AOSP — définition des sources audio](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/media/java/android/media/MediaRecorder.java). `UNPROCESSED` dépend de la disponibilité et retombe sur DEFAULT sinon ; une route identique ne garantit pas des caractéristiques identiques entre appareils.

Aucun nouvel APK, aucune modification de règle d'urgence et aucune validation universelle sur téléphone ne sont revendiqués.
