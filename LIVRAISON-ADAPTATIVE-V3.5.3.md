# Guardian Voice v3.5.3 — candidat adaptatif pour essai réel

Cette livraison apporte une amélioration mesurée sur les deux captures fournies,
avec le **vrai Qwen3-ASR 0.6B int8 du 25 mars 2026**, Sherpa 1.13.8 CPU,
français imposé, mêmes paramètres de décodage que le code Android.
Les voix et les résultats textuels privés ne font pas partie de la livraison.

| Capture | Voie originale conservée, rejouée sur hôte | Candidat adaptatif, rejoué sur hôte |
|---|---|---|
| 3 m avec bruit | 1 phrase admise sur 3 ; sa transcription est inexacte | 3 phrases admises, 3 transcriptions exactes |
| 5 m au calme | 3 phrases admises, 2 transcriptions exactes | 3 phrases admises, 2 transcriptions exactes |
| Fenêtres supplémentaires décodées hors des phrases | 0 | 0 |

Les références sont celles fournies dans la conversation. Celle de la capture à
5 m reprend le protocole annoncé ; elle n'a pas été confirmée par une annotation
indépendante de l'enregistrement. Les résultats CPU peuvent différer de ceux de
l'AAR Android personnalisé. La substitution lexicale de la deuxième phrase à
5 m reste présente : **elle n'est pas corrigée par cette livraison**.

## Installation sur le projet Windows

Télécharger le ZIP et l'installateur dans le même dossier, puis lancer :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer-moteur-v3.5.3-adaptive-candidate.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'
```

L'installateur vérifie l'empreinte du ZIP et chaque fichier du module, sauvegarde
l'ancien module à côté du projet et remplace uniquement le module natif.
Il ne touche pas aux modèles installés dans les données du téléphone.

Reconstruire l'application avec la méthode et la signature habituelles, par exemple :

```powershell
Set-Location 'C:\Projets\guardian-voice'
npx expo run:android --device
```

Ne pas désinstaller l'application ni effacer ses données : Qwen est déjà installé.
Cette livraison contient les sources du module, **pas un APK compilé**.

## Activer et contrôler le candidat

Le mode normal reste `ORIGINAL`. Le nouveau chemin n'est actif que pendant un
essai explicitement sélectionné `ADAPTIVE_CANDIDATE`. Installer le ZIP seul ne
l'active pas. L'ancien mode `REFERENCE_NOISE_STUDY` reste diagnostique,
`SHADOW_NO_ALERT` ; il ne remplace pas le candidat.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\enregistrer-guardian-adaptatif.ps1 -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName com.hugo3154.guardianvoice -ProcessingMode ADAPTIVE_CANDIDATE -CaptureRoute MIC_STEREO_FOCUS -TestCase PHRASES -DurationSeconds 85 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'bruit stable'
```

Le script demande un arrêt/redémarrage de la protection. Garder les vingt
premières secondes sans parole, puis lire les consignes affichées et garder le
silence après la dernière phrase. Le candidat produit de vraies transcriptions
dans l'application : cet essai n'est pas une simulation de l'ASR.

Recommencer à 5 m au calme, puis avec `-TestCase SILENCE` dans le bruit. Pour une
comparaison avec la voie conservée, utiliser `-ProcessingMode ORIGINAL` avec
la même route et les mêmes conditions. Les enregistrements successifs ne sont
pas une comparaison des mêmes échantillons ; l'évaluation cloud, elle, utilise
les captures figées pour cette comparaison.

Le collecteur refuse une ancienne révision, un mode non appliqué, une preuve
FSMN incohérente et un export dont les octets diffèrent de l'entrée Qwen. Il
accepte les routes à deux canaux ou davantage : une ouverture Android à quatre
canaux n'est plus assimilée à un refus de la demande multicanal. Une absence de
deux canaux exploitables laisse la voie originale active ; les compteurs
`adaptiveFilterAvailableWindows` et `adaptiveFilteredWindows` montrent ce qui
a réellement fonctionné. Les contrôles locaux sont restaurés à la fin du test.

Les ZIP de diagnostic restent sur le PC. Aucun envoi automatique n'est effectué.

## Changements retenus après les comparaisons

