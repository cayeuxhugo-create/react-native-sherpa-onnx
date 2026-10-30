# Guardian Voice v3.5.4 — secours temporel pour le candidat LONG

Cette livraison cible une vraie phrase presente dans le WAV filtre mais refusee
avant Qwen par la confirmation FSMN. Le secours reste limite au mode explicite
`ADAPTIVE_CANDIDATE`. Une phrase deja admise par le chemin original conserve son
chemin ; la nouvelle contrainte temporelle ne devient pas une duree minimale
globale pour les mots courts.

Le secours exige une calibration propre, des sources completes et synchrones,
un canal et une route stables, puis une admission FireRed sur LONG et un refus
FSMN effectivement evalue. Une vue GTCRN continue a gain unitaire apporte une
confirmation supplementaire : FireRed, FSMN et un noyau vocal temporel doivent
tous l'accepter. Une inference absente, un changement de route ou une erreur
ne permettent pas de contourner les gardes.

Le noyau conserve les ilots de 15 trames FSMN positives consecutives au minimum,
fusionne les ilots separes d'au plus 120 trames et exige dans un meme groupe
100 trames positives et une densite de 25 % au minimum. Le total de trames
positives dispersees dans une fenetre ne suffit pas.

GTCRN sert a confirmer la presence de parole. L'audio fourni a Qwen reste LONG,
avec son empreinte enregistree. Aucun mot attendu, correction par phrase connue
ou liste de textes parasites n'intervient dans l'admission.

## Installation Windows

Le script `installer-guardian-v354.ps1` accepte `-SourceRef` avec les 40 caracteres
du commit de livraison indique dans le message de livraison. Il telecharge les
outils et le ZIP dans un dossier temporaire, verifie leurs empreintes avant de
remplacer les outils locaux, puis lance l'installateur avec le chemin explicite
de l'archive. Un echec de telechargement ou d'integrite conserve les outils
existants. Il ne lance pas la compilation Android.

Placer l'installateur et l'archive verifies dans le meme dossier, puis executer :

```powershell
$outils = Join-Path $env:USERPROFILE 'Guardian-v3.5.4'
powershell -NoProfile -ExecutionPolicy Bypass `
    -File "$outils\installer-moteur-v3.5.4-temporal-rescue.ps1" `
    -ProjectDirectory 'C:\Projets\guardian-voice'
if ($LASTEXITCODE -ne 0) { throw 'Installation interrompue.' }
```

L'installateur verifie le ZIP et tous les fichiers du module. Il sauvegarde
l'ancien module a cote du projet et restaure cette sauvegarde si le remplacement
echoue. Les modeles Qwen deja presents dans les donnees Android ne sont pas
reinstalles. L'archive contient les sources du module, pas un APK compile.

Reconstruire et installer l'application avec la methode et la signature habituelles.
Si le projet utilise la commande Expo locale deja documentee :

```powershell
Set-Location 'C:\Projets\guardian-voice'
npx expo run:android --device
if ($LASTEXITCODE -ne 0) { throw 'Compilation ou installation Android interrompue.' }
```

Conserver l'application et ses donnees pendant cette mise a jour. Le collecteur
verifie la revision du moteur effectivement executee ; remplacer les sources
sans reconstruire l'APK ne satisfait pas ce controle.

## Deux essais reels

Le lanceur exige `guardian-voice-v3.5.4-temporal-rescue`, le secours temporel
initialise et la route AUTO de comparaison : source MIC=1, quatre canaux Android,
masque 15. Ces quatre canaux ne garantissent pas quatre microphones physiques
exploitables. Une route differente interrompt le test et conserve les diagnostics.

```powershell
$outils = Join-Path $env:USERPROFILE 'Guardian-v3.5.4'
powershell -NoProfile -ExecutionPolicy Bypass `
    -File "$outils\tester-guardian-secours-temporel.ps1" `
    -ProjectDirectory 'C:\Projets\guardian-voice' -Scenario 3M_BRUIT
```

