# Récupération d'un enregistrement Guardian existant

`recuperer-guardian-wav.ps1` est un outil de lecture ADB indépendant du moteur et
du collecteur. Il utilise `session.json` dans le dossier du test pour retrouver
les cinq WAV privés et l'index, sans lancer d'enregistrement ni reconstruire l'APK.

Arrêter la protection puis fermer l'application avant la récupération. Si l'arrêt
normal ne termine pas la capture, `adb shell am force-stop <package>` peut figer les
fichiers. Cette commande arrête aussi la protection Guardian. Elle ne désinstalle
pas l'application et ne supprime pas Qwen ni les fichiers enregistrés.

Exemple :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\recuperer-guardian-wav.ps1" -SessionDirectory 'C:\Projets\guardian-voice\diagnostics-voix\Enregistrement-...'
```

Le script vérifie le SHA-256 distant avant et après transfert, puis celui de la
copie locale. Les flux binaires ne passent pas dans une redirection texte PowerShell.
Un fichier qui change encore est refusé ; les autres fichiers restent récupérables.

Les WAV PCM16 mono 16 kHz au format exact du writer Guardian peuvent avoir conservé
un en-tête de longueur nulle après une interruption. Le script reconstruit uniquement
les deux champs de longueur sur la copie PC. Il compare le SHA-256 des échantillons
avant et après et conserve l'ancien en-tête dans le rapport de récupération.
Il ne modifie pas les échantillons, n'amplifie pas le son et ne répare pas les originaux.
Un en-tête réparé ne prouve pas que l'ensemble du test a été enregistré. Après un
arrêt forcé, la fin de l'index peut également être incomplète.

Chaque tentative crée un nouveau sous-dossier `wav-recuperes-...`, son rapport
`recuperation.json`, puis un nouveau ZIP à côté du dossier du test. Les logs et les
métadonnées d'origine sont conservés. Si certains fichiers manquent, un ZIP partiel
est tout de même créé et explicitement marqué incomplet. Aucune suppression ou
écriture sur le téléphone n'est effectuée par ce script.

Validation sous PowerShell 7.4.6 avec ADB simulé : six scénarios (fichiers valides,
en-têtes non finalisés, transfert corrompu, fichier absent, fichier en croissance,
format inattendu). Les contrôles vérifient l'intégrité binaire, la préservation des
échantillons et des originaux, et les états complet/partiel du ZIP. Le script cible
également PowerShell Windows 5.1 ; son exécution sur le téléphone reste à effectuer.
