# Guardian Voice v3.4 — FireRed streaming et Qwen local

Cette livraison remplace le prédécoupage Silero/GTCRN par **FireRed streaming →
confirmation FireRed non streaming → Qwen sur le PCM original → règles Guardian**.
Les modèles FireRed sont inclus dans le module. Qwen reste dans le stockage
privé de l'application, comme dans la version précédente.

## Comportement livré

- Capture 16 kHz, blocs demandés de 50 ms ; lectures Android plus courtes acceptées
  sans perte de l’état acoustique. Sélection des sources et micros existante conservée.
- État neuronal, fenêtre acoustique et historique FireRed conservés entre les blocs.
- Pré-roll 2 s, silence de fin et queue 1,2 s, plafond 8 s, overlap 400 ms,
  frontière de pause privilégiée au rollover.
- Gain max 64 limité par la crête sur les **copies FireRed seulement**.
  Aucun débruitage ni gain nouveau sur le PCM destiné à Qwen.
- Confirmation non streaming avant chaque fenêtre finale ; pas de décodage
  intermédiaire. Un rejet FireRed ne déclenche pas Qwen.
- GTCRN désactivé pour cette chaîne, sans attente d'une sortie asynchrone.
  Les anciennes classes et assets sont conservés pour compatibilité/recherche.
- Réinitialisation sur discontinuité ou saturation de capture ; erreur native
  explicite, sans repli silencieux sur RMS ou sur un autre détecteur.
- Diagnostics privés : raw, pregain, truth, vad, asr ; logs de route micro,
  décisions streaming, confirmations, âge de capture et saturation.

Les profils DISTANT et NOISY utilisent cette même architecture. Ils ne sont pas
deux modèles distincts et ne garantissent pas la reconnaissance dans le bruit.

## Installer dans le projet Windows

Ne pas coller le contenu du script ligne par ligne. Dans PowerShell :

```powershell
$guardianInstaller = Join-Path $env:TEMP 'installer-moteur-v3.4-firered-stream.ps1'
Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/cayeuxhugo-create/react-native-sherpa-onnx/codex/guardian-firered-stream-v34-20261023/installer-moteur-v3.4-firered-stream.ps1' -OutFile $guardianInstaller
powershell -NoProfile -ExecutionPolicy Bypass -File $guardianInstaller -ProjectDirectory 'C:\Projets\guardian-voice'
```

L'installateur vérifie le SHA-256 du ZIP et chaque fichier du manifeste, sauvegarde
l'ancien module **hors du projet**, puis remplace seulement
`modules/guardian-native-protection`. Les fichiers de l'application hors module
ne sont pas modifiés. Une interruption déclenche une tentative de restauration.
Si Windows refuse le déplacement du module, fermer les outils qui le verrouillent
avant de relancer ; l'installateur ne contourne pas les permissions Windows.

**Reconstruire ensuite l'APK avec le build Android habituel du projet**, puis
l'installer sur le téléphone. Une mise à jour JavaScript/Metro ne remplace pas les
bibliothèques natives. Cette livraison n'est pas un APK précompilé.
Privilégier une mise à jour de l'application : une désinstallation peut supprimer
le modèle Qwen privé. Si Qwen est annoncé indisponible, vérifier son installation
avec l'installateur Qwen déjà utilisé, sans retélécharger FireRed séparément.

## Nouveau test avec logs et WAV

Après installation du nouvel APK, téléphone visible par `adb devices` :

```powershell
$guardianTest = Join-Path $env:TEMP 'enregistrer-firered-stream.ps1'
Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/cayeuxhugo-create/react-native-sherpa-onnx/codex/guardian-firered-stream-v34-20261023/enregistrer-firered-stream.ps1' -OutFile $guardianTest
powershell -NoProfile -ExecutionPolicy Bypass -File $guardianTest -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile DISTANT -DurationSeconds 60
```

Le collecteur active temporairement les diagnostics privés, demande d'arrêter puis
redémarrer la protection et attend une session `guardian-voice-v3.4-firered-stream`,
`FIRERED_READY`, les WAV et des trames audio avant son chronomètre. Il restaure les
fichiers de contrôle et donne le chemin du ZIP, dans `diagnostics-voix` du projet.
L'enregistrement contient les voix alentour ; conserver ce ZIP privé.

Commencer par une période sans voix puis des phrases et mots courts à 1 m et 3 m.
Comparer ensuite à 5 m avec le téléphone dans la même position et le même niveau
de voix. Séparer un vrai silence, du bruit sans voix et une personne parlant
dans le bruit dans les notes du test. La sélection manuelle de route micro reste
disponible par `-CaptureRoute` pour des comparaisons ultérieures.

## Validation et limites

Sur ordinateur, la vraie JNI native, la politique Kotlin, le segmenter de
production et la confirmation native ont été rejoués sur les 24 entrées :
**15 492 décisions par bloc identiques au prototype ; 27 fenêtres admises identiques
octet pour octet**. Ces PCM correspondent aux résultats Qwen déjà vérifiés :
12/12 phrases, dont six à 5 m ; 0 fenêtre admise sur les 500 s sans voix ;
15/15 mots synthétiques admis, mais seulement 6/15 correctement reconnus.
Les résultats Qwen identiques sont réutilisés depuis le cache vérifié, pas
présentés comme 27 nouveaux décodages indépendants.

Le processeur asynchrone a aussi été testé avec la vraie JNI et un Qwen simulé
pour contrôler trois fenêtres PCM, leur ordre, la réutilisation des tableaux
de capture, l'absence de partial et l'absence d'attente GTCRN. Bibliothèques
Android recompilées pour arm64-v8a, armeabi-v7a, x86 et x86_64 ; alignement 16 Kio.
Tests de politique, découpage, concurrence, diagnostics et collecteur inclus.

**Pas d'APK complet compilé ici, ni de validation sur téléphone pour cette version.**
Le test réel doit vérifier le coût CPU, les pertes de trames, les micros et la
stabilité. Les mesures françaises ne prouvent pas la couverture de toutes les
langues européennes ; Qwen et sa configuration de langue actuelle restent
inchangés. Cette livraison ne résout pas encore la faiblesse des mots courts.

Synthèse : `modules/guardian-native-protection/tests/results/v3.4-streaming-summary.json`.
Source native : `android/src/main/cpp/firered/streaming.h`, JNI commune isolée de
l'ONNX Runtime Sherpa. Empreintes des modèles vérifiées au chargement.
