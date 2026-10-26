# Guardian Voice v3.4.6 — comparaison Wiener sur téléphone

Cette version reprend la sélection adaptative des canaux de v3.4.5 et ajoute un **Wiener adaptatif DD en voie d’étude**. Elle sert à comparer les signaux et les résultats avant de choisir une nouvelle chaîne de traitement. Elle n’établit pas que Wiener est meilleur sur téléphone.

## Ce qui change

- `GuardianWienerEnhancer.kt` : filtre continu à 16 kHz, FFT 512, pas 256, estimation DD avec alpha 0,98 et plancher 0,5. Pas de modèle supplémentaire à télécharger, pas de gain automatique ajouté.
- `GuardianWienerStudyStream.kt` : calibration, suivi de l’horloge audio et remise à zéro après changement de canal. Une fenêtre qui traverse une transition conserve son original complet.
- `GuardianAudioEngine.kt` et `GuardianVoiceInputRouter.kt` : chaque trame transporte la révision du canal au moment de sa capture, y compris si un traitement asynchrone se termine plus tard.
- `GuardianContinuousStudy.kt`, `GuardianQwenPrimaryAudioProcessor.kt`, `GuardianProtectionService.kt` : mode `WIENER_STUDY`, exports comparables et reconnaissance diagnostique du signal filtré. GTCRN n’est pas chargé dans ce mode.
- `enregistrer-guardian-wiener.ps1` : lancement et collecte avec contrôle de version, des canaux, des longueurs et des SHA-256.

La sélection de canaux reste celle de v3.4.5 : elle compare les canaux réellement ouverts par Android, peut changer pendant une phrase et limite les discontinuités par une transition courte. Un téléphone peut n’exposer qu’un canal, ou plusieurs copies du même signal. Les sources Android ne garantissent pas l’accès séparé à chaque microphone physique. Les traces permettront de vérifier ce que ce téléphone fournit et ce que le sélecteur choisit.

## Comparaison et sécurité du chemin principal

En `WIENER_STUDY`, FireRed continue de détecter et de découper **l’original sélectionné**. Le Qwen principal reçoit cet original. Les décisions du produit viennent toujours de cette voie. Chaque fenêtre est ensuite comparée à son intervalle Wiener : confirmation FireRed filtrée et, si elle accepte, Qwen diagnostique. Cette dernière voie n’appelle aucun écouteur de transcription ou d’alerte du produit.

Le modèle Qwen est partagé : une seule tâche diagnostique peut être en attente, derrière les tâches principales. Une tâche déjà commencée ne peut pas être interrompue ; l’étude peut donc ajouter de la latence et de la consommation. Les abandons et résultats incomplets restent visibles. Les mesures sur ordinateur ne prédisent pas le coût sur téléphone.

Les autres modes de diagnostic restent disponibles, dont l’ancienne étude GTCRN. Le fonctionnement habituel n’active pas automatiquement Wiener.

## Calibration et changements de canal

Chaque nouvelle époque utilise trois secondes d’original. Le profil de bruit utilise les trames entièrement comprises entre 0,5 et 3 secondes ; le passage au filtré se fait sur 100 ms. Les échantillons avant 3 secondes sont conservés exactement. Le filtre finalise les données avec 256 à 511 échantillons de retard ; il n’invente pas de fin audio à l’arrêt.

Si FireRed indique de la parole pendant la calibration, celle-ci est marquée invalide. Ce garde-fou utilise aussi une probabilité de trame d’au moins 0,5, sans attendre l’activation lissée qui sert au découpage principal. Une nouvelle tentative démarre au retour au non-parole. Un changement effectif de canal ou de périphérique déclenche aussi une nouvelle calibration, y compris en mono. La parole très faible que FireRed manquerait peut toutefois contaminer un profil : ce garde-fou ne prouve pas que le bruit est sans voix.

Une fenêtre entière repasse sur l’original si elle commence avant la fin de calibration et de transition, traverse une époque, rencontre une calibration invalide ou la fin abandonnée d’un ancien filtre. Ses fichiers de paire `truth` et `asr` sont alors identiques ; aucun nouveau décodage filtré n’est demandé. Le résultat principal sert de référence. Les logs indiquent `startupBypass` et `bypassReason`.

Lors d’une remise à zéro, les échantillons réels encore en attente sont restitués en original, dans l’ordre, sans silence artificiel. Pour cette raison et pour les fenêtres en repli, le fichier `asr` continu et le fichier `asr` d’une paire peuvent différer : c’est explicite dans les logs. Il faut comparer les paires pour évaluer un segment.

## Vérifications réalisées avant livraison

