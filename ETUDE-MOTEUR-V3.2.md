# Étude et corrections du moteur Guardian v3.2 — 19 octobre 2026

## Résultat principal

Dans les essais transmis, le moteur transcrit les trois répétitions à 1 mètre
et les trois à 3 mètres. À 5 mètres, le VAD ne transmet aucune des trois phrases
attendues ; Qwen produit seulement une sortie « Systeme. » issue d'un autre segment.
Pourtant la parole est exploitable dans le WAV : une relecture diagnostique par
Qwen retrouve la phrase. Le défaut principal de cet essai se situe dans la détection
avant transcription, pas dans une absence totale de son ou un modèle Qwen manquant.

| Distance déclarée | APK testé : phrases attendues transcrites exactement | Chaîne corrigée, rejouée hors Android |
|---|---:|---|
| 1 m | 3/3, plus une fausse sortie confirmée pendant le silence | 3/3 exactes, aucun segment supplémentaire |
| 3 m | 3/3, plus une sortie supplémentaire « systeme » | 3/3 exactes, aucun segment supplémentaire |
| 5 m | 0/3, une sortie « Systeme. » | 3 phrases récupérées : 2 exactes, 1 substitution « les » au lieu de « mes » |

La phrase de référence est « Je vais chercher mes chaussures et je reviens. ».
À 5 m, la relecture corrigée donne une erreur de mot sur 24 mots attendus (4,17 %).
Ces chiffres décrivent ces neuf répétitions de la même phrase sur un seul téléphone,
dans les conditions fournies. Ils ne constituent pas une mesure générale du modèle.
La disparition des segments supplémentaires est constatée sur les WAV rejoués ;
le sens exact de tous les bruits hors exercice ne peut pas être établi par les logs.
Le silence de la fausse phrase à 1 m a été explicitement confirmé par l'utilisateur.

## Ce que les fichiers démontrent

Les douze WAV des trois collectes sont lisibles et leurs SHA-256 correspondent aux
manifestes. L'index permet de relier chaque bloc aux séquences de capture. Les logs
ne rapportent aucune tâche d'entrée perdue. Le routage sélectionné dans les trois
sessions est VOICE_RECOGNITION, mono, 16 kHz, mapping Android PROCESSED.

L'inventaire et les essais matériels existent bien. Une route MIC stéréo à deux
informations acoustiques indépendantes est conservée comme alternative. Le choix
AUTO reste la référence mono, avec performance comparative UNVERIFIED : le code
ne démontre donc pas que cette référence est la meilleure pour la parole distante.
Cela ne justifie pas de forcer la stéréo sur tous les téléphones. Les mappings
PROCESSED ne garantissent pas un accès physique indépendant à chaque microphone.

À 1 et 3 m, la transcription fonctionne avec une très courte attente dans la file,
généralement 0 à 2 ms ; un calcul déjà en cours peut faire attendre un autre travail.
Le test à 3 m contient aussi une attente de 526 ms. Les anciens décodages intermédiaires
répètent du calcul hors nécessité pour un affichage après la pause.

Le WAV `pregain` n'est pas le microphone brut : il est déjà passé par le filtre mono,
avant gain. `truth`, `asr` et `vad` sont identiques en DISTANT dans l'APK testé.
Ce diagnostic ne permet donc pas d'attribuer toute atténuation à un filtre précis.
La nouvelle piste `raw` observe AudioRecord avant le filtre mono, après l'éventuelle
sélection ou combinaison des canaux ; elle n'est pas une capture séparée de chaque
micro physique.

## Comparaisons réellement exécutées

- Silero d'origine : seuil probabiliste 0,5, confirmation 128 ms, vue conditionnée
  jusqu'à ×4. Sa fausse activation à 1 m est reproductible depuis le WAV.
- Confirmation 192 ms sur la même vue : les six phrases à 1 et 3 m restent détectées ;
  les brefs segments supplémentaires disparaissent. Cela ne suffit pas à 5 m.
- Gains de la seule vue VAD jusqu'à ×256 : pas de récupération fiable des trois
  phrases à 5 m ; certains gains ajoutent des déclenchements. Non retenus.
- Sensibilité Silero augmentée et TEN-VAD : non retenus, les essais ne démontrent
  pas un meilleur compromis. Aucun modèle TEN n'est ajouté à la livraison.
- GTCRN en vue de détection, gain modèle borné ×6, compensation de sortie, Silero
  ×4 avec confirmation 192 ms : les trois passages à 5 m sont détectés.
- Le nettoyage seul peut supprimer un mot court synthétique (« stop »). Une voie
  originale est donc conservée, avec accord de deux vues neurales pour éviter de
  réintroduire les faux positifs de l'ancienne vue amplifiée.

Les prototypes ont été corrigés pour reproduire les blocs natifs GTCRN de 256
échantillons et la FIFO de compensation des gains. Les conclusions de livraison
utilisent cette version, et non une approximation par blocs de 800 échantillons.
La normalisation RMS essayée dans un prototype n'est pas retenue.

## Fonctionnement livré

Aucun seuil de volume absolu ne bloque les voix faibles. Le VAD conserve son seuil
probabiliste 0,5 ; 192 ms est une durée de confirmation, pas une intensité minimale.

