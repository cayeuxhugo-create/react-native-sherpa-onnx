# Moteur vocal v3 — installation et test à 1 m

Cette version contient des corrections du moteur et le diagnostic mono.
Commencer par vérifier la voix douce à **1 m**, dans le calme puis avec bruit.
Les essais à 3 m et 5 m viennent après cette première validation.

## Installation

1. Sauvegarder le projet puis remplacer `modules/guardian-native-protection`
   par le dossier de cette archive. Les dossiers `android/build` ne sont pas
   fournis : ils seront recréés par la compilation.
2. Conserver les modèles Qwen, Firebase et l'AAR Sherpa de l'application.
   Le module référence toujours
   `android/app/libs/sherpa-onnx-1.13.8-gv3895-2.aar`.
   Les modèles Silero VAD et GTCRN et leurs licences sont inclus dans le module.
3. Ajouter `voiceDiagnosticsEnabled: true` à votre appel existant de
   `configureProtection` pendant les essais :

```ts
await GuardianNativeProtection.configureProtection({
  ...configurationExistante,
  voiceEnabled: true,
  voiceProfile: 'DISTANT',
  voiceDiagnosticsEnabled: true,
});
```

4. Reconstruire et réinstaller l'application Android avec votre commande
   Expo/Gradle habituelle. Un rechargement JavaScript seul ne suffit pas.
   Ce ZIP contient les sources du module, pas un APK prêt à installer.

## Lancer le test

Téléphone branché, débogage USB et adb autorisés, lancer le script du ZIP :

```powershell
.\collect-guardian-logs.ps1 -IncludeWav
```

Le script détecte le package Guardian ou le demande. On peut préciser
`-PackageName 'com.exemple.guardian'`. Avec plusieurs téléphones, ajouter
`-DeviceId`. Il propose quatre cas :

- calme sans aucune personne qui parle, pendant 30 secondes ;
- trois phrases en voix douce à 1 m dans le calme ;
- bruit sans parole, pendant 30 secondes ;
- les mêmes phrases à 1 m avec le même bruit.

Lire les phrases affichées sans chuchoter, avec trois secondes entre elles.
Attendre huit secondes après la dernière phrase avant de terminer le cas.
Noter tout changement de phrase, bruit, orientation ou personne qui parle.
Un ventilateur convient mieux qu'une télévision pour le premier bruit de test.
Le script signale les cas terminés en moins de huit secondes.

À chaque changement de profil, le script prépare les réglages du test et
vous demande d'arrêter complètement puis de redémarrer la protection.
Il vérifie la révision native `guardian-voice-v3`, le profil demandé, une
nouvelle session et des trames audio. Avec -IncludeWav, il exige également
l'ouverture du diagnostic WAV et refuse les erreurs d'enregistrement connues.
Un ancien APK ou des diagnostics désactivés arrêtent le protocole avant les cas.

En fin de test, arrêter la protection pour finaliser les WAV, puis valider
la copie. Le ZIP `GuardianVoiceTest-...` sur le Bureau contient les résultats
à renvoyer dans la conversation. Rien n'est envoyé automatiquement.
Ajouter `-Full` pour retrouver les huit cas à 1, 3 et 5 m une fois le test
court satisfaisant. Les WAV sont limités à trois minutes par profil/session.

## Réglages temporaires et enregistrements

Le script prépare `guardian-voice-profile.txt` (DISTANT puis NOISY) et
`guardian-record-wav.txt` (ON avec -IncludeWav) dans le dossier externe de
l'application. Le module ne les lit qu'au démarrage et uniquement lorsque
`voiceDiagnosticsEnabled` est vrai. Les fichiers préexistants sont sauvegardés
puis restaurés ; les fichiers créés uniquement pour le test sont retirés.
Ne pas fermer brutalement PowerShell pendant le test. Toute erreur de
restauration affiche le chemin concerné. Les WAV ne sont pas supprimés.

Les nouveaux exports fonctionnent en mono sans imposer une capture stéréo :

- `pregain.wav` : tampon detectorSamples avant gain ;
- `truth.wav` : tampon principal après gain ;
- `asr.wav` : signal original retenu pour les fenêtres Qwen ;
- `vad.wav` : seconde vue disponible pour le soutien VAD en NOISY ;
- `index.tsv` : numéros de trames, temps de capture et positions des blocs.

Les WAV sont PCM16 mono 16 kHz. La conversion borne les valeurs à [-1,1]
sans modifier les tableaux flottants du moteur. L'index permet de repérer
les trames manquantes : la durée seule du fichier ne suffit pas.
Dans DISTANT, les voies asr et vad coïncident. La vue vad est enregistrée
avant l'amplification réservée au modèle VAD.
La copie disque ajoute une charge facultative, surveillée dans les logs.

Les fichiers récents de `files/guardian-diagnostics` sont copiés par adb pull,
ou par flux binaire run-as si l'APK est débogable. Les anciens exports stéréo
restent récupérables. Le script ne change pas `guardian-multimic.txt`.
Les en-têtes et sommes SHA-256 sont enregistrés dans le manifeste.
Le ZIP contient les voix et transcriptions du test : utiliser une séance
prévue à cet effet. Après les essais, remettre `voiceDiagnosticsEnabled: false`
et arrêter/redémarrer la protection.

Voir `CORRECTIONS-V3.md` pour les changements et les résultats mesurés.
Aucun vrai téléphone Android ni Windows n'était disponible dans cet environnement.
