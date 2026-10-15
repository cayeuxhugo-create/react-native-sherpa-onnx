# Test vocal : diagnostic r2

Cette version ajoute des enregistrements mono facultatifs et une verification du
profil reel. Les algorithmes de reconnaissance sont ceux du moteur v2 ; ce
paquet sert a comprendre les pertes de parole avant de les regler.

## Installation obligatoire

Copier `modules/guardian-native-protection` de cette archive dans votre projet,
a la place du module precedent. Conserver une sauvegarde de votre projet.
Dans votre appel existant a `configureProtection`, ajouter :

```ts
voiceDiagnosticsEnabled: true,
```

Reconstruire et reinstaller l'application Android avec votre commande habituelle.
Un rechargement JavaScript seul ne suffit pas : le code natif a change.
Ce paquet contient les sources du module, pas un APK pret a installer.

## Test PowerShell

Telephone branche, adb autorise, lancer le nouveau script :

```powershell
.\collect-guardian-logs.ps1 -IncludeWav
```

Le script detecte le package Guardian ou le demande. On peut preciser
`-PackageName 'com.exemple.guardian'`. Avec plusieurs telephones, ajouter
`-DeviceId`. Il utilise quatre cas par defaut : calme sans aucune personne qui
parle, voix douce a 5 m dans le calme, bruit sans parole, voix douce a 5 m avec
le meme bruit. Ajouter `-Full` pour inclure aussi les distances 1 m et 3 m.

Le script prepare `guardian-voice-profile.txt` (DISTANT puis NOISY) et
`guardian-record-wav.txt` (ON avec -IncludeWav) dans le dossier externe de
l'application. Ces fichiers sont lus uniquement au demarrage du moteur et
uniquement si `voiceDiagnosticsEnabled` est vrai. A chaque demande, arreter
completement puis redemarrer la protection. Le script attend une nouvelle
SESSION_START au bon profil et des AUDIO_FRAME ; avec -IncludeWav il exige
WAV_READY. Sans ces preuves il s'arrete et conserve les logs dans un ZIP.

Les fichiers de controle presents sont sauvegardes puis restaures ; les fichiers
crees uniquement pour le test sont retires a la fin. Les WAV ne sont pas effaces.
Si une restauration echoue, le script affiche le chemin a restaurer. Ne pas
fermer brutalement PowerShell pendant le test. Apres le test, remettre
`voiceDiagnosticsEnabled: false` et arreter/redemarrer la protection.

## Consignes de parole

Chaque cas sans parole dure 30 secondes. Personne ne doit parler, meme loin,
et aucune television ou radio ne doit diffuser de voix. Utiliser par exemple
un ventilateur pour le bruit. Pour les cas avec parole, lire les trois phrases
affichees doucement, sans chuchoter, avec trois secondes entre elles. Attendre
huit secondes apres la derniere phrase avant de terminer le cas. Noter toute
phrase differente, personne qui parle ou changement de bruit/orientation.
Un cas termine en moins de huit secondes est signale comme trop court.

## Audio recupere

Arreter la protection a la fin pour finaliser les en-tetes WAV. Le collecteur
copie les nouveaux fichiers de `files/guardian-diagnostics`, par adb pull ou
run-as binaire si l'APK est debogable. Il ne force pas la capture stereo.

Trois voies PCM16 mono 16 kHz, limitees chacune a 180 secondes par session :

- `pregain.wav` : copie du tampon detectorSamples fourni par le moteur audio ;
- `truth.wav` : copie du tampon principal avec son gain actuel ;
- `asr.wav` : signal retenu par le routeur, avant VAD et decoupage Qwen.

Le fichier `index.tsv` associe chaque bloc au numero de trame, a l'horloge de
capture et a sa position dans le WAV. Des pertes de trames peuvent raccourcir
le fichier ; utiliser cet index, pas seulement la duree du WAV. Le PCM16 est
une conversion du signal flottant ; il est borne a [-1,1] sans modifier le
signal transmis au moteur. La copie disque peut ajouter une charge ; les logs
permettent de surveiller files d'attente et pertes. Pour -Full, garder chaque
profil sous trois minutes ou les enregistrements seront limites.
Les anciens WAV stereo restent recuperables sans modifier guardian-multimic.txt.

Le ZIP sur le Bureau contient logs, cas, informations du telephone, WAV et
index disponibles, en-tetes et sommes SHA-256. Il contient les voix et les
transcriptions du test. Rien n'est envoye automatiquement.

## Validation realisee ici

Tests Kotlin du routeur/decoupage : 14 ; concurrence du decodeur : 4 ; traces : 4.
Le test WAV verifie trois voies, format PCM, bornage, limite, invalides,
index temporel, integrite du tampon source et finalisation.
Compilation des adaptateurs avec API Sherpa v1.13.8 et stubs Android reussie.
Collecteur execute sous PowerShell 7 avec adb simule : profils verifies,
4 cas, copie binaire et index, exclusion des anciens WAV, restauration des
fichiers de controle, ZIP valide. Un essai sans diagnostics doit s'arreter
avant les cas et restaurer les fichiers. Syntaxe prevue pour PowerShell 5.1 ;
pas de test sur un vrai telephone ou sous Windows ici. Pas de compilation
complete de l'application Android : le projet applicatif complet est requis.