Allumer le bruit avant le demarrage et le maintenir pendant la prise. Garder le
telephone et le bruit aux memes emplacements que lors de la comparaison.
Garder les vingt premieres secondes sans parole, prononcer chaque phrase affichee
une seule fois au signal, puis rester silencieux jusqu'a la fin. Decrire tout
ecart dans l'observation finale et arreter completement la protection quand le
collecteur le demande pour fermer les WAV.

Recommencer ensuite au calme :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File "$outils\tester-guardian-secours-temporel.ps1" `
    -ProjectDirectory 'C:\Projets\guardian-voice' -Scenario 5M_CALME
```

Un controle supplementaire sans aucune parole est disponible avec
`-Scenario SILENCE_BRUIT`. Les trois scenarios durent 85 secondes hors fermeture
des fichiers. Le niveau de voix declare reste `SOFT` pour conserver le protocole
precedent ; utiliser `-VoiceLevel NORMAL` si cela decrit mieux la voix reelle.

## Preuves conservees

Chaque essai produit son ZIP, puis le decoupe pour l'envoi si necessaire.
Le lanceur conserve les archives precedentes et prepare aussi une collecte
marquee en erreur. Envoyer toutes les parties et le `repartition.json` du meme
dossier, avec le scenario et les incidents observes. Aucun envoi automatique
n'est effectue.

- Les canaux de capture, leur selection et les WAV continus restent disponibles.
- `adaptive-asr` contient l'original et le PCM effectivement fourni a Qwen.
- `adaptive-rescue` contient, pour chaque confirmation executee, l'original et
  la vue GTCRN de confirmation, y compris lorsqu'elle est refusee. Dans ce dossier,
  le suffixe `asr` des fichiers designe la seconde vue exportee ; ce n'est pas
  l'audio fourni a Qwen.
- `TEMPORAL_RESCUE_RESULT` associe bornes, canal, route, confirmations, noyau
  temporel et empreinte exacte du PCM. Le collecteur controle ces preuves et
  l'identite des entrees Qwen avant de valider la collecte.

## Validation avant l'essai Android

Sur la capture AUTO la plus recente, le rejeu des composants de production
avec les vrais modeles CPU transmet maintenant trois fenetres a Qwen, contre
deux avant le correctif. Les trois transcriptions sont exactes, sans fenetre
ASR supplementaire. Les PCM des deux phrases deja admises restent identiques ;
le secours recupere la phrase auparavant bloquee sans remplacer leur audio.
Ce resultat vient du rejeu de la meme capture, pas d'un nouvel essai physique.

Les douze scenarios natifs passent : captures reelles disponibles, rupture de
route, perte de reference, quatre contre-epreuves synthetiques totalisant
248 secondes et panne GTCRN injectee. Cette panne preserve les admissions
ordinaires sans reinitialiser tout le pipeline. Le precedent essai calme
conserve ses entrees ASR bit pour bit. L'ancien essai bruyant a deux canaux
reste a deux phrases admises sur trois : sa deuxieme phrase n'est pas recuperee
par ce secours et toutes les erreurs des anciennes captures ne sont pas corrigees.
La suite hote, la compilation contre l'API Sherpa publique, les controles des
exports et les tests PowerShell d'installation/collecte completent ces rejeux.

Les seuils acoustiques ont ete confrontes a 52 fenetres issues de cinq captures :
28 fenetres de parole admises et 24 fenetres parasites refusees. Certaines se
recouvrent ; ce ne sont pas 52 observations independantes et aucun corpus
independant n'a valide un taux de fiabilite general. Une confirmation acoustique
positive ne garantit pas des mots exacts.

L'APK Android complet n'a pas ete compile dans cet environnement. L'execution
du nouvel adaptateur natif, le rejet des activations parasites et le cout du
traitement continu restent a verifier sur le telephone. Le mode normal reste
`ORIGINAL` ; le secours est active dans le candidat de test explicite. Les resultats
de validation logicielle sont consignes dans
`modules/guardian-native-protection/tests/results/v3.5.4-temporal-rescue-summary.json`.
Les voix et transcriptions privees restent hors de cette livraison.
