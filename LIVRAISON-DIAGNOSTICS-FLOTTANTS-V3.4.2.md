# Guardian Voice v3.4.2 — diagnostics flottants et tests annotés

## Ce qui change

Les cinq étapes audio principales et les deux étapes GTCRN enregistrent désormais, à côté des WAV d'écoute en 16 bits, le signal exact en Float32. Le format `.f32` est IEEE-754 binary32, little-endian, mono, 16 000 Hz, sans en-tête : 4 octets par échantillon. Les valeurs ne sont ni normalisées ni limitées par l'export flottant. Le même index TSV décrit les échantillons des deux formats. Les très faibles signaux restent représentables ; l'export ne reconstitue pas une information déjà perdue par la capture matérielle.

FireRed, les seuils, les durées de découpage et Qwen restent identiques. GTCRN reste une voie de comparaison NOISY, sans texte publié ni décision d'alerte. Les exports n'existent que lors des diagnostics avec enregistrement audio activé. Sept fichiers flottants s'ajoutent aux sept WAV pour un test NOISY. La limite reste 180 secondes par étape ; l'export flottant ajoute jusqu'à environ 77 Mio pour sept étapes de 180 secondes. Arrêter la protection à la fin du test.

Le collecteur `enregistrer-guardian-float.ps1` exige le nouvel APK v3.4.2, vérifie les empreintes, les tailles et la correspondance des longueurs Float32/WAV. Une erreur d'export invalide le bloc tout en conservant les fichiers disponibles.

## Installer

Exécuter `installer-moteur-v3.4.2-float-diagnostics.ps1` avec `-ProjectDirectory 'C:\Projets\guardian-voice'`. Il sauvegarde le module existant à l'extérieur du projet et vérifie le manifeste. Reconstruire ensuite Android avec la procédure habituelle et installer le nouvel APK ; l'installateur ne compile pas l'application. Vérifier que Qwen est disponible. Aucun téléchargement de modèle supplémentaire n'est requis par ces diagnostics.

Télécharger également `enregistrer-guardian-float.ps1` dans `$env:TEMP` depuis le même commit de livraison. Vérifier `adb devices` (état `device`) et `adb shell run-as com.hugo3154.guardianvoice id`.

## Déroulement commun

Le téléphone reste immobile, microphone dégagé, même orientation. Garder la même source : `VOICE_RECOGNITION_MONO`, valeur par défaut du nouveau collecteur. Ce choix stabilise le test ; il n'est pas présenté comme la meilleure route pour tous les téléphones. Le collecteur vérifie la route effectivement appliquée. Il restaure les réglages antérieurs après le test.

Chaque commande prépare un seul enregistrement. Arrêter complètement puis redémarrer la protection au moment demandé, puis Entrée. Attendre « ENREGISTREMENT ACTIF ». Lire uniquement les consignes « PARLER » affichées à l'écran du PC ; aucun bip ni voix synthétique n'est émis. Observer l'écran à distance, par exemple avec une autre personne qui reste silencieuse ; ne pas déplacer le téléphone pour lire. Sinon utiliser une distance permettant de lire le PC et noter toute difficulté.

À la fin du minuteur, arrêter immédiatement la protection puis Entrée pour finaliser les fichiers. Noter ensuite les paroles réellement prononcées et toute consigne manquée. Écrire « conforme » seulement si toutes les consignes ont été suivies. Les marqueurs indiquent les invitations à parler, pas une preuve acoustique ni une annotation automatique du début exact des mots.

Ne pas lancer deux collecteurs à la fois. Attendre le ZIP et la restauration des réglages avant le suivant. Éviter les notifications et autres voix pendant les blocs. Ces premières consignes n'incluent pas de phrase d'urgence susceptible de lancer un appel réel.

## Les cinq blocs de départ

Dans PowerShell, définir ces arguments communs :

```powershell
$collecteur = "$env:TEMP\enregistrer-guardian-float.ps1"
$commun = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $collecteur, '-ProjectDirectory', 'C:\Projets\guardian-voice', '-PackageName', 'com.hugo3154.guardianvoice', '-Profile', 'NOISY', '-CaptureRoute', 'VOICE_RECOGNITION_MONO')
```

