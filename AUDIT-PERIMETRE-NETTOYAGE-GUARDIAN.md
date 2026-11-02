# Audit de périmètre et nettoyage prudent

## Accès réellement disponible

Le checkout `cayeuxhugo-create/react-native-sherpa-onnx` contient le SDK React Native (package racine react-native-sherpa-onnx 0.4.4), l’application d’exemple VoiceLabOfflineTools et le module `modules/guardian-native-protection`. Aucun package/app.json racine ou android/app correspondant au projet Expo Guardian complet n’a été trouvé dans ce checkout. Le dossier Windows `C:\Projets\guardian-voice` n’est pas monté dans cet environnement. Cet audit ne doit donc pas être présenté comme l’audit complet de l’application déployée.

## Suppression effectuée

Le fichier Git `_codeql_detected_source_root` était un lien symbolique de mode 120000 vers `.` : une référence cyclique à la racine du checkout. Aucune référence de build ou de code n’a été trouvée ; seul .prettierignore contenait son exclusion. Le lien lui-même a été supprimé, sans suivre sa cible. L’exclusion obsolète de Prettier a été retirée et le marqueur est maintenant ignoré par Git pour éviter son ajout accidentel. Aucune source d’application ou donnée audio n’a été supprimée.

## Éléments conservés après inspection

- 23 archives ZIP suivies, environ 252,2 Mio : livraisons et installateurs historiques. Elles occupent le dépôt, mais ne constituent pas à elles seules une preuve de gonflement de l’APK Guardian : l’installation copie le module, pas la racine du SDK. Une politique de conservation des livraisons doit précéder leur retrait, car certains scripts et liens épinglés y font référence.
- Les doublons de licences, modèles de test Android/iOS et outils/documents distribués dans le module sont souvent nécessaires à des destinations différentes. Un hash identique n’est pas une preuve d’inutilité.
- Les bibliothèques natives par ABI ne sont pas des doublons interchangeables. Leur suppression peut casser un appareil ou un émulateur.
- Les logs suivis sous tests/results servent de traces de validation historique ; pas de suppression automatique sur leur extension.
- Dépendances locales et caches ignorés : leur nettoyage peut libérer de la place mais n’établit rien sur le code inutile de l’application. Ils n’ont pas été confondus avec des sources à supprimer.
- Les données de tests privées, modèles téléchargés et outils de cet environnement se trouvent hors du checkout. Ils ne sont pas des fichiers de l’application Windows.

## Vérification

Recherche des références au marqueur, lecture du mode Git et de la cible du lien, comparaison des manifests package/app, inventaire des fichiers suivis et des doublons de contenu. Le contenu des fichiers de secrets et des fichiers d’authentification n’a pas été examiné. Aucun test fonctionnel n’est requis pour retirer ce lien non référencé ; la vérification de diff et la présence des sources sont suffisantes pour ce changement.

## Pour terminer l’audit de Guardian complet

Obtenir le dépôt et la branche de l’application Expo, ou une archive de ses sources. Inclure package.json, lockfile, app.json/app.config, src/app et modules, les configurations Android/Expo et les scripts de compilation. Exclure .env, clés de signature, credentials, node_modules, caches/build et gros modèles téléchargeables. Les imports dynamiques, l’autolinking Expo, les noms de ressources Android et les assets chargés par chemins devront être vérifiés avant suppression de code.

Une recherche de nom sans résultat ne suffit pas à déclarer un fichier inutilisé. Pour l’application complète, il faudra combiner graphe d’imports, configuration native, tests/build et vérification des parcours produit, puis livrer un diff de suppressions réversible.
