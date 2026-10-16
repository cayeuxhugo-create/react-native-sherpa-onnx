# Installer Qwen dans le stockage privé de Guardian

Si adb voit le modèle externe mais que Guardian indique root=false sur ses
racines de découverte, copier les fichiers dans le dossier privé que le
reconnaisseur consulte en premier :

```
/data/user/0/<package>/files/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
```

Arrêter la protection avant le transfert. L'APK doit autoriser `run-as`
(APK débogable) ; le script le vérifie avant toute copie. Aucun accès root
ni changement de permissions générales n'est utilisé.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer-qwen-prive.ps1 -SourceDirectory 'C:\chemin\sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25' -PackageName 'com.exemple.guardian'
```

Le dossier source doit contenir conv_frontend.onnx, encoder.int8.onnx,
decoder.int8.onnx et le dossier tokenizer avec vocab.json, merges.txt et
tokenizer_config.json non vides. Les trois ONNX et tous les fichiers du
tokenizer sont transférés ; les WAV de démonstration ne sont pas nécessaires.
Ajouter -DeviceId si plusieurs téléphones sont connectés.

La version v2 transfère des blocs de 8 Mio vers l'entrée standard d'adb
shell -T/run-as/dd via les flux binaires .NET. Le terminal virtuel est
désactivé. Chaque bloc est vérifié par SHA-256 et peut être renvoyé trois fois
avant d'être assemblé sur le téléphone à un emplacement précis du fichier.
Un bloc renvoyé n'est pas ajouté deux fois. Ils ne passent pas par le
pipeline texte PowerShell 5. Les chemins locaux peuvent contenir des espaces.
Chaque fichier complet est ensuite comparé par taille et SHA-256 sur le PC
et dans le stockage privé. Les messages de démarrage du serveur ADB sont
ignorés lors de la lecture des empreintes ; une seule ligne de checksum
valide est exigée. Les erreurs affichent tailles et empreintes observées.
La copie est placée dans un dossier temporaire privé ; elle devient le
modèle actif seulement après toutes les vérifications. Un ancien modèle
privé est renommé en sauvegarde, jamais effacé. En cas d'échec de publication,
le script tente de restaurer cette sauvegarde. Une copie temporaire créée
par le script est nettoyée à l'échec ; la copie externe existante reste intacte.

Après « Installation privée vérifiée », rouvrir Guardian et redémarrer
complètement la protection. Le message confirme le transfert et l'intégrité
par rapport au PC, pas la compatibilité des graphes ONNX avec l'AAR : le
chargement Qwen doit ensuite être vérifié dans les logs.

La copie approche 1 Go pour le modèle int8. Laisser le téléphone connecté
jusqu'à la fin du transfert et des vérifications. Les données privées sont
conservées lors d'une mise à jour de l'APK de même identifiant, mais sont
supprimées si l'application est désinstallée ou si ses données sont effacées.

Validation : PowerShell 7/Linux, adb simulé. Installation neuve et remplacement,
conservation exacte de tous les octets dont valeurs 0 à 255, fichiers tokenizer,
contrôle SHA-256, sauvegarde d'un ancien modèle. Refus d'un APK non débogable,
échec de copie, empreinte incorrecte et échec de publication : ancien modèle
conservé/restauré, copie temporaire nettoyée. Syntaxe prévue pour PowerShell 5.1.
Les tests v2 comprennent un fichier de plus de 17 Mio avec plusieurs blocs
et un dernier bloc partiel, des fichiers vides facultatifs, un bloc tronqué
au premier essai puis retransmis, des messages de démarrage ADB devant le
checksum et une erreur d'assemblage final. Le modèle d'origine est préservé
lors de chaque échec. Pas de téléphone Android ou de Windows disponible
pour exécution réelle ici.