- Suites Kotlin : segmentation, routage, DSP, calibration, réinitialisation et transport de révision du canal validés.
- Compilation de l’adaptateur contre les API Sherpa, et de la capture contre les signatures Android API 35, validée. **APK complet non compilé ici, téléphone non testé.**
- Intégration avec le vrai FireRed natif et un Qwen simulé : trois sorties principales identiques aux originaux, un rejet, quatre paires alignées, aucune publication depuis les trois décodages diagnostiques. Une rupture de capture invalide l’étude tandis que le chemin principal continue.
- Parité numérique : 15 cas, trois découpages de blocs chacun ; sortie indépendante du découpage. Contrôle supplémentaire sur les 13 enregistrements du corpus. Écart maximal par rapport au DSP Python : 7,45 × 10⁻⁹, sur une amplitude normalisée.
- Reconnaissance hôte réelle sur les 37 fenêtres du corpus : **21/24 phrases exactes acceptées** pour le port Kotlin, contre **23/24** dans l’étude Python et **22/24** pour l’original. Aucun des 13 négatifs n’a été accepté lors de ce contrôle. Il s’agit de fenêtres fixes avec calibration initiale supposée propre, pas de la sélection et des recalibrations sur téléphone.
- Sur les deux écarts supplémentaires de reconnaissance, une comparaison Python/Kotlin/Kotlin/Python retrouve les mêmes différences. Les minuscules écarts proviennent de l’arrondi de la magnitude complexe vectorisée NumPy ; les 37 paires donnent pourtant les mêmes échantillons après conversion PCM16. La parité numérique ne suffit donc pas à garantir la parité de transcription. Le score Python ne doit pas être attribué à cette version Android.
- Collecteur PowerShell : douze scénarios simulés validés, comprenant calibration invalide, mauvais modèle, étude invalide, export corrompu, trace de sélection corrompue, canal manquant, pertes de capture et flottants incomplets. Une fenêtre manquante invalide le bloc après récupération des fichiers ; une reconnaissance diagnostique non terminée reste un avertissement explicite.

Ces résultats justifient une expérience comparative, pas un remplacement en production ni une garantie d’absence de faux déclenchements. Les tests de reconnaissance concernent le corpus français disponible. Ils ne valident pas toutes les langues européennes ni tous les téléphones.

## Installation

Utiliser `installer-moteur-v3.4.6-wiener-study.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'`. L’installateur vérifie le ZIP et tous les fichiers du module puis sauvegarde le module précédent **à côté du projet**, avant remplacement. Les fichiers de l’application hors module restent inchangés. En cas de dossier verrouillé, fermer les programmes qui utilisent le module et arrêter Gradle avant de relancer.

Reconstruire et installer ensuite l’APK comme d’habitude, **sans désinstaller l’application**, pour conserver Qwen. Aucun nouveau modèle Wiener n’est nécessaire. Le collecteur vérifiera la révision `guardian-voice-v3.4.6-wiener-study`.

## Premier bloc à réaliser

Téléphone connecté en ADB, protection arrêtée au départ. Lancer uniquement cette commande, puis suivre les consignes affichées :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-wiener.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode WIENER_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Le script demande d’arrêter puis de redémarrer la protection. Attendre **ENREGISTREMENT ACTIF**, garder le silence et ne parler que lorsque **PARLER** apparaît. Le début silencieux permet la calibration. Lire chaque phrase une fois. Ne pas changer le téléphone de place pendant ce bloc.

Après ce premier essai : même commande avec un bruit stable sans voix, puis `-TestCase WORDS`, et un bloc `-TestCase SILENCE`. Garder les mêmes positions et préciser le bruit avec `-NoiseDescription`. Ne pas enchaîner avec 5 mètres avant d’avoir examiné les blocs à 3 mètres.

## Ce que le ZIP permet d’examiner

Tout reste sous `C:\Projets\guardian-voice\diagnostics-voix`. Le ZIP contient les logs et notes, le plan et ses marqueurs, les vues audio principales, les canaux réellement enregistrés avec `selection.tsv`, l’original et le Wiener continus et leurs paires, ainsi que les fichiers float32 nécessaires pour rejouer exactement le signal. Les voix alentour sont enregistrées pendant ce diagnostic volontaire.

Les événements renseignent la sélection et sa révision, les époques de calibration, les replis, les probabilités et durées positives de confirmation, le coût CPU/temps, la mémoire Java et les files d’attente. Les exports sont bornés à 180 secondes et 64 paires, avec files limitées. Un débordement invalide l’étude plutôt que de raccorder des signaux sans continuité.

Envoyer le ZIP annoncé. S’il est trop volumineux, `preparer-envoi-diagnostic.ps1` permet de le répartir ; conserver toutes les parties et le manifeste. Un fichier manquant ou une étude invalidée ne doit pas être interprété comme un silence réussi.
