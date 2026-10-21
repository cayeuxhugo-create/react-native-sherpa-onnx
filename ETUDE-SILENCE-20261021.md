# Analyse du silence et des bruits sur le téléphone — 21 octobre 2026

## Conclusion

Les faux départs de transcription sont maintenant documentés sur le build **guardian-voice-v3.2.1**. Le premier bloc déclaré entièrement silencieux produit quatre sorties Qwen : une courte phrase affirmative, deux occurrences d'un même mot et une autre phrase sans rapport. L'utilisateur confirme l'absence de toute voix pendant les trois premiers blocs.

La faiblesse est localisée dans l'admission de la parole : la vue VAD nettoyée par GTCRN peut, à elle seule, déclarer la parole présente. Sur les épisodes suspects du premier bloc et du bruit blanc, les transitions enregistrées montrent `enhancedSpeech=true`, `primarySpeech=false` et `clearRawSpeech=false`. Le segmenter reçoit donc `speech=true` et envoie l'audio à Qwen, qui produit du texte sans parole déclarée par l'utilisateur.

**Cela ne démontre pas des alertes d'urgence.** Les décisions Voice Fusion présentes dans les logs restent `level=NONE`, `emergency=false` et `wake=false`. Il s'agit ici de fausses admissions acoustiques et de textes inventés, qui coûtent du calcul et pourraient alimenter de futures erreurs d'interprétation. Aucune alerte d'urgence positive n'a été identifiée dans ces logs.

## Données et intégrité

Six ensembles individuels ont été analysés, en privilégiant la version récupérée du bloc aspirateur et en dédupliquant l'archive originale correspondante. Le premier ZIP récapitulatif est un rapport d'échec sans bloc. Le second ZIP récapitulatif dépasse la limite de transfert de 32 Mio ; ses copies individuelles permettent néanmoins de traiter les six ensembles sans demander de renvoi.

Les 36 empreintes de fichiers ont été vérifiées. Les cinq WAV récupérés du bloc aspirateur ont des en-têtes réparés localement : les données PCM sont vérifiées par leur empreinte, et la reconstruction de l'en-tête original retrouve les empreintes des fichiers du téléphone. L'index de ce bloc se termine 50 à 100 ms avant certains WAV ; cette fin non indexée n'est pas utilisée pour une relecture exacte.

Les six WAV ASR représentent 618,4 secondes d'audio, démarrage et arrêt compris. Cela ne constitue pas 618,4 secondes de protocole chronométré valide : un bloc exporte la mauvaise session. Les durées de test ont été rapprochées des marqueurs `SIMPLE_RECORD_BEGIN/END` du téléphone, et non de l'heure du PC, dont l'horloge diffère.

## Résultats par bloc

| Bloc | Condition déclarée | Résultat |
|---|---|---|
| 1 | Silence | Quatre fenêtres Qwen issues du test, provenant de trois admissions VAD. Textes inventés d'après la confirmation de l'utilisateur. Une admission dure 7,75 secondes : le défaut n'est pas limité aux impulsions très brèves. |
| 2 | Silence | Aucune admission ni sortie Qwen dans la session capturée. |
| 3 | Silence | Collecte invalide pour les 100 secondes : les WAV exportés précèdent le début chronométré. Une seconde session contient deux sorties Qwen dans les logs, sans les WAV correspondants dans les ZIP individuels. |
| 4 | Aspirateur | Aucune sortie Qwen dans les logs conservés. Une admission commence après la fin chronométrée ; l'absence de sortie ne prouve pas une validation correcte de cette fin de capture. |
| 5 | Ventilateur | Une admission commence près de la fin chronométrée, puis sa transcription est publiée après le marqueur de fin. Une autre sortie et d'autres admissions surviennent après le test ; elles ne sont pas comptées comme appartenant aux 100 secondes. |
| 6 | Bruit blanc | Trois admissions/transcriptions pendant le test, dont une longue phrase sans rapport. Une autre admission très tardive est hors de la période chronométrée. |

Au total, onze événements `QWEN_RESULT` distincts apparaissent dans l'ensemble des logs, sessions supplémentaires et événements postérieurs au test compris. Ce nombre ne doit pas être présenté comme onze fausses alertes pendant dix minutes de protocole valide.

## Ce que le code et les logs permettent d'affirmer

Le code de `GuardianSpeechEvidence.accept` utilise, lorsque le nettoyage est disponible :

```text
parole = VAD_nettoye OU (VAD_original_gain4 ET VAD_original_gain1)
```

