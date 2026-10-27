# Guardian Voice v3.4.8 — confirmation indépendante et gain contrôlé

Cette version de test ajoute une confirmation FSMN sur le signal original, après les validations FireRed existantes. Dans le replay complet du dernier enregistrement, les trois phrases restent admises et les deux faux textes sont bloqués avant Qwen. Le nouveau modèle et ses bibliothèques sont fournis avec le module.

Une limite reste connue : certaines vues du second canal trompent encore FSMN. Cette livraison apporte une correction vérifiée sur les cas décrits ; elle ne valide pas tous les téléphones, tous les bruits ou toutes les langues. L’APK complet et la nouvelle bibliothèque doivent encore être testés sur appareil.

## Ce qui change dans la décision

La chaîne conserve l’audio original et le découpage existant :

1. FireRed streaming repère les fenêtres potentielles.
2. FireRed examine la fenêtre complète ; en capture multicanale, il faut aussi une preuve sur un canal original continu.
3. Le nouveau FSMN examine **le PCM principal original**, avec son propre conditionnement.
4. Qwen reçoit la fenêtre originale seulement si ces validations passent.

FSMN est obligatoire dans le processeur vocal créé par le service de cette version. Un modèle absent, une empreinte incorrecte ou une erreur d’inférence n’autorise pas le passage avec FireRed seul : les journaux indiquent l’échec. Les anciennes constructions du processeur utilisées dans les tests peuvent explicitement désactiver cette option pour les comparaisons.

Le gain FSMN est calculé en Float32 :

`gain = max(1, min(64, 0.4 / pic_original))`

Un signal nul conserve une copie nulle. Le gain ne réduit pas un signal déjà fort ; il limite l’amplification vers un pic de 0,4. Le plafond 64 reste disponible pour les voix très faibles. Ce réglage est distinct du conditionnement FireRed, qui reste inchangé pour préserver le déclenchement des voix distantes.

Chaque analyse utilise sa propre copie du PCM. Les gains ne s’appliquent pas successivement au même tampon. **Le PCM envoyé à Qwen n’est pas remplacé par l’entrée normalisée de FSMN.** Dans le mode `WIENER_STUDY`, le gain de segment principal reste 1 et le débruitage reste diagnostique.

La politique FSMN est portée de la référence amont : fenêtre de décision de 200 ms, transition de 150 ms et différence de probabilités de 0,6. Ce dernier nombre n’est ni un seuil de volume ni un simple seuil de probabilité de parole égal à 0,6. Le port conserve aussi les arrondis Float32 de la référence et la fermeture des segments terminés ; il n’ajoute pas de seuil RMS.

## Port natif Android

Le modèle offline FSMN est converti en NCNN FP32, sans réentraînement, quantification ou nouvelle dépendance ONNX Runtime. Le frontend utilise le Hamming 400/160, 80 bandes mel, l’empilement LFR 5/1 et le CMVN d’origine en double précision. Les empreintes des trois assets sont vérifiées avant le chargement.

Une bibliothèque isolée `libguardian_fsmn_vad.so` est fournie pour `arm64-v8a`, `armeabi-v7a`, `x86` et `x86_64`. Les symboles NCNN sont privés ; les bibliothèques FireRed existantes ne sont pas remplacées. Les segments ELF sont alignés sur 16 Ko et les dépendances natives ne contiennent pas ONNX Runtime ou un nouveau `libc++_shared`.

Les sources, la conversion reproductible, les notices et les hashes des modèles sont dans `android/src/main/assets/fsmn/PROVENANCE.md` et `tests/native/`. Le modèle amont est distribué dans un dépôt MIT et attribué à ModelScope/FunASR. Sa documentation indique un entraînement sur corpus chinois. Les essais français décrits ici ne valident pas toutes les langues européennes.

## Comparaison des canaux et journaux

La file diagnostique contient désormais **deux tâches en attente au total**, en FIFO. Les finales principales restent prioritaires. Une tâche déjà lancée n’est pas préemptible ; les diagnostics peuvent donc encore ajouter de la latence pendant l’étude.

L’ordre des canaux tourne après la dernière offre réellement acceptée. Les résultats ne dépendent plus de la parité des identifiants de segment et le canal 0 n’accapare plus la place disponible. Les statuts distinguent une tâche mise en file, refusée faute de place, abandonnée, échouée, vide ou décodée. Un refus ou une absence de résultat n’est jamais compté comme du silence reconnu.

`FSMN_READY` décrit le modèle, le gain et la politique réellement chargés. `FSMN_RESULT` distingue un contrôle exécuté, accepté, rejeté, échoué ou non exécuté parce qu’un contrôle précédent avait déjà rejeté le segment. Les durées utilisent des noms explicites : `confirmationMs`, `coherentGateMs`, `fsmnConfirmationMs` et `confirmationCpuMs`. Le champ commun `elapsedMs` reste réservé à l’horloge de session.

