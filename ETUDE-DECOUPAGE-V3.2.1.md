# Comparaison des fins de parole et correction du découpage à 5 mètres

## Résultat retenu

Conserver le gain actuel, les 1 200 ms de fin de parole, le pré-enregistrement de 2 secondes et la queue audio de 800 ms. Corriger la coupure technique des longues séquences : utiliser une pause neuronale déjà présente dans le tampon lorsque cela est possible, sans jeter l'audio suivant.

Sur le dernier test réel à 5 mètres, cette correction donne en relecture **trois phrases exactes, sans le fragment supplémentaire**. Elle doit encore être vérifiée dans un nouvel APK sur le téléphone. Elle ne corrige pas à elle seule les faux déclenchements dans le silence.

## Méthode : conserver les décisions prises sur le téléphone

Une première comparaison a recalculé le VAD sur les WAV avec le frontal Python v3.2. Elle n'a pas reproduit exactement les frontières du téléphone. Elle n'est donc pas utilisée pour conclure sur le défaut observé.

La comparaison décisive utilise le WAV ASR original, les offsets de son index et les événements `VAD_CHANGE` enregistrés pendant la session. Les 1 112 trames de 800 échantillons et les neuf changements d'état sont vérifiés : aucun trou, aucune réinitialisation du pipeline. Seul le délai de fin de parole varie dans des copies temporaires du segmenter Kotlin réel. Le gain, les décisions VAD et les paramètres Qwen restent identiques.

La variante de référence à 1 200 ms reproduit les trois fenêtres du téléphone, avec les mêmes tailles et les mêmes raisons de coupure : 128 000, 75 200 et 108 000 échantillons. Qwen reproduit également les trois phrases et le fragment observé. C'est ce contrôle qui permet de comparer les variantes sur le problème réel.

## Comparaison sans correction de la coupure technique

| Silence de fin | Fenêtres Qwen | Résultat |
|---|---:|---|
| 800 ms | 4 | Deux phrases exactes ; un segment devient « systeme » ; une autre phrase comporte des erreurs |
| 1 000 ms | 3 | Deux phrases exactes ; erreurs dans la deuxième répétition |
| 1 200 ms | 3 | Trois phrases exactes ; fragment supplémentaire « Je vais » à la coupure technique |

Le VAD comporte une interruption de 850 ms au milieu de la parole. À 800 ms, le segmenter termine trop tôt cette séquence. Le texte « systeme » de cette variante est produit par une relecture d'un fragment : il ne constitue pas la preuve d'une nouvelle fausse alerte Guardian pendant un silence réel.

Sur les autres WAV avec VAD recalculé (1 m, 3 m, ancien 5 m), les trois délais transmettent les mêmes échantillons à Qwen ; ils changent uniquement l'instant d'envoi de 200 ou 400 ms. Le test silencieux connu de six secondes ne transmet aucun segment dans ces variantes. Ces contrôles ne suffisent pas à valider les nouveaux déclenchements signalés par l'utilisateur.

## Correction v3.2.1

Le segmenter continue d'attendre 1 200 ms pour une fin naturelle. À l'approche de la limite technique de 8 secondes, il peut utiliser une pause déjà terminée dans l'audio accumulé :

- Pause d'au moins 900 ms de VAD négatif, suivie d'une reprise de parole. Ce seuil sert uniquement à choisir rétrospectivement une coupure technique ; il ne raccourcit pas la fin naturelle.
- Conservation de la queue de 800 ms après le dernier noyau de parole avant cette pause.
- Coupure candidate située après au moins 2 secondes et avant les 7 premières secondes du tampon, afin de ne pas produire un petit préfixe.
- Conservation de tout le reste du tampon pour la fenêtre suivante, avec les 400 ms de recouvrement acoustique existantes.
- En l'absence de pause admissible, maintien de la coupure technique actuelle à 8 secondes.

Les décisions de présence de parole restent celles du VAD. Aucune ponctuation générée par Qwen et aucun seuil de volume ne décident d'une frontière audio.

| Relecture du dernier test à 5 m | Tailles des fenêtres | Phrases exactes | Fragment supplémentaire |
|---|---|---:|---|
| Référence v3.2 | 8,00 s / 4,70 s / 6,75 s | 3/3 | Oui |
| Correction v3.2.1 | 5,95 s / 6,75 s / 6,75 s | 3/3 | Non |

Le nombre d'appels Qwen et la durée totale d'audio transmise restent identiques : trois appels, 19,45 secondes avec recouvrement. La correction améliore le contexte des fenêtres ; elle ne démontre pas une baisse de consommation. L'envoi de la première fenêtre reste déclenché à la limite technique : ce changement ne démontre pas non plus une alerte plus rapide.

## Fichiers modifiés dans le module

1. `GuardianSpeechSegmenter.kt` : frontière technique alignée sur une pause, conservation du reste du tampon, nouvelle raison `QWEN_PAUSE_ALIGNED_ROLLOVER`.
2. `GuardianProtectionService.kt` : révision diagnostique `guardian-voice-v3.2.1`, stratégie et durée minimale de pause inscrites dans le démarrage de session.
3. `tests/host/VoiceCoreTest.kt` : deux contrôles supplémentaires pour la continuité de l'audio et la protection contre une interruption de 850 ms.

Aucun changement de capture micro, de gain, de modèle, de seuil VAD, de règle d'alerte ou de communication réseau. Le ZIP contient le module complet ; le patch permet de relire les trois différences avec v3.2. L'APK doit être reconstruit pour activer cette correction.

## Vérification et limites

- 42 contrôles hôtes réussis, dont conservation exacte de l'audio autour de la coupure, recouvrement, taille bornée, faible parole, réinitialisation et absence de décodage intermédiaire.
- Sur vingt entrées de contrôle, les fenêtres sont inchangées octet par octet : quatre enregistrements avec VAD recalculé, l'extrait de silence connu et quinze mots courts synthétiques. Les transcriptions de ces entrées ne sont donc pas améliorées par cette correction ; les faiblesses connues sur les mots courts restent ouvertes.
- La relecture décisive du dernier test à 5 mètres utilise les décisions du téléphone, et reproduit d'abord les tailles et sorties de la référence.
- Pas de compilation de l'application Android complète ici : projet applicatif et AAR personnalisé non disponibles. La validation du nouvel APK sur téléphone reste à faire.
- Pas de conclusion générale sur d'autres téléphones, le bruit, les urgences manquées ou les nouvelles fausses alertes. Le test de silence préparé reste nécessaire.

## Reproducteurs

`etude-segmentation/build_recorded_input.py` construit une entrée vérifiée à partir du WAV ASR, de l'index et des logs ou d'un tableau JSON d'événements. Il refuse les discontinuités, les sessions ambiguës, les changements VAD manquants et les réinitialisations non prises en charge.

`etude-segmentation/compare_silence.py` compile trois copies temporaires du segmenter (800, 1 000 et 1 200 ms), les rejoue et fait transcrire leurs fenêtres par Qwen. Le module source reste intact. Avec `--recorded-input`, il conserve les décisions du téléphone ; sans cette option, il recalcule le VAD et ne garantit pas la reproduction de la session originale.

Pour reproduire la référence, utiliser les sources du module v3.2. Pour la correction, utiliser le module v3.2.1. Les fichiers détaillés et les WAV restent privés ; seuls les résultats agrégés sont publiés.
