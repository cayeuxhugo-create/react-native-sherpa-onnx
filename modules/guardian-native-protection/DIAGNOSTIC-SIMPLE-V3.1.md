# Guardian v3.1 : diagnostic de voix distante

Cette livraison remplace le module natif entier. Un nouvel APK est nécessaire.
Installer l'APK comme mise à jour, sans désinstaller Guardian, pour conserver le
modèle Qwen déjà copié dans les fichiers privés de l'application.

Corrections et limites :
- Les hypothèses intermédiaires Qwen sont limitées aux fenêtres de trois secondes.
  Les résultats finaux gardent le signal complet, jusqu'à huit secondes. Les finals
  avaient déjà la priorité dans la file ; un décodage natif en cours reste non interruptible.
  La réduction du délai sur le téléphone reste à mesurer.
- Le script active les logs et les WAV grâce à des commandes privées temporaires,
  même si voiceDiagnosticsEnabled=false dans la configuration JavaScript.
- Aucun changement du gain, des seuils VAD ou du traitement des voix distantes.
  Il faut mesurer la régression avant de régler ces paramètres.
- Les erreurs HTTP 503 vues dans le test concernent le serveur de synchronisation.
  Cette livraison ne les corrige pas ; les tentatives ont déjà un backoff WorkManager.

Après installation du module, reconstruire Android comme habituellement et installer
le nouvel APK. La compilation APK complète n'a pas pu être exécutée ici : l'application
Expo et son AAR Sherpa personnalisé ne font pas partie du dépôt fourni.

## Un seul enregistrement

Après mise à jour de l'APK, depuis PowerShell :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile DISTANT -DurationSeconds 90
```

Le script demande des notes facultatives, puis d'arrêter et redémarrer la protection.
Attendre « ENREGISTREMENT ACTIF ». Dans le calme, prononcer trois fois à chaque
distance la phrase « Je vais chercher mes chaussures et je reviens. » à voix douce :
- 0 à 25 secondes : environ un mètre ;
- 25 à 50 secondes : environ trois mètres ;
- 50 à 75 secondes : environ cinq mètres ;
- 75 à 90 secondes : silence, sans autre personne qui parle.

Garder environ trois secondes entre les répétitions. Noter si le téléphone affiche
une phrase fausse ou ne réagit pas. Arrêter la protection dès que le script le demande,
puis appuyer sur Entrée pour finaliser et récupérer les WAV. Ne pas laisser le moteur
tourner plusieurs minutes avant de commencer : plafond de 180 secondes par piste.

Le dossier `diagnostics-voix/Enregistrement-...` dans le projet contient les logs,
les notes, les paramètres de session, quatre WAV PCM16 mono 16 kHz et l'index TSV
qui relie les pistes aux séquences de capture. Le ZIP est créé à côté du dossier.
Les pistes sont : pregain (avant gain de capture), truth (signal de capture après
gain), asr (signal avant segmentation remis au chemin Qwen), vad (vue choisie avant
le gain interne du VAD). En DISTANT, certaines pistes sont volontairement identiques.

Les fichiers de commande présents avant le test sont restaurés à l'identique ;
les commandes créées pour ce test sont retirées. Les WAV restent aussi sur le téléphone.
Si une erreur interrompt la collecte, un ZIP partiel est conservé et marqué incomplet.
Les voix alentour et les transcriptions sont présentes dans le ZIP. Le dossier est
ignoré par Git via un fichier .gitignore local, sans écraser un .gitignore existant.

Le profil NOISY se teste ensuite avec `-Profile NOISY`, sans reconstruire l'APK.
Pour le premier test, conserver DISTANT et le calme afin d'isoler la distance.

Validation : tests Kotlin de segmentation, concurrence, gain, diagnostics et WAV ;
compilation des adaptateurs contre l'API officielle Sherpa 1.13.8 ; collecte PowerShell
avec ADB simulé (succès DISTANT/NOISY, ancien APK refusé, téléchargement corrompu refusé,
limite WAV détectée, restauration exacte des commandes). Cela ne remplace pas le test
Android réel demandé ci-dessus. Aucun nouvel essai acoustique réel n'est revendiqué.
