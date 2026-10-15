# Test réel : logs et WAV existants

Le collecteur fonctionne déjà avec les logs habituels du module v2. Pour
obtenir aussi les mesures structurées détaillées, installer le module de
l'archive **diagnostic**, ajouter `voiceDiagnosticsEnabled: true` à l'appel
`configureProtection`, puis reconstruire l'application Android.
Aucun réglage de reconnaissance n'est modifié par cette version diagnostic.

```ts
await GuardianNativeProtection.configureProtection({
  ...configurationExistante,
  voiceEnabled: true,
  voiceProfile: 'DISTANT', // puis NOISY pour la deuxième série
  voiceDiagnosticsEnabled: true,
});
```

Les paramètres sont appliqués au prochain démarrage complet de la protection.
Le script ne pilote pas la configuration de l'application. Il demande de
changer de profil au bon moment ; les logs indiquent le profil réellement
exécuté. Réutiliser la procédure d'arrêt/configuration/démarrage existante.

## PowerShell et adb

Télécharger `collect-guardian-logs.ps1` depuis la branche GitHub diagnostic.
Dans PowerShell, téléphone connecté et débogage USB autorisé :

```powershell
.\collect-guardian-logs.ps1 -IncludeWav
```

Le script essaie de reconnaître l'application dont l'identifiant contient
`guardian`. Sinon, il demande son identifiant Android. Pour l'indiquer :

```powershell
.\collect-guardian-logs.ps1 -IncludeWav -PackageName 'com.exemple.guardian'
```

L'identifiant réel se trouve dans `android/app/build.gradle` (`applicationId`)
ou dans les résultats de :

```powershell
adb shell pm list packages | Select-String guardian
```

Avec plusieurs appareils, ajouter `-DeviceId` et l'identifiant vu dans
`adb devices`. Aucun service adb n'est arrêté et aucun log système n'est effacé.

Le protocole comprend 8 cas : calme sans parole ; voix douce à 1, 3 et 5 m
en DISTANT ; bruit sans parole ; puis voix douce aux mêmes distances en NOISY.
Parler doucement, sans chuchoter pour cette première série. Garder le même
bruit de fond (sans voix) et l'orientation du téléphone ; noter tout changement.
Trois phrases sont affichées. Laisser trois secondes entre elles, puis huit
secondes après la dernière avant de terminer le cas. Si une phrase est dite
autrement, l'indiquer dans les notes. Les cas sans parole durent 30 secondes.

## Les WAV déjà présents dans votre code

`GuardianAudioEngine.startStereoDiagnosticCaptureIfNeeded()` enregistre :

```
/sdcard/Android/data/<package>/files/guardian-diagnostics/
  guardian-multimic-<date_en_millisecondes>-<selection>.wav
```

Il s'agit du **PCM16 stéréo brut**, avant prétraitement mono. Ce n'est pas une
copie de la sortie GTCRN ou du signal exact décodé par Qwen.
L'enregistrement existe seulement si :

- la capture est stéréo ;
- le mode diagnostic multi-micro est activé explicitement.

Le service v2 l'active quand `selectedMultiMic.enabled` et
`selectedMultiMic.explicitOverride` sont vrais. `guardian-multimic.txt` permet
déjà les overrides LEFT/RIGHT/MIX/etc. Le collecteur **ne crée et ne modifie
pas ce fichier**, afin de préserver votre route de capture. Ne pas imposer un
mode différent uniquement pour produire un WAV sans noter ce changement.
Si le mode actuel n'enregistre pas, les logs seuls sont conservés et le
collecteur le signale. Un ancien APK qui enregistrait déjà des WAV peut être
utilisé pour une première collecte, même sans les nouveaux logs structurés.

Démarrer le collecteur avant de démarrer la protection. Il ne récupère que
les WAV dont la session a commencé pendant la collecte. En fin de test,
**arrêter la protection avant la copie**, pour que le code finalise l'en-tête
RIFF. Le script copie par `adb pull` ; si nécessaire, il essaie `run-as` (APK
débogable requis), en conservant les octets binaires. Ne pas utiliser
`adb exec-out ... > fichier.wav` sous Windows PowerShell 5 : cela peut altérer
les octets.

Pour copier séparément des WAV plus anciens, après arrêt de la protection :

```powershell
$pkg = 'com.exemple.guardian' # votre identifiant réel
adb pull "/sdcard/Android/data/$pkg/files/guardian-diagnostics" '.\guardian-wav'
```

Aucune suppression de fichier sur le téléphone n'est effectuée.

## Ce que vous me renverrez

Un dossier et un ZIP `GuardianVoiceTest-...` sont créés sur le Bureau :

- `guardian.log` : logs sélectionnés et marqueurs BEGIN/END des cas ;
- `cases.csv` : phrase attendue, profil demandé, distance, horaires et notes ;
- `phone.json` : modèle, Android, architecture et horaires ;
- `wav/` et `wav-manifest.json` : WAV disponibles, validité de l'en-tête et SHA-256 ;
- `collection-summary.txt`, `adb-errors.log` et éventuellement `wav-errors.txt`.

Joindre ce ZIP dans la conversation. Le script n'envoie rien automatiquement.
Les logs contiennent les transcriptions ; les WAV peuvent contenir les sons
alentour. Utiliser la séance de test prévue, puis remettre
`voiceDiagnosticsEnabled: false` après les essais et arrêter tout diagnostic
WAV activé auparavant. Le script ne désactive pas vos overrides de capture.

## Mesures et limites

Tag `GuardianVoiceDiag`, JSON schéma 1 : session et profil, horloge UTC et
monotone, niveau avant gain, estimation du plancher de bruit, gain, niveau et
pic de la voie retenue, transitions VAD, retard d'entrée, pertes, GTCRN
réellement utilisé, segments, attentes/durées Qwen, résultats admissibles,
publications réelles, texte après timeline et réinitialisations.

Les mesures audio sont échantillonnées toutes les 250 ms et à chaque
transition VAD ; les transitions et segments sont toujours tracés. Le
`estimatedSnrDb` est l'estimation du code à partir du plancher de bruit,
pas une mesure acoustique étalonnée. Les textes sont limités à 512 caractères.
Un `QWEN_RESULT` n'implique pas une publication : chercher `QWEN_PUBLISH`, puis
`PRODUCT_TEXT`. Le script compte les nouveaux logs et avertit s'ils manquent.
Il ne conclut pas automatiquement à une reconnaissance correcte.

Le collecteur a été testé sous PowerShell 7 sur Linux avec adb simulé : 8 cas,
16 marqueurs, exclusion d'un ancien WAV, repli run-as binaire identique et
ZIP valide. Sa syntaxe reste compatible PowerShell 5.1. Aucun téléphone
Windows/Android n'était connecté ici ; le test réel reste nécessaire.
