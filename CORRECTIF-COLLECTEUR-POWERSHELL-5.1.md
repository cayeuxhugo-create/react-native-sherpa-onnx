# Correctif du collecteur de référence : PowerShell 5.1

Le collecteur `reference-noise-study-3.5.0.1` reste compatible avec le moteur Android `guardian-voice-v3.5.0-reference-noise-study`. Ce correctif ne demande pas de reconstruire l’APK et ne change aucun traitement audio.

La validation des fenêtres regroupait des dictionnaires Hashtable, puis demandait à `Select-Object -ExpandProperty lane` de lire une clé comme une propriété. PowerShell 7 accepte cette projection ; Windows PowerShell 5.1 peut lever « La propriété lane est introuvable ». La lecture utilise maintenant explicitement la clé du dictionnaire. Les contrôles de quatre voies distinctes, de durée, de couverture et d’identité binaire sont conservés.

Si cette erreur s’est produite dans le collecteur original, les WAV principaux, leurs Float32, le dossier reference-study et les journaux ont été copiés avant la validation. Le finally crée le ZIP et restaure les contrôles. Le bloc reste marqué non terminé ; cela ne constitue pas un échec du moteur. L’export des canaux adaptatifs et les observations finales, situés après cette validation, peuvent manquer. Conserver le ZIP et toutes ses parties pour analyse, sans refaire immédiatement la prise de son.

Pour le prochain essai, remplacer seulement `enregistrer-guardian-reference.ps1` par le fichier corrigé. Garder `preparer-envoi-diagnostic.ps1` dans le même dossier.

La régression reproduit sous PowerShell 7 le refus de projection de clé Hashtable de PowerShell 5.1 : l’ancien code échoue sur une exportation valide, le nouveau passe. Les scénarios incluent les quatre voies, le contexte, le mono, la duplication d’une voie, les trous d’horloge et les fichiers corrompus. Les exportations restent inchangées pendant leur validation. Windows PowerShell 5.1 natif n’est pas disponible sur cet hôte ; la vérification ciblée est une simulation de cette différence, pas une exécution sur Windows.
