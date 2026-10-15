# Réinstaller le module vocal après des fichiers mélangés

Le journal de compilation reçu indique des contenus qui ne correspondent pas
aux noms des fichiers : éléments PowerShell dans GuardianUploadWorker.kt,
import Swift ExpoModulesCore dans GuardianVoiceEvent.kt, et erreurs au-delà
de la longueur réelle de GuardianVoiceProfile.kt. Les erreurs de symboles
manquants qui suivent peuvent être des conséquences de ce mélange.

Le ZIP modules-voix-v3.zip a été revérifié : toutes ses sommes SHA-256 sont
conformes. Cet installateur remet exactement cette version, sans modifier
les algorithmes vocaux ni lancer une compilation EAS.

## Utilisation sous Windows PowerShell

Télécharger reinstaller-module-guardian.ps1 puis l'exécuter :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\reinstaller-module-guardian.ps1
```

Indiquer le chemin du projet Expo, le dossier contenant le package.json de
l'application, par exemple votre dossier de travail habituel dans C:\Projets.
On peut également fournir ce chemin directement :

```powershell
.\reinstaller-module-guardian.ps1 -ProjectDirectory 'C:\chemin\du-projet'
```

Le script télécharge une archive épinglée au commit de publication v3,
vérifie son SHA-256 puis le contenu du module. Il déplace l'ancien dossier
modules/guardian-native-protection dans une sauvegarde à côté du projet,
installe le dossier complet et vérifie chaque fichier après copie.
Le chemin de sauvegarde est affiché. Celle-ci reste hors du projet et de
modules pour éviter les doublons d'autolinking et l'envoi de la sauvegarde à EAS.

Les fichiers de l'application principale restent inchangés. Les éventuelles
modifications locales à l'intérieur de l'ancien module restent dans la
sauvegarde ; elles ne sont pas fusionnées automatiquement dans les sources
réinstallées. Ne pas remettre les fichiers .kt mélangés depuis cette sauvegarde.
En cas d'interruption de copie détectée, le script tente de restaurer l'ancien
module et conserve la copie partielle hors du projet.

Si vous avez déjà le ZIP v3, le téléchargement peut être évité :

```powershell
.\reinstaller-module-guardian.ps1 -ProjectDirectory 'C:\chemin\du-projet' -ArchivePath '.\modules-voix-v3.zip'
```

L'archive doit correspondre exactement au SHA-256 de la v3 ; sinon le script
s'arrête avant toute modification du projet. La validation n'accepte pas
un ZIP reconstruit ou une autre version à la place de celui attendu.

Après le message « Installation vérifiée », relancer votre compilation
Android habituelle depuis le projet réinstallé. Conserver le prochain journal
si elle échoue : restaurer les bons fichiers supprime ce problème de contenu,
mais la compilation complète de l'APK n'a pas été exécutée ici.

## Vérifications effectuées

Exécution réelle sous PowerShell 7/Linux avec projets de test, dont un chemin
contenant des espaces : remplacement d'un dossier mélangé, conformité au
manifeste, sauvegarde octet pour octet hors projet, conservation d'un AAR
applicatif témoin, seconde installation, refus d'un ZIP incorrect et
restauration après interruption de copie simulée. Téléchargement HTTPS réel
et vérification des fichiers installés également testés.
Syntaxe prévue pour PowerShell 5.1 ; pas d'exécution Windows dans cet environnement.