Exécuter les commandes suivantes **une par une**, en attendant la fin du bloc précédent.

1. Silence calme, 120 s : aucun mot ni manipulation.

```powershell
powershell @commun -TestCase SILENCE -DurationSeconds 120 -NoiseDescription 'piece calme sans voix'
```

2. Trois phrases, voix douce à 1 m, calme :

```powershell
powershell @commun -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 1 -VoiceLevel SOFT -NoiseDescription 'piece calme sans voix'
```

3. Les mêmes trois phrases, voix douce à 3 m, calme :

```powershell
powershell @commun -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'piece calme sans voix'
```

4. Six mots courts, voix douce à 3 m, calme :

```powershell
powershell @commun -TestCase WORDS -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'piece calme sans voix'
```

5. Les trois phrases à 3 m, voix douce, aspirateur stable pendant tout le bloc. Reproduire la position du téléphone, de la personne et de la source de bruit pour un futur A/B.

```powershell
powershell @commun -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'aspirateur stable pendant tout le bloc'
```

Le protocole PHRASES propose à 15, 30 et 45 secondes :

- « Je vais chercher mes chaussures et je reviens. »
- « La fenêtre de la cuisine est ouverte. »
- « Le téléphone est posé sur la table. »

WORDS propose à 15, 23, 31, 39, 47 et 55 secondes : Bonjour, Merci, Oui, Bonjour, Merci, Oui. Rester silencieux entre les consignes et jusqu'à la fin. Un bloc annoté exige au moins 75 secondes. Le mode FREE reste disponible pour une observation libre ; il n'a pas de consignes planifiées.

Après analyse de ces cinq blocs, ajouter les mêmes phrases et mots à 5 m, puis varier les bruits, téléphones et langues. Ces quelques essais ne suffisent pas à qualifier la production ni toutes les langues européennes.

## Fichiers et horloges

Le dossier et le ZIP sont créés dans `C:\Projets\guardian-voice\diagnostics-voix`. Ils contiennent les voix et les transcriptions : ne pas les publier dans le dépôt.

- `session.json` : version, profil, route demandée/appliquée, distance, voix, bruit, notes et observations réelles.
- `test-plan.json` : textes attendus et instants planifiés.
- `test-markers.json` : BEGIN, PROMPT et END, temps du minuteur, UTC PC avant/après la commande ADB, dernière trame diagnostique connue du téléphone.
- `guardian.log` : événements natifs et marqueurs `FLOAT_TEST_*` horodatés par Android.
- `audio-routing.log` : choix de source, microphones et traitements.
- `wav` : cinq paires WAV/Float32 et leur index.
- `gtcrn-comparison` : deux paires WAV/Float32 et leur index.
- `wav-manifest.json` : empreintes et informations des fichiers audio et index.

Les événements AUDIO_FRAME exposent désormais le numéro de séquence, `capturedAtNs`, `observedAtNs`, ainsi que l'UTC et le temps écoulé Android. `capturedAtNs` est la valeur du pipeline/index : ne pas l'assimiler à une horloge matérielle de chaque échantillon. L'UTC PC et l'UTC téléphone peuvent différer ; `elapsedRealtime` et `System.nanoTime` ne doivent pas être confondus après veille. Les références aux trames et les horodatages Android permettent d'aligner les marqueurs ; la dernière trame enregistrée peut précéder le marqueur.

Le WAV commence avec la capture et se termine à l'arrêt effectif de la protection : il peut dépasser le minuteur. Les marqueurs délimitent explicitement le bloc utile sans supprimer le pré-roll. La voie GTCRN reste bornée et peut abandonner des trames si elle est en retard ; comparer via les index, jamais en supposant une continuité parfaite.

## Vérifications de livraison

Tests host : conservation bit à bit des flottants, signaux sous le pas PCM16, limites, index et entrées invalides. Compilation des interfaces Kotlin avec stubs Android. Intégration réelle FireRed JNI : trois fenêtres originales inchangées à l'entrée d'un faux décodeur Qwen. Collecte ADB simulée avec fichiers intacts, tronqués, corrompus et erreurs de finalisation ; protocole WORDS de 75 secondes vérifié avec six invitations chronométrées. Les simulations ne remplacent pas la compilation APK ou un test sur le téléphone.
