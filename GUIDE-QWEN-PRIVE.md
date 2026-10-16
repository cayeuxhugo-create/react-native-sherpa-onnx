# Installer Qwen dans le stockage privé — installateur v3

Le dossier privé lu en premier par Guardian est :

```
/data/user/0/<package>/files/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
```

Arrêter la protection avant le transfert. L'APK doit autoriser `run-as`
(APK débogable) ; le script le vérifie avant toute copie.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer-qwen-prive.ps1 -SourceDirectory 'C:\chemin\sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25' -PackageName 'com.exemple.guardian'
```

Le dossier source doit contenir conv_frontend.onnx, encoder.int8.onnx,
decoder.int8.onnx et le dossier tokenizer avec vocab.json, merges.txt et
tokenizer_config.json non vides. Les trois ONNX et tous les fichiers du
tokenizer sont transférés ; les WAV de démonstration ne sont pas nécessaires.
Ajouter -DeviceId si plusieurs téléphones sont connectés.

## Méthode vérifiée sur le téléphone pour un premier fichier

L'installateur v3 utilise le protocole natif `adb push` pour copier chaque
fichier dans son propre dossier sous /data/local/tmp. Guardian lit cette
copie via run-as ; sa taille et son SHA-256 doivent correspondre au PC.
Le fichier est alors copié par `run-as cp` dans un dossier privé provisoire,
puis sa taille et son SHA-256 sont vérifiés une seconde fois. La copie
temporaire de ce fichier est supprimée après validation.
Aucun octet des modèles ne passe par un pipeline texte PowerShell ou par
l'entrée standard d'un processus .NET. Les chemins locaux avec espaces
sont pris en charge. Un transfert raté ou une empreinte temporaire incorrecte
peut être réessayé trois fois. Les erreurs donnent les tailles et empreintes
observées lorsqu'elles sont disponibles.

Les permissions de lecture/traversée sont explicitées uniquement dans le
dossier temporaire créé sous /data/local/tmp. Les permissions du stockage
privé de l'application ne sont pas élargies. Le dossier temporaire ADB est
nettoyé à la fin ; aucune autre donnée de /data/local/tmp n'est supprimée.
Le fichier de test manuel guardian-qwen-probe.json n'est pas touché.

Le modèle devient actif par renommage du dossier privé seulement après
vérification de tous les fichiers. Un ancien modèle privé est renommé en
sauvegarde, jamais effacé. En cas d'échec de publication, le script tente de
restaurer cette sauvegarde. La copie externe existante reste intacte.
La connexion doit rester active jusqu'au message « Installation privée vérifiée ».
Le transfert approche 1 Go, et le stockage doit accueillir le modèle privé
ainsi qu'un fichier temporaire, dont le décodeur d'environ 756 Mo.

Après ce message, rouvrir Guardian et redémarrer complètement la protection.
Il confirme le transfert et l'intégrité par rapport au PC, pas la compatibilité
des graphes ONNX avec l'AAR : vérifier ensuite le chargement Qwen dans les logs.
Les données privées restent lors d'une mise à jour de même application, mais
sont supprimées par une désinstallation ou un effacement des données.

## Validation

Sur le téléphone : adb push puis run-as cp a réussi pour tokenizer_config.json ;
le SHA-256 privé correspond au fichier de référence. Le transfert des gros
ONNX par cette méthode reste à exécuter sur le téléphone.

Sous PowerShell 7/Linux avec adb simulé : installation neuve et remplacement,
fichier de plus de 17 Mio, chemins locaux avec espaces, valeurs binaires 0 à
255, tokenizer et fichier facultatif vide, tailles et SHA-256 aux deux étapes,
sauvegarde d'un ancien modèle et conservation de la copie externe.
Une copie temporaire tronquée est renvoyée ; les messages de démarrage ADB
ne faussent pas le checksum. APK non débogable, accès temporaire refusé,
échec de push, corruption de la copie privée et échec de publication : modèle
initial conservé/restauré et dossiers temporaires nettoyés.
Syntaxe prévue pour PowerShell 5.1 ; pas d'exécution locale Windows ici.