Ce choix avait permis de retrouver la parole lointaine, parfois refusée par les deux vues originales. Il admet aussi certains bruits réels de ce nouveau corpus. Remplacer simplement le OU par un ET pourrait supprimer ces faux mais ferait perdre la sensibilité obtenue à distance.

Le démarrage de Qwen est confirmé dans les six ensembles. Le profil est DISTANT, la route demandée AUTO, la capture effective VOICE_RECOGNITION mono et GTCRN activé. Les avertissements de repli GTCRN sont au démarrage ; les admissions suspectes identifiées plus tard utilisent bien la vue nettoyée. Aucun débordement d'entrée n'est rapporté dans les événements de fin disponibles.

La correction v3.2.1 de la coupure à huit secondes n'empêche pas ces fausses admissions. Dans le premier bloc, une longue admission erronée atteint encore la limite technique, ce qui explique que trois départs VAD donnent quatre fenêtres Qwen. La présence d'un mot récurrent dans les textes n'est pas la preuve qu'il a été prononcé, et le masquer ne corrigerait pas l'admission de bruit.

## Bug de collecte confirmé et corrigé

Dans le troisième bloc, le collecteur choisit une session dont les WAV durent environ 66 secondes. Le service termine cette session avant le marqueur de début du test. Le collecteur la considère néanmoins prête à enregistrer, parce qu'il trouve ses anciens événements de démarrage et ses WAV disponibles. Une nouvelle session apparaît ensuite.

Il n'est donc pas possible d'attribuer cette anomalie à une interruption effectuée par l'utilisateur pendant le test. La cause démontrée dans le collecteur est l'absence de vérification qu'une session est encore active au moment de la sélection.

`enregistrer-guardian.ps1`, version `simple-3.1`, corrige ce comportement :

- Choix prioritaire de la session la plus récente et exclusion des sessions déjà terminées.
- Contrôle de continuité de session pendant le chronométrage et juste avant sa fin ; arrêt ou changement de session rend le bloc invalide.
- Comptage des cinq étapes WAV distinctes, plutôt que de cinq messages potentiellement dupliqués.
- Vérification que les WAV ASR et VAD couvrent au moins la durée demandée, à 100 ms près.
- Conservation des logs, fichiers partiels et restauration des réglages en cas d'échec.

Douze scénarios ADB simulés passent : les huit contrôles existants, une session terminée suivie d'une active, un changement de session pendant le test un WAV trop court et des messages WAV dupliqués. Ce sont des tests du collecteur, pas un nouvel essai sur téléphone.

Cette correction est uniquement dans le script PC. **Le module Android et son admission de parole ne sont pas modifiés par cette livraison.** Aucun nouveau build n'est nécessaire pour utiliser le collecteur corrigé lors d'un prochain essai. Les anciennes commandes avec une URL de commit figée restent attachées à l'ancien collecteur ; utiliser la nouvelle révision publiée.

## Correction acoustique à préparer : limites de l'étude

La prochaine correction doit renforcer la confirmation acoustique des admissions provenant uniquement de GTCRN, en contrôlant aussi les voix réelles à 1, 3 et 5 mètres et les mots courts. Il serait injustifié de :

- Augmenter globalement le gain, puisque du bruit est déjà admis comme parole.
- Relever seulement un seuil de volume, au risque d'éliminer la voix douce distante.
- Allonger seulement la durée minimale de parole, puisque l'admission silencieuse la plus longue dure plusieurs secondes.
- Réduire le délai de fin de parole pour régler un problème d'admission.
- Supprimer des mots particuliers ou imposer le silence des deux VAD originaux comme unique motif de rejet.

Des essais exploratoires ont rejoué les WAV de la vue VAD avec des gains maximum de 1, 2 et 4, avec et sans la première trame de repli. Ils ne reproduisent pas toutes les décisions prises sur le téléphone. L'export PCM16 et les états/conditions exactes d'inférence doivent donc être pris en compte avant de valider un nouveau seuil ou un nouveau gain sur cette seule base. Ces essais exploratoires ne sont pas présentés comme une correction validée.

Pour une comparaison acoustique exacte, conserver les entrées flottantes réellement reçues par le VAD, ses réinitialisations et la disponibilité du nettoyage pour chaque trame serait préférable à une relecture supposée identique des WAV PCM16. La collecte actuelle fournit néanmoins déjà une preuve exploitable des faux départs et de leur chemin dans le moteur. L'absence de ces données supplémentaires ne remet pas en cause les événements enregistrés et la confirmation de silence par l'utilisateur.

Aucune donnée vocale, information personnelle ou identifiant de session n'est publié avec ce rapport.
