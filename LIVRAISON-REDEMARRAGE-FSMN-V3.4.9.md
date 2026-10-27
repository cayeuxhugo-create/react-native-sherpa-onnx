# Guardian Voice v3.4.9 — correction du crash au redémarrage

Après l'arrêt puis la réactivation du suivi vocal, la v3.4.8 pouvait quitter brutalement à la première confirmation FSMN. Les journaux montrent trois SIGABRT natifs identiques dans `libguardian_fsmn_vad.so`. La symbolication d'une reconstruction dont la section de code est identique au binaire livré situe l'arrêt dans une assertion d'affinité processeur de LLVM OpenMP, appelée depuis `ncnn::InnerProduct_arm::forward`.

Android classe ces événements comme des crashs natifs. Aucun de ces trois événements n'est classé comme un arrêt pour mémoire insuffisante. Le changement exact de topologie ou de masque CPU qui déclenche l'assertion n'a pas été enregistré ; la pile établit cependant le composant et le contrôle qui échouent. Un simple `catch` Kotlin ne peut pas intercepter ce SIGABRT.

## Correction

FSMN reste en CPU FP32 sur un thread. Sa bibliothèque NCNN est reconstruite depuis une source fixée, avec `NCNN_OPENMP=OFF` et `NCNN_SIMPLEOMP=OFF`. Le runtime responsable de l'assertion n'est ainsi plus lié à FSMN. Les bibliothèques FireRed restent distinctes.

Le démarrage exige également une réponse native `NCNN_SERIAL_CPU`. Une ancienne bibliothèque JNI ne peut pas se faire passer pour la correction : le chargement échoue explicitement avant la capture si le backend attendu manque. Ce backend apparaît dans le log normal `FSMN_READY` et dans les événements structurés.

Le modèle FSMN, ses trois assets et leurs empreintes restent identiques. Les poids, le conditionnement à pic 0,4 avec plafond de gain 64, les seuils, le découpage et le PCM Qwen ne sont pas modifiés par cette correction. La décision reste FireRed original ∧ cohérence du canal original lorsqu'elle est requise ∧ FSMN sur le PCM principal.

Le collecteur `enregistrer-guardian-redemarrage.ps1` exige la révision `guardian-voice-v3.4.9-fsmn-restart`, le backend série déclaré par la session et le backend confirmé après chargement natif. Les anciens collecteurs et installateurs sont conservés.

Les tests et leurs résultats mesurés sont consignés dans `modules/guardian-native-protection/tests/results/v3.4.9-fsmn-restart-summary.json`. La compilation et les replays hôte ne remplacent pas la validation du nouvel APK sur le téléphone affecté. Cette correction ne prétend pas réduire la mémoire de Qwen ni établir une mesure de consommation électrique.

## Vérifications réalisées

| Contrôle | Résultat |
|---|---|
| Bibliothèques Android | Quatre ABI, quatre exports JNI, alignement ELF 16 Ko, aucune référence au runtime OpenMP |
| Modèle et FireRed | Assets FSMN et bibliothèques FireRed identiques aux versions précédentes |
| Replays FSMN | 132 vues, 82 231 posteriors ; écart maximal du posterior silence de 1,02 × 10⁻⁶ par rapport à l'ancien hôte, aucune différence d'admission ou de segments |
| Frontend contre ONNX | 58 entrées non vides, aucune différence de classification de trame |
| Redémarrages hôte | 40 sessions et 240 appels natifs, dont 20 sessions sur un seul cœur ; nouveau worker à chaque session |
| Chaîne principale | Trois finales et leurs PCM conservés ; les deux anciens faux restent rejetés |
| Régressions mono | 13 enregistrements, 23 sorties et PCM identiques, dont six mots courts et trois anciennes phrases à 5 mètres |
| Compatibilité native | Ancienne JNI et backend incompatible refusés avant création du modèle ; backend série accepté |
| Collecteur | 25 scénarios ADB simulés, dont backend absent ou incorrect |
| Kotlin | Suite hôte et compilation de l'adaptateur API réussies |

Les intégrations utilisent les vrais VAD et un décodeur simulé pour contrôler les callbacks et l'identité des PCM déjà validés. Elles ne constituent pas un nouveau benchmark de reconnaissance Qwen. Les tests hôte de redémarrage ne reproduisent pas exactement le runtime LLVM Android ni les changements de cpuset du téléphone ; la correction doit être confirmée sur appareil.

## Installation

Après téléchargement du nouvel installateur :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.4.9-fsmn-restart.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

L'installateur contrôle l'archive et les fichiers, sauvegarde l'ancien module hors du projet et remplace uniquement `modules/guardian-native-protection`. Si Windows refuse de déplacer le dossier, fermer les outils qui le maintiennent ouvert, arrêter Gradle et relancer l'installateur.

Reconstruire ensuite Android avec la procédure habituelle, puis installer le nouvel APK par-dessus l'application existante pour conserver Qwen. Remplacer les fichiers JavaScript seuls ne remplace pas cette bibliothèque native.

## Vérification sur téléphone

Avant les nouveaux tests de précision, démarrer le suivi vocal, prononcer une phrase, attendre le résultat, arrêter complètement le suivi, puis le réactiver. Répéter cinq cycles dans le même processus de l'application, avec une phrase et un résultat à chaque cycle. Le but est de solliciter l'inférence FSMN après chaque redémarrage, pas seulement d'ouvrir le microphone. Garder aussi les journaux pendant ces cycles ; en cas de nouveau plantage, récupérer le journal et `dumpsys activity exit-info`.

Une fois ces cycles vérifiés, reprendre le test des mots courts :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-redemarrage.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode ORIGINAL -Profile DISTANT -TestCase WORDS -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Les WAV et journaux sont collectés dans le dossier `diagnostics-voix` du projet. Ils contiennent les voix et les textes du test volontaire. Les données privées utilisées pour le diagnostic du crash et les replays ne sont pas incluses dans cette livraison.