Le nouveau collecteur vérifie ces preuves par segment et les rapproche de l’admission finale. Une erreur conserve les fichiers récupérables et ne devient pas un test terminé avec succès. Un rejet normal par FSMN est un résultat de test valide.

## Vérifications réalisées

| Vérification | Résultat |
|---|---|
| Frontend contre la référence | 61 cas, 58 non vides ; écart maximal du posterior silence environ 1,53 × 10⁻⁵ ; aucune différence de classification de trame ou de segments |
| Politique Kotlin contre la référence | 226 comparaisons, dont frontières Float32 et cas courts ; aucune différence d’admission ou de nombre de segments |
| Modèle JNI réel | 132 vues, deux passages en ordre inverse ; 264 appels, mêmes admissions et nombres de segments que la référence |
| Dernière capture complète | 3 finales principales conservées ; les 2 faux rejetés par FSMN après acceptation FireRed/cohérence |
| Comparaisons diagnostiques | Les deux canaux décodés dans le replay, cinq tâches par canal ; aucun callback diagnostique vers le produit |
| Ancien signal assemblé | Ancien faux toujours bloqué ; trois phrases conservées |
| Régressions mono | 13 enregistrements ; 23 finales identiques bit à bit aux références, dont six mots courts et trois phrases de l’ancien essai à 5 mètres |
| Erreurs et interruptions | Modèle manquant, erreur d’inférence et perte de payload refusés sans appel Qwen permissif |
| Compilation | Quatre bibliothèques Android natives et adaptateur Kotlin/API vérifiés ; APK complet non compilé ici |

Les trois PCM principaux du dernier replay et les dix PCM diagnostiques sont identiques aux entrées déjà transcrites par le vrai Qwen local. Le test d’intégration utilise le vrai FireRed et le vrai FSMN, avec un Qwen simulé pour contrôler strictement les callbacks et la possession des tampons. Il ne mesure pas les performances de Qwen et des nouvelles tâches simultanément sur le téléphone.

Les tests couvrent aussi le gain exact, l’immuabilité de l’original, les modèles non vérifiés, les deux places diagnostiques, leur limite globale, la priorité aux finales, la fermeture, les remises à zéro et la rotation jusqu’à 30 indices de canal.

## Limites à conserver dans le bilan

Sur les fenêtres principales du corpus de confirmation, la combinaison conserve les 26 bonnes admissions FireRed sur 27 paroles annotées et rejette les 16 négatifs. Le manqué ancien sous aspirateur reste présent. Elle rejette également 65 contrôles de bruit supplémentaires, soit 500 secondes de conditions corrélées ; cela ne constitue pas un taux de fausse alerte par heure.

Le contre-exemple du second canal reste enregistré dans les tests : deux vues originales sans parole et certaines vues débruitées restent admissibles par FSMN pris isolément. Il faut donc valider le comportement sur les autres canaux sélectionnés et appareils. Un accord sur une vue filtrée ne remplace jamais les preuves requises sur le signal principal.

Cette version réduit les faux **textes finaux** du dernier test. Le détecteur streaming peut encore proposer des fenêtres non vocales, ensuite rejetées. L’amélioration mesurée ne signifie pas que chaque signal de début de parole est devenu exact.

## Installer et reprendre le test

Exécuter le nouvel installateur :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.4.8-fsmn-confirmation.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

L’installateur vérifie l’archive et tous les fichiers, sauvegarde l’ancien module à côté du projet puis remplace uniquement le module. En cas de verrouillage du dossier, fermer les programmes qui l’utilisent et arrêter Gradle avant de relancer.

Reconstruire ensuite l’APK avec la procédure habituelle et l’installer **sans désinstaller Guardian**, pour conserver Qwen. Les petits modèles FSMN sont inclus dans l’APK ; aucun transfert Qwen supplémentaire n’est requis si l’application est conservée.

Une fois ce nouvel APK installé, commencer par le même bloc à 3 mètres, voix douce au calme, téléphone immobile :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-confirmation.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode WIENER_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Le collecteur demande un arrêt puis redémarrage de la protection. Attendre l’enregistrement actif et parler uniquement aux invitations. Les voix alentour seront enregistrées pendant ce diagnostic volontaire. Le ZIP est créé dans `C:\Projets\guardian-voice\diagnostics-voix`. Conserver les messages d’erreur et les fichiers partiels si le bloc échoue. Les anciens collecteurs restent disponibles mais n’acceptent pas cette nouvelle révision.

Les fichiers audio, transcriptions et rapports de travail utilisés pour les validations restent privés ; cette livraison contient uniquement le code, les modèles et des agrégats de tests.