Lorsque la vue GTCRN correspondante est disponible, la décision est :

`VAD(signal nettoyé ×4) OU [VAD(original ×4) ET VAD(original sans amplification)]`

Toutes ces vues utilisent la confirmation 192 ms. Ce sont des décisions neurales ;
le gain, le RMS, Far Voice et les mots retournés par Qwen ne peuvent pas ouvrir
un segment. Une panne ou un retard de l'enhancer utilise le VAD original comme
repli borné et journalisé. Cette dégradation peut perdre le bénéfice observé à 5 m.

Sur les appareils recommandés par le benchmark existant, DISTANT active aussi la
vue GTCRN ; NOISY continue de la demander explicitement. Le nettoyage n'est pas
substitué au PCM original envoyé à Qwen. L'attente de synchronisation reste bornée
à 200 ms. Si le modèle GTCRN ne peut pas être chargé, la capture peut continuer
sur le VAD original, avec une trace explicite.

Le pré-enregistrement passe de 1 à 2 secondes pour conserver les syllabes faibles
qui précèdent une détection tardive. Le segment se clôt après 1,2 seconde sans
parole ; 0,8 seconde de queue est conservée. Qwen ne reçoit qu'un résultat final
par segment. Une parole continue reste découpée à 8 secondes avec recouvrement.
L'envoi du dernier segment pendant la pause est normal : il contient la parole
validée juste avant ; cela n'autorise pas un nouveau segment sur le silence seul.

## Validation et limites

- 40 contrôles hôte Kotlin : segmentation, absence d'intermédiaires en production,
  pré-enregistrement, ordre des trames, repli borné, concurrence/reset, gain,
  décision neurale, cinq WAV et commandes privées.
- Compilation des adaptateurs contre l'API officielle Sherpa 1.13.8, avec stubs
  Android. Ce contrôle ne compile pas le service Android entier ni un APK.
- Relecture native : GTCRN 256 échantillons, gain compensé, trois vues Silero,
  puis véritable `GuardianSpeechSegmenter` Kotlin de la livraison. Les fenêtres
  finales originales sont transcrites avec Qwen3-ASR 0.6B int8, Sherpa 1.13.8,
  français imposé. Neuf segments attendus et aucun segment supplémentaire.
- Quinze détections de mots courts synthétiques (« aide », « stop », « oui »,
  « non », « au secours » à trois niveaux) conservées. Ce ne sont pas de vraies
  voix ni une certification des appels d'urgence.
- Douze bruits synthétiques de 30 secondes (blanc, rose, ventilateur, clics,
  trois niveaux) : aucune admission de parole avec la chaîne retenue.
  Cela ne couvre pas les conversations, la télévision ou tous les bruits réels.
- Huit scénarios PowerShell avec ADB simulé : AUTO et trois routes explicites,
  ancienne révision refusée, téléchargement altéré refusé, limite WAV détectée,
  route non appliquée détectée, restauration exacte des commandes.

L'application Expo et son AAR Sherpa personnalisé ne sont pas présents ici :
la compilation APK complète et le JNI sur le téléphone restent à valider.
Le modèle officiel Qwen est de la même variante que celui du téléphone ; les
runtimes CPU et les détails numériques peuvent modifier certaines transcriptions.
La batterie, la chauffe et le comportement sur d'autres téléphones ne sont pas
mesurés par cette relecture. Les voix brutes, les archives utilisateur et les logs
personnels ne sont pas publiés avec cette étude.

## Fichiers principaux modifiés

- `GuardianNeuralVad.kt` : confirmation 192 ms et conditionnement configurable.
- `GuardianNeuralInputGain.kt` : décision par VAD nettoyé et accord des vues originales.
- `GuardianQwenPrimaryAudioProcessor.kt` : trois vues neurales, jointure GTCRN en
  DISTANT, diagnostic des décisions, piste raw, Qwen toujours sur l'original.
- `GuardianSpeechSegmenter.kt` : pré-enregistrement 2 s, retrait des fenêtres intermédiaires.
- `GuardianProtectionService.kt` : activation GTCRN selon profil/capacité, repli
  explicite si indisponible, commandes de route privées, paramètres journalisés.
- `GuardianDiagnosticOptions.kt` : sélection de commandes privées avec repli externe.
- `GuardianDiagnosticWavWriter.kt` : ajout de la piste raw.
- Scripts et tests associés : collecte, intégrité, restauration, relecture et installation.

## Vérification suivante sur le téléphone

Installer le module v3.2, reconstruire l'APK et l'installer comme mise à jour,
sans désinstaller Guardian pour conserver Qwen. Utiliser le nouveau collecteur
simple-3 : il attend les cinq WAV et la révision diagnostique 3.

Recommencer d'abord les trois phrases à 5 m puis le silence, avec la route AUTO.
Les logs doivent montrer GTCRN installé et des vues GTCRN réellement utilisées.
Ajouter ensuite quelques mots courts à 1 m et un test avec bruit sans autre voix.

La comparaison des microphones sera un essai séparé à conditions constantes :
AUTO, puis MIC_MONO et MIC_STEREO_FOCUS si la route est acceptée. Le script sait
poser et restaurer les commandes privées correspondantes. Aucune supériorité de
la stéréo n'est revendiquée avant cette comparaison.