| Levier | Résultat et décision |
|---|---|
| Original et filtré | Le candidat partage un seul découpeur entre les preuves de détection originales et filtrées. Une fenêtre filtrée ne peut remplacer/récupérer l'original qu'avec une calibration sans activité vocale observée, une continuité de canaux vérifiée et FireRed **et** FSMN positifs. Une calibration suspecte conserve l'admission et le PCM originaux |
| Calibration | Estimateur robuste disponible dans le candidat : exclusion des trames signalées par le VAD avec marge de 250 ms et des transitoires dépassant quatre fois l'énergie médiane sur l'une des sources ; au moins 63 trames utilisables, sinon repli. Sur le préfixe à 5 m, le rejeu isolé exclut 147/811 trames ; le parasite tardif est ensuite refusé par FSMN. L'indicateur de parole dans le préfixe reste conservateur et n'est pas effacé par le nouvel ajustement |
| Bornes | L'union des détections prolonge les phrases faibles avant clôture. Une seule fenêtre finale, au plus huit secondes, conserve les échantillons originaux et les recouvrements existants. Une trame réelle supplémentaire couvre la queue FFT retenue ; aucun remplissage ni découpage de fin pour rendre le filtre disponible. Les contextes de l'ancien outil d'étude sont plafonnés au budget restant de huit secondes |
| Intensité du filtrage | Comparaisons réelles original, LONG et mélange à 50 %, puis calibration robuste. Le mélange n'améliore pas régulièrement les mots ; il n'est pas promu. Choix original/filtré borné par la calibration et les confirmations, sans critère de baisse de RMS |
| Gain ASR | Comparaisons réelles ×1, maximum ×2 et maximum ×4, après admission des contrôles de parole. Pas de bénéfice régulier ; maximum ×1 conservé. Aucune amplification utilisée pour prouver la présence de parole |
| Microphones | Sélection de canal de v3.5.2 conservée, PCM effectivement transmis et empreinte enregistrés. Le filtre exige une paire synchrone stable et une identité vérifiée avec le canal primaire ; route, perte ou changement de source invalident sa calibration. Cela ne prouve pas qu'Android expose deux micros physiques indépendants |
| Doublons et fluidité | Un seul appel Qwen par fenêtre finale admise dans le candidat, sans décodages diagnostiques exact/contexte en parallèle. Les diagnostics historiques gardent leur déduplication stricte. Les nouveaux coûts du filtre et des validations sont journalisés ; un gain global de latence sur téléphone n'est pas établi |

L'exception à la garde originale existe **uniquement dans le candidat explicite**.
Elle récupère les deux phrases faibles manquées à 3 m dans le rejeu ; elle ne
constitue pas une règle universelle de reconnaissance correcte ou de rejet de
tout bruit. Aucun mot attendu ni liste de textes parasites n'est codé dans
l'admission ou utilisé pour réécrire la transcription.

## Vérifications et limites

- Suite Kotlin hôte : continuité, reset, concurrence, microphones, calibration
  robuste et 25 cas de politique d'admission/budget de fenêtre.
- Rejeux natifs avec les vrais FireRed/FSMN : deux captures, deux captures
  prolongées par du silence synthétique, changement de route au milieu d'une
  phrase, perte du canal de référence et quatre signaux synthétiques de 62 s
  (zéro, bruit corrélé, bruit indépendant, impulsions). Les prolongements font
  clôturer les perturbations terminales ; aucun nouveau décodage n'est admis.
- Vérification des 14 fenêtres admises des dix scénarios : export Float32,
  taille et SHA-256 identiques à l'entrée ASR ; les six entrées des captures
  non modifiées sont celles évaluées avec Qwen réel.
- 144 offres de comparaison Qwen, 54 offres complémentaires avec calibration
  robuste/contextes plafonnés et six entrées finales du candidat. Les offres
  de PCM identique peuvent réutiliser un décodage ; elles ne sont pas des
  observations indépendantes. Les diagnostics forcés sur le silence ne sont
  jamais comptés comme des phrases admises par le candidat.
- Compilation des composants audio contre les signatures Sherpa officielles ;
  ancien mode d'étude contrôlé sans modification de ses callbacks principaux.
- Six scénarios PowerShell avec ADB simulé, dont quatre canaux, mauvaise
  identité audio, preuve incohérente, mauvais APK et mode non actif.
- Installateur testé sur projet temporaire, sauvegarde et rejet de ZIP altéré.

Les résultats détaillés publics sont dans
`modules/guardian-native-protection/tests/results/v3.5.3-adaptive-summary.json`.
Les modèles natifs embarqués sont inchangés. Le dépôt `main` n'est pas modifié ;
aucune publication distante n'est effectuée.

L'application Expo Windows complète et l'AAR personnalisé ne sont pas présents
dans ce cloud. Le build APK complet, l'exécution JNI Android, la batterie, la
chauffe et la latence réelle restent à vérifier sur le téléphone. Les contrôles
synthétiques et deux enregistrements sur un appareil ne couvrent pas tous les
bruits ni toutes les voix. Le candidat reste réversible et ne devient pas le
mode normal avant ces essais réels.
