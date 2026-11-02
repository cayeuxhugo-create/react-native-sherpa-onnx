# Réorganisation du module reçu

Le fichier `modules.zip` reçu contient 280 fichiers identiques au module de résilience déjà livré. L’archive principale et node_modules.zip n’ont pas pu être transférées : chacune dépasse la limite de 32 Mio de l’outil. Ce travail couvre donc le module natif, pas encore l’application Expo complète.

## Réalisé

- Sources Kotlin classées par domaine : core, configuration, événements, réseau, stockage, localisation, PTI et sous-dossiers voice (capture, vad, enhancement, asr, segmentation, decision, delivery, diagnostics).
- 76 fichiers déplacés sans changement de contenu, dont deux simulations sorties de main et placées dans tests/host/legacy. Le fichier Kotlin GuardianManualSosEvent était vide et a été supprimé. Il reste 76 fichiers Kotlin de production, dont les deux points d’entrée Expo à la racine du package.
- Packages Kotlin, noms de classes et bibliothèques JNI préservés. Aucun seuil ou algorithme de reconnaissance n’est modifié.
- Scripts de compilation hôte et deux lanceurs d’études adaptés aux nouveaux chemins. Résolution récursive des sources pour les listes dynamiques.
- README-ARCHITECTURE.md explique le parcours de lecture, et SOURCE-LAYOUT.json trace les anciens/nouveaux chemins et empreintes.

Les grandes classes AudioEngine et ProtectionService n’ont pas été fractionnées dans ce changement. Un tel découpage est une modification de conception/concurrence à réaliser séparément ; classer les fichiers ne prouve pas qu’une refonte interne est sans risque.

## Vérifié

- Contrôle automatique des empreintes et des contrats de nommage Expo.
- Suite hôte existante, y compris les tests de résilience.
- Tests du moteur vocal et du journal avec stubs Android.
- Deux simulations historiques compilées et exécutées depuis leur nouveau dossier.
- Compilation de l’adaptateur contre les signatures officielles Sherpa.
- Archives et SHA-256 vérifiés.

La compilation APK et l’autolinking dans votre projet Expo complet restent à vérifier sur votre poste. Les modèles et bibliothèques n’ont pas été allégés : cette livraison vise l’organisation et le retrait de fichiers de test de la production, pas un gain CPU annoncé.

## Installation

Le nouvel installateur `installer-guardian-organisation.ps1` utilise `modules-guardian-organisation.zip`, vérifie tous les fichiers et sauvegarde le module existant hors du projet. Utiliser cette paire ; l’ancien installateur de résilience continue de désigner son propre paquet.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer-guardian-organisation.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'
```

Après succès, reconstruire avec la méthode Expo habituelle (`npx expo run:android --device`). Le remplacement conserve les dépendances et modèles existants ; aucune désinstallation du téléphone n’est demandée.

## Pour traiter le reste de l’application

Envoyer une nouvelle archive des sources principales sans node_modules, .git, APK/AAB, ZIP d’installation, caches/build, modèles volumineux et secrets. Les configurations Expo/Android, package.json, lockfile, sources et références d’assets restent nécessaires. Les fichiers binaires exclus peuvent être listés avec leurs chemins et tailles. Si nécessaire, créer plusieurs ZIP indépendants de moins de 32 Mio chacun. modules.zip n’a pas besoin d’être renvoyé s’il n’a pas changé.

## Export automatique des sources pour l’audit de l’application

Le script `preparer-sources-guardian.ps1` prépare une copie des sources en ZIP indépendants de 24 Mio environ maximum par partie (contrôle final strictement sous 32 Mio). Il ne modifie et ne supprime aucun fichier du projet initial. Les fichiers exclus sont listés dans `MANIFESTE-AUDIT.json` afin de ne pas confondre une exclusion d’export avec un fichier inexistant.

Il ignore node_modules, caches/sorties générées, archives/APK, bibliothèques et modèles volumineux, ainsi que les noms courants de fichiers de credentials/clés/configuration locale. Les valeurs de mots de passe/tokens/clés reconnues dans gradle.properties sont masquées dans la copie exportée. Les secrets directement écrits dans du code ne sont pas détectés automatiquement ; vérifier les archives avant envoi reste nécessaire.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\preparer-sources-guardian.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'
```

Le dossier de sortie est créé à côté du projet et affiché à la fin. Envoyer uniquement les `guardian-sources-XX.zip` et `MANIFESTE-AUDIT.json`. Ne pas zipper de nouveau le dossier complet avec node_modules.

Test d’intégration réellement exécuté avec PowerShell 7.4.6 sur Linux : découpage, CRC et empreintes, chemins Unicode, exclusion des dépendances/secrets/build, copie masquée des propriétés, fichiers originaux intacts, liens cycliques ignorés, rejet d’une sortie dans le projet ou déjà existante. Le script cible la syntaxe Windows PowerShell 5.1 ; son exécution sur Windows reste à confirmer sur le poste utilisateur.
