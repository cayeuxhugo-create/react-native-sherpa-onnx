# Organisation du module Guardian

Ce module contient le moteur natif Android et son pont Expo. La présente réorganisation conserve les packages/classes et les octets des sources Kotlin déplacées. Elle ne change pas les règles de reconnaissance, les seuils ni les modèles.

## Où lire le code

Les chemins suivants sont relatifs à `android/src/main/java/expo/modules/guardiannativeprotection/` :

| Dossier | Responsabilité | Points d’entrée utiles |
|---|---|---|
| racine | Pont Expo | GuardianNativeProtectionModule, GuardianNativeProtectionView |
| core/ | Service Android et identité | GuardianProtectionService, GuardianIdentityManager |
| core/config/ | Types de configuration | GuardianNativeConfig |
| core/events/ | Types d’événements communs | GuardianNativeEvent |
| network/ | Authentification, encodage et envoi | GuardianApiClient, GuardianUploadWorker, GuardianUploadScheduler |
| persistence/ | Persistance des données et de la configuration | GuardianLocationStore, GuardianNativeConfigStore |
| location/ | Localisation | GuardianLocationEngine |
| pti/ | Protection travailleur isolé | GuardianPtiEngine |
| voice/capture/ | Capture, routage et vues des canaux | GuardianAudioEngine, GuardianAudioCapabilities |
| voice/vad/ | Détection et confirmation de présence de parole | GuardianFireRedStreaming, GuardianFsmnConfirmation |
| voice/enhancement/ | Débruitage et traitement du signal | GuardianReferenceNoiseEnhancer, GuardianGtcrnEnhancer |
| voice/asr/ | Entrées et ordonnanceur de reconnaissance | GuardianQwenPrimaryAudioProcessor, GuardianQwenProgressiveDecoder |
| voice/segmentation/ | Découpage et assemblage de la parole | GuardianSpeechSegmenter, GuardianWordTimelineEngine |
| voice/decision/ | Compréhension et décision vocale | GuardianVoiceEngine, GuardianVoiceUnderstandingEngine, GuardianVoiceFusionEngine |
| voice/delivery/ | Réessais et journal de secours des alertes | GuardianEventDelivery, GuardianVoiceEventJournal |
| voice/diagnostics/ | Exports, traces et modes d’étude | GuardianVoiceDiagnostics, GuardianReferenceNoiseStudy |

Les dossiers organisent les sources. Le package Kotlin reste `expo.modules.guardiannativeprotection` pour préserver l’autolinking Expo, les noms JNI et les consommateurs existants. Ne pas déduire un nouveau nom de package d’un déplacement de fichier. Gradle compile récursivement l’arbre de sources. Le manifeste Android et expo-module.config.json conservent leurs classes d’entrée.

## Parcours de lecture du moteur vocal

1. `core/GuardianProtectionService.kt` initialise et raccorde les composants.
2. `voice/capture/GuardianAudioEngine.kt` fournit les trames et les canaux.
3. `voice/asr/GuardianQwenPrimaryAudioProcessor.kt` orchestre VAD, fenêtres, contrôles acoustiques et envoi au décodeur.
4. `voice/asr/GuardianQwenProgressiveDecoder.kt` gère la file, les générations et les callbacks de Qwen.
5. `voice/segmentation/` conserve les limites acoustiques, mots et unités de conversation.
6. `voice/decision/` décide du sens des mots ; `voice/delivery/`, `persistence/` et `network/` prennent en charge les événements.

Ce classement n’est pas une extraction des grandes classes en nouveaux sous-composants : les gros fichiers AudioEngine et ProtectionService restent inchangés dans cette livraison. Leur découpage devra être réalisé séparément, avec tests de cycle de vie et de concurrence, pour éviter de mélanger une réorganisation de dossiers et une modification de comportement.

## Nettoyage réalisé

Deux programmes de simulation (`GuardianLexicalV31Simulation.kt` et `GuardianSpeechRescueRingSimulation.kt`) ont été retirés de l’arbre de production et placés dans `tests/host/legacy/`, sans modification de contenu. Ils restent disponibles pour les tests et ne sont plus inclus comme sources du module Android.

Les modèles, licences, bibliothèques par ABI, scripts et résultats historiques n’ont pas été déclarés inutiles à partir de leur seul nom. Le fichier `GuardianManualSosEvent.kt` était entièrement vide et a été supprimé. Aucun code métier n’a été supprimé. Les fichiers de dépendances installées (`node_modules`) ne doivent pas être inclus dans les archives de sources.

## Tests et traçabilité

`SOURCE-LAYOUT.json` contient les anciens/nouveaux chemins et le SHA-256 de chaque fichier déplacé. Le script `tests/check-source-layout.py` vérifie les déplacements, les packages, l’absence des simulations dans main et le contrat Expo. Les scripts de compilation hôte ont été adaptés aux nouveaux chemins ; les listes dynamiques utilisent `tests/kotlin-sources.sh`.

Les suites restent accessibles par `tests/run-host-tests.sh`, `tests/run-voice-runtime-tests.sh`, `tests/run-legacy-simulations.sh` et `tests/check-reference-api.sh`. Java/Kotlin et les dépendances de test doivent être préparés comme auparavant. La compilation APK et l’autolinking effectif devront être confirmés dans le projet Expo complet sur votre poste.
