# Guardian Voice v3.4.3 — étude et corrections canal/gain

## Contenu

Le correctif des trames nulles de démarrage est actif dans le moteur. Un mode candidat compare une sélection de canal tenue pendant la parole et une voie avant gain, avec gain éventuel seulement après confirmation du segment. L'étude conserve les quinze éléments calmes, dont six mots courts et trois phrases à 5 m, sans gain supplémentaire. Le rejeu stéréo sous aspirateur récupère une phrase sur trois, contre zéro dans sa référence hôte ; le problème du bruit n'est pas résolu.

L'étude et ses limites sont dans `ETUDE-CANAUX-GAIN-20261026.md`. Les plafonds ×2 et ×4 testés dégradent une phrase calme ; ils ne sont pas recommandés.

## Installation

Exécuter `installer-moteur-v3.4.3-speech-gain-study.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'`, puis reconstruire Android et installer l'APK avec la procédure habituelle. Le script sauvegarde l'ancien module hors du projet et vérifie le manifeste. Il ne compile pas l'application. Ne pas désinstaller l'application pour cette mise à jour : une désinstallation supprime notamment les modèles privés.

Télécharger dans le même dossier temporaire `enregistrer-guardian-etude.ps1` et `preparer-envoi-diagnostic.ps1` depuis le commit de livraison. Le collecteur exige le nouvel APK v3.4.3.

## Modes explicitement vérifiés

- `ORIGINAL` : chaîne historique avec correctif d'initialisation. Référence par défaut ; ce n'est pas une reconstitution bit à bit de l'ancien build à son démarrage.
- `CANDIDATE` : gain de capture désactivé, signal avant gain pour FireRed, canal tenu pendant la portion de parole si le mode focus est utilisé, plafond de gain de segment ×1 recommandé.
- `CANDIDATE_GAIN2` et `CANDIDATE_GAIN4` : comparaisons explicites, plafonds respectifs ×2/×4 après confirmation ; ils ne sont pas retenus sur les résultats actuels.

Le réglage est un fichier de contrôle privé, restauré par le collecteur. Le candidat n'est pris en compte que pendant un enregistrement WAV diagnostic. Il ne s'active pas automatiquement en usage habituel, et un mode non reconnu se replie sur ORIGINAL. La sélection ne fait rien en mono ; deux canaux Android ne garantissent pas deux microphones bruts ou un faisceau physique.

## Première validation après installation du nouvel APK

Vérifier d'abord le silence et les phrases calmes avec CANDIDATE. Puis refaire une paire de blocs sous aspirateur dans des conditions inchangées, l'un en ORIGINAL, l'autre en CANDIDATE. Ne pas activer les gains 2/4 pour cette première comparaison.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-etude.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile NOISY -CaptureRoute VOICE_RECOGNITION_MONO -ProcessingMode CANDIDATE -TestCase SILENCE -DurationSeconds 120 -NoiseDescription 'piece calme sans voix'
```

Pour les phrases à 3 m dans le calme :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-etude.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile NOISY -CaptureRoute VOICE_RECOGNITION_MONO -ProcessingMode CANDIDATE -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'piece calme sans voix'
```

Pour comparer les canaux sous aspirateur, exécuter séparément ces deux commandes, dans l'ordre de son choix, sans déplacer le téléphone, la personne ou l'aspirateur et sans changer le niveau de bruit ou de voix :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-etude.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile NOISY -CaptureRoute MIC_STEREO_FOCUS -ProcessingMode ORIGINAL -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'aspirateur stable pendant tout le bloc'

powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-etude.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -Profile NOISY -CaptureRoute MIC_STEREO_FOCUS -ProcessingMode CANDIDATE -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'aspirateur stable pendant tout le bloc'
```

Le script demande d'arrêter complètement puis redémarrer la protection après préparation de chaque mode. Attendre ENREGISTREMENT ACTIF, lire les trois phrases à PARLER, rester silencieux entre les consignes. À la fin, arrêter la protection puis Entrée. Les consignes ne sont pas une preuve que les paroles ont été prononcées : indiquer les observations réelles et les consignes manquées.

Les résultats restent dans `C:\Projets\guardian-voice\diagnostics-voix`. Le ZIP contient les voix et les transcriptions ; ne pas le publier dans le dépôt. Pour MIC_STEREO_FOCUS, le dossier `multimic` contient désormais le WAV à deux canaux avant sélection, récupéré avec contrôle SHA-256. L'absence de ce fichier ou une erreur de mode invalide le bloc et conserve les autres éléments.

Si le ZIP dépasse 32 Mio et que le répartiteur est présent à côté du collecteur, les petites archives sont préparées automatiquement. Envoyer les `partie-*.zip` et `repartition.json` du dossier affiché, en distinguant les deux modes. Le ZIP original est conservé.

Les exports Float32 sont mono, 16 kHz, IEEE754 little-endian sans en-tête ; les WAV d'écoute sont PCM16. Les index conservent les références de capture. Dans le candidat, `asr.wav` est la base avant gain de segment ; l'événement `SEGMENT_GAIN` indique le coefficient réellement appliqué après confirmation. Les WAV peuvent dépasser le minuteur jusqu'à l'arrêt effectif de la protection. Ne pas confondre l'UTC PC avec celui du téléphone ; les événements AUDIO_FRAME donnent désormais l'heure d'observation dans le même domaine elapsedRealtime que la capture.
