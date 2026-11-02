# Installer le correctif de résilience Guardian

Cette livraison installe le module natif corrigé dans votre projet Expo local. Elle ne contient pas d’APK et ne publie rien sur GitHub ou EAS. Le modèle UL-UNAS n’est pas installé. Le réglage Qwen reste à deux threads. Les règles de détresse/citations restent celles de la version précédente.

## Préparation

Décompresser entièrement le ZIP de livraison dans un dossier d’outils, par exemple `C:\Projets\Guardian-resilience`. Ne pas le placer sous `guardian-voice\modules`, ce qui risquerait de créer des modules Expo supplémentaires.

Le projet cible doit déjà disposer de ses dépendances Expo/Android habituelles et de l’unique AAR `sherpa-onnx-1.13.8-gv3895-2.aar` attendu dans `android\app\libs`. Le correctif ne remplace aucun AAR, ne télécharge pas de modèle et ne change pas la signature Android. Conserver les modèles déjà présents sur le téléphone ; ne pas désinstaller l’application pour appliquer ce correctif.

## Installer et enregistrer les logs

Dans PowerShell :

```powershell
$ErrorActionPreference = 'Stop'
Set-Location 'C:\Projets\Guardian-resilience'
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer-guardian-resilience.ps1 -ProjectDirectory 'C:\Projets\guardian-voice' 2>&1 | Tee-Object -FilePath .\installation.log
if ($LASTEXITCODE -ne 0) { throw 'Installation interrompue : consulter installation.log' }

Set-Location 'C:\Projets\guardian-voice'
npx expo run:android --device 2>&1 | Tee-Object -FilePath 'C:\Projets\Guardian-resilience\compilation-android.log'
if ($LASTEXITCODE -ne 0) { throw 'Compilation interrompue : consulter compilation-android.log' }
```

Remplacer le premier chemin et celui du fichier de compilation si vous avez choisi un autre dossier d’outils. Les deux fichiers de logs sont à joindre en cas d’échec. Ils ne sont créés qu’en exécutant les commandes sur votre machine ; aucun log d’installation Windows réussi n’est fourni comme preuve fictive.

Le script vérifie SHA-256 du ZIP, le manifeste et chaque fichier du module. Il sauvegarde entièrement le module précédent dans un dossier voisin du projet, nommé `guardian-voice-guardian-sauvegarde-...`, avant remplacement. Si une erreur survient pendant la copie, il tente de restaurer automatiquement ce module. Le chemin de sauvegarde est affiché dans `installation.log`.

Après compilation, lancer l’application et vérifier séparément le démarrage vocal, le comportement après arrêt/redémarrage et les champs de santé. Une compilation réussie ne prouve pas la livraison d’une alerte. Les essais Windows/APK/téléphone n’ont pas été exécutés dans l’environnement Linux de préparation.

## Mesurer le CPU

Avec l’application déjà démarrée sur le téléphone :

```powershell
Set-Location 'C:\Projets\Guardian-resilience'
powershell -NoProfile -ExecutionPolicy Bypass -File .\mesurer-cpu-guardian.ps1 -DurationSeconds 180 -OutputCsv .\guardian-cpu.csv
if ($LASTEXITCODE -ne 0) { throw 'Mesure CPU interrompue' }
```

Faire des sessions distinctes silence, aspirateur seul, parole au calme et parole sous aspirateur. Le CSV mesure le processus complet ; 100 % correspond à un cœur. Il ne mesure ni batterie ni température. Un appareil peut refuser l’accès à /proc ; garder alors le message exact au lieu d’interpréter un fichier vide comme une mesure nulle.

## Retour arrière après une compilation échouée

Fermer Metro/Android Studio et les terminaux ouverts dans le module. Reprendre le chemin exact de sauvegarde affiché dans le log, puis exécuter le script de restauration fourni :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\restaurer-guardian-resilience.ps1 -ProjectDirectory 'C:\Projets\guardian-voice' -BackupDirectory 'C:\Projets\guardian-voice-guardian-sauvegarde-CHEMIN-A-REMPLACER'
```

Il conserve également le module corrigé dans un nouveau dossier voisin du projet avant de remettre l’ancien. Reconstruire ensuite avec la même commande Expo. Aucun effacement des données du téléphone n’est nécessaire à cette restauration de sources.

## Identité du paquet

Paquet `guardian-voice-v3.5.4-resilience-cpu-r1`, basé sur le commit `a39e884311f5b7a5aff955309e89d286ca215dfd`, avec le correctif local de résilience/CPU. Ce n’est pas une version publiée sur le dépôt distant. Le libellé de diagnostic interne v3.5.4 antérieur reste présent : le manifeste et les empreintes des fichiers identifient précisément cette livraison.
