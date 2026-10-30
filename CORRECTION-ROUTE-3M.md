# Vérification de la route initiale à 3 m

Les anciennes captures utilisaient la source Android MIC (1), quatre canaux
ouverts et le masque d'indices 15. Deux de ces canaux contenaient du signal ;
quatre canaux ouverts ne signifie pas quatre microphones physiques utiles.

Les commandes de test données ensuite imposaient `MIC_STEREO_FOCUS`. Les
nouvelles sessions ouvrent MIC avec deux canaux et sans masque d'indices.
Dans `GuardianProtectionService`, cet override explicite désactive la découverte
`allChannelsProbe`. Ce changement rend la comparaison avec les anciennes prises
insuffisamment contrôlée. Il ne prouve pas à lui seul que la route explique les
erreurs de reconnaissance : le contenu et les conditions des prises diffèrent.

Le moteur v3.5.3 est correctement actif dans les nouveaux logs. La nouvelle
capture à 3 m ne publie aucune des trois phrases ; celle à 5 m les publie toutes.
Le rejeu natif reproduit ces décisions. Les anciens résultats de rejeu ne
permettent donc pas de déclarer une amélioration générale en bruit réel.

Le collecteur 3.5.3.2 ajoute des attentes facultatives sur la source, le nombre
de canaux et le masque. Une différence interrompt le protocole avant les phrases
et conserve les logs. Le lanceur `tester-guardian-route-initiale.ps1` demande
AUTO avec les attentes spécifiques au téléphone déjà étudié : 1 / 4 / 15.
Ce n'est pas une nouvelle règle générale de sélection du meilleur microphone.

## Utilisation

Placer les trois scripts dans le même dossier :

- `tester-guardian-route-initiale.ps1` ;
- `enregistrer-guardian-adaptatif.ps1` (3.5.3.2) ;
- `preparer-envoi-diagnostic.ps1`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tester-guardian-route-initiale.ps1 -ProjectDirectory 'C:\Projets\guardian-voice'
```

L'APK installé n'a pas à être reconstruit pour ce contrôle. Le lanceur demande
une seule prise de 85 secondes à 3 m avec l'aspirateur, en conservant les mêmes
positions et le même bruit autant que possible. Suivre les consignes affichées,
dont vingt secondes initiales sans parole et le silence après la troisième phrase.
La route observée doit être affichée comme source 1, canaux 4, masque 15.

Le lanceur ne retient que l'archive nouvellement créée, conserve le ZIP original
et prépare automatiquement les parties avec leur répartition. Une collecte en
erreur reste marquée en erreur même si ses logs ont été préparés pour envoi.
Les anciens ZIP ne sont pas modifiés. Aucun fichier n'est envoyé automatiquement.

Le test à 5 m au calme déjà reçu est exploitable ; il n'est pas nécessaire de le
refaire pour vérifier cette différence de route à 3 m. Si la route initiale ne
peut plus être ouverte, arrêter la protection et conserver l'archive de diagnostic.

## Contrôles réalisés

Douze scénarios du collecteur avec ADB simulé passent, dont AUTO à quatre canaux,
mauvaise source, mauvais nombre de canaux, mauvais masque, canal manquant,
identité audio altérée et restauration des contrôles. Trois scénarios du lanceur
passent : succès, archive de collecte en erreur conservée et absence de nouvelle
archive sans réutilisation accidentelle d'un ancien ZIP.

Ces tests vérifient les scripts et les contrôles de route ; ils ne prouvent pas
que la prochaine capture sera reconnue correctement. Aucune voix ni transcription
privée n'est incluse dans cette correction et aucun modèle expérimental testé
dans le cloud n'est ajouté au téléphone.
