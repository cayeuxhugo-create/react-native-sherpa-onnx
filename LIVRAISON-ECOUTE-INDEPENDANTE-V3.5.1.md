# Guardian Voice v3.5.1 — écoute débruitée indépendante

Le moteur peut maintenant créer des segments sur le flux Wiener LONG, même si la voie principale manque la parole. Le flux filtré est regroupé en blocs réels de 800 échantillons avant FireRed continu ; le découpeur corrigé conserve chaque échantillon et son horloge. Les fenêtres EXACT et END_PLUS_2S restent séparées et bornées à huit secondes.

Sur les WAV déjà étudiés, les fenêtres choisies automatiquement par ce code permettent à Qwen de restituer les trois phrases sous bruit. Des textes parasites apparaissent aussi. Les essais de gain, de confirmation et de détecteurs supplémentaires n’ont pas trouvé de modification qualifiable. La nouvelle voie reste donc diagnostique et n’émet aucun résultat produit ni aucune alerte. Les gardes, les modèles et les gains de la voie principale sont conservés.

La file Qwen reste unique. Pour un candidat filtré confirmé par FireRed, l’étude préfère le contexte réel de deux secondes, avec FSMN mesuré même lorsqu’il refuse. Si ce contexte dépasse huit secondes, l’extrait exact est proposé ; si un contexte attendu manque ou ne peut être proposé, une tentative explicite de repli exact est enregistrée. Une offre refusée ou un décodage manquant ne prouve jamais du silence. Au plus seize offres filtrées sont acceptées par étude.

Les identifiants filtrés commencent à un milliard et ne sont pas réutilisés lors des changements de route. `windowSource` distingue PRIMARY_ORIGINAL et FILTERED_STREAM_LONG ; une admission principale absente reste null. La paire de canaux est toujours stable dans chaque époque et n’est pas présentée comme la meilleure paire de microphones physiques. En mono, la comparaison intercanaux est non applicable et la voie originale continue.

Cette livraison contient aussi le correctif de continuité du découpage après huit secondes et le correctif PowerShell 5.1 de lecture de la clé lane. Les modèles et bibliothèques natifs de la v3.4.9 restent identiques.

## Utilisation

L’installateur remplace seulement le module, après vérification SHA-256 de l’archive et de chaque fichier ; il sauvegarde l’ancien module hors du projet. Une nouvelle compilation Android est nécessaire. Installer l’APK par-dessus l’application existante conserve le modèle Qwen privé.

Utiliser ensuite `enregistrer-guardian-independant.ps1`, avec `preparer-envoi-diagnostic.ps1` dans le même dossier. Le collecteur vérifie la révision v3.5.1 et les sources de fenêtres autonomes. L’ancien collecteur v3.5.0 reste fourni séparément pour l’ancien APK.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-independant.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode REFERENCE_NOISE_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'aspirateur constant'
```

Le bruit doit déjà être présent lors du démarrage. Garder au moins vingt secondes sans parole. Les phrases sont affichées avant de s’éloigner ; si l’écran est illisible, les mémoriser puis les lire une fois chacune avec des pauses, et décrire le déroulement réel à la fin. Les marqueurs de consigne restent des indications, pas une preuve d’instant de parole. Aucun signal sonore n’est généré.

## Vérifications et limites

Suite hôte et compilation API Kotlin réussies ; comparaisons voie principale inchangée, relectures natives autonomes, repli en fin de capture, exports quatre voies exacts et collecteur simulé vérifiés. Les détails agrégés sont dans `modules/guardian-native-protection/tests/results/v3.5.1-independent-filtered-study-summary.json`.

Le nouveau code n’a pas été validé dans un APK complet sur téléphone dans cet environnement. Les résultats sur les enregistrements déjà étudiés ne suffisent pas à qualifier une reconnaissance en production, toutes les langues européennes, tous les appareils ou tous les bruits. Les compteurs de blocs comprennent le DSP, le VAD continu filtré et l’écriture ; les confirmations de fenêtres et Qwen ont un coût supplémentaire.
