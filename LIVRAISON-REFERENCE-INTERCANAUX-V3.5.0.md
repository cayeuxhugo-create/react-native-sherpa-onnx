# Guardian Voice v3.5.0 : étude du bruit commun aux canaux

Cette version ajoute le prototype spectral intercanaux aux sources Android/Kotlin. Les modèles et bibliothèques natifs de la v3.4.9 sont conservés. Les règles de publication de la voie originale ne changent pas.

Le choix de Wiener et les limites sont définis dans `ARCHITECTURE-MOTEUR-VOIX.md`. Le mono decision-directed adaptatif reste disponible avec `WIENER_STUDY`. Le nouveau `REFERENCE_NOISE_STUDY` évalue le résidu de Wiener intercanaux ; il ne cumule pas les deux traitements et ne charge pas GTCRN.

Les trois phrases retrouvées dans le meilleur rejeu sont une preuve de potentiel du signal, pas un moteur complet validé : FSMN refuse toujours la première. Cette version doit rendre ce refus et le texte Qwen forcé visibles ensemble. Les résultats diagnostiques ne sont jamais des alertes.

## Code principal

- `GuardianReferenceNoiseEnhancer.kt` : DSP continu, deux profils fixes, échantillons finalisés sans flush artificiel.
- `GuardianReferenceNoiseStudy.kt` : paire de canaux stable, exports alignés, fenêtres exactes et contexte réel de deux secondes, validations séparées et ASR diagnostique forcé.
- `GuardianQwenPrimaryAudioProcessor.kt` : raccordement opt-in, sans remplacement de la décision originale.
- `GuardianProtectionService.kt` : mode, révision et métadonnées explicites.
- `enregistrer-guardian-reference.ps1` : collecte, contrôles d'intégrité et comparaison des tranches aux flux continus.

Le port Kotlin a été comparé aux sorties du prototype sur trois captures : les six flux sont identiques, à un écart maximal d'environ 4,55×10⁻¹³ près sur un flux. Ces mesures hôte ne remplacent pas la validation Android.

## Installation et essai

Exécuter l'installateur fourni, puis reconstruire l'APK avec la procédure Android habituelle. Installer par-dessus l'application existante pour conserver Qwen. Le correctif natif CPU série de la v3.4.9 reste inclus.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.5.0-reference-noise-study.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

Pour reprendre exactement l'essai à 3 m sous aspirateur :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-reference.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode REFERENCE_NOISE_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'aspirateur constant'
```

L'aspirateur doit déjà fonctionner avant la capture. Garder le téléphone immobile et ne pas parler avant la première consigne. Les premières secondes servent au profil de bruit long. Prononcer chaque phrase même si aucune transcription n'apparaît, arrêter proprement la protection à la fin puis attendre le ZIP.

Le script demande les conditions réellement observées pendant le préfixe, sans conclure au silence à partir du seul VAD. Une voie mono est une limitation de capacité mesurée, pas une erreur de build. Une extension dépassant huit secondes, un changement de route ou la fin d'enregistrement peuvent empêcher une comparaison ; le statut et les fichiers disponibles sont conservés.

Les données incluent les originaux de la paire, les deux résidus et leurs index. `truth` représente le canal cible, `raw` la référence, `pregain` le résidu court et `asr` le résidu long dans le dossier reference-study ; ces noms de fichiers sont des alias documentés dans reference-files.csv. Les WAV principaux du dossier wav gardent leur sens habituel. Les autres canaux disponibles restent dans adaptive-channels.

Les deux variantes LONG peuvent être proposées au Qwen diagnostique même si FireRed ou FSMN les refuse. Lire ensemble filteredCombinedAccepted, diagnosticForced et productionEligible=false. Un refus de mise en file n'est pas du silence reconnu. Le décodeur demeure partagé et peut décliner un diagnostic sous charge.

Les tests, la compilation et les replays effectués sont consignés dans `modules/guardian-native-protection/tests/results/v3.5.0-reference-noise-study-summary.json`. L'APK complet et la nouvelle voie sur appareil n'ont pas été validés dans l'environnement hôte.
