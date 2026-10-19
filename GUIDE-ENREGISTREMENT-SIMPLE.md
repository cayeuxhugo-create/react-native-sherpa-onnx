# Guardian v3.2 : installation et collecte

Cette livraison corrige le chemin de détection observé sur les WAV à 1, 3 et 5 m.
Lire `ETUDE-MOTEUR-V3.2.md` pour les résultats et leurs limites. Un nouvel APK est
nécessaire ; le script PowerShell seul ne modifie pas le moteur installé.

## Installer le module

Exécuter l'installateur `installer-moteur-v3.2.ps1` avec
`-ProjectDirectory 'C:\Projets\guardian-voice'`. Il vérifie le ZIP et tous les
fichiers du manifeste, sauvegarde l'ancien module hors du projet et remplace
uniquement `modules/guardian-native-protection`. Il ne lance pas EAS.

Reconstruire Android comme habituellement, puis installer l'APK comme mise à jour.
Ne pas désinstaller l'application : Qwen est déjà dans son stockage privé.

## Un enregistrement

Télécharger la version simple-3 du script `enregistrer-guardian.ps1`, puis :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile DISTANT -DurationSeconds 30
```

Le script active temporairement les diagnostics sans modification JavaScript.
Lorsqu'il le demande, arrêter complètement puis redémarrer la protection et
attendre « ENREGISTREMENT ACTIF ». À 5 mètres, répéter trois fois à voix douce
« Je vais chercher mes chaussures et je reviens », avec quelques secondes de pause.
Garder ensuite le silence, sans autre personne qui parle. Arrêter la protection
lorsque le script le demande et appuyer sur Entrée pour finaliser les fichiers.

Le dossier `diagnostics-voix/Enregistrement-...` contient les logs, les notes,
les paramètres, cinq WAV PCM16 mono 16 kHz, l'index de séquences et les empreintes.
Le ZIP à transmettre est créé à côté du dossier. Les voix et transcriptions y
figurent. Ne pas publier ce dossier ; un .gitignore local est créé s'il est absent.

Pistes : raw (avant filtre mono, après choix des canaux), pregain (après filtre,
avant gain), truth (capture après gain), asr (original avant segmentation Qwen),
vad (vue proposée au VAD avant son conditionnement interne). Certaines pistes
peuvent être identiques. Le plafond est de 180 secondes par piste ; démarrer le test
rapidement après la protection et l'arrêter au signal.

Le script vérifie les SHA-256 et les en-têtes, confirme la révision diagnostique,
conserve un ZIP partiel en cas d'échec, et restaure les quatre fichiers de commande
précédents à l'identique. Les WAV restent également sur le téléphone.
`audio-routing.log` rassemble les traces matérielles et les décisions de route.

Pour une comparaison ultérieure, ajouter `-CaptureRoute MIC_MONO` ou
`-CaptureRoute MIC_STEREO_FOCUS`. Le script refuse de qualifier un essai si la
route demandée n'est pas effectivement appliquée. Ne pas changer la route pendant
la première vérification v3.2 : conserver AUTO pour comparer le moteur.
`-Profile NOISY` reste disponible pour une session distincte avec bruit.
