# Mesurer la taille de l'APK Guardian

Le projet Android de l'application Windows et les APK de l'utilisateur ne sont
pas disponibles dans cet espace. Les tailles du ZIP d'installation et des
sources ne permettent pas de mesurer la taille du fichier APK final.

Entre v3.5.3 et v3.5.4, le ZIP du module passe de 17 873 879 a 17 899 508 octets
(+25 629 octets). Les assets/modeles (5 287 036 octets) et les bibliotheques
natives (29 999 496 octets) sont identiques octet pour octet. Le correctif reutilise
le modele GTCRN deja present. Les nouvelles sources Java/Kotlin representent
20 007 octets supplementaires ; cela ne mesure pas leur taille compilee en DEX.

La commande `npx expo run:android --device` construit par defaut la variante
debug. Une comparaison avec un APK release peut donc melanger deux modes de
compilation. Il faut relever le mode et les architectures des deux APK.
Le collecteur d'etude utilise actuellement `adb run-as` : un changement de
configuration de compilation doit conserver cet acces pendant les essais.

Le module fournit quatre architectures. Avant compression, ses bibliotheques
x86 et x86_64 representent 22 026 488 octets ; ses bibliotheques arm64-v8a
representent 5 218 840 octets. Le filtrage de l'application peut deja exclure
certaines architectures. Ces chiffres ne constituent donc pas une promesse
de gain sur un APK. Le choix d'architectures doit conserver les appareils vises.
Le passage de bibliotheques compressees a des bibliotheques stockees sans
compression peut aussi augmenter le fichier APK sans ajout de modele. Le rapport
releve les deux tailles pour permettre cette verification.

`diagnostiquer-taille-apk.ps1` s'execute sur le PC. Il lit le ZIP de l'APK,
repartit les tailles compressees et brutes par categorie et architecture,
affiche les fichiers les plus lourds et produit un rapport local. Si aapt est
disponible, il releve aussi les informations de manifeste. Avec un ancien APK,
il permet de comparer les memes categories. Aucun contenu vocal n'est lu et
aucun envoi automatique n'est effectue.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\diagnostiquer-taille-apk.ps1 `
    -ApkPath 'C:\chemin\application-actuelle.apk' `
    -PreviousApkPath 'C:\chemin\application-precedente.apk'
```

L'ancien APK est facultatif. En omettant `-ApkPath`, le script demande son chemin.
Le rapport permettra de choisir une correction precise : architectures superflues,
ecart de variante debug/release, gros assets ajoutes ou dependances natives.
Les enregistrements de diagnostic conserves dans les donnees Android ne font
pas partie du fichier APK et ne sont pas une explication de sa croissance.
