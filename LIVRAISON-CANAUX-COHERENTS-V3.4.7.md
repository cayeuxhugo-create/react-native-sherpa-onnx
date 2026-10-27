# Guardian Voice v3.4.7 — canaux cohérents et comparaison Wiener

Cette version corrige trois problèmes observés lors du dernier essai : les changements de canal sans avantage vocal solide, la destruction répétée du profil Wiener et l’acceptation d’un assemblage que chaque canal original continu rejetait. Le test complet sur ordinateur conserve les trois phrases prévues et bloque le faux texte de ce cas. La vérification sur le téléphone avec le nouvel APK reste nécessaire.

## Comportement du moteur

**Sélection.** Le classement acoustique conserve ses mesures et son avantage relatif de 3 dB, mais accumule maintenant un soutien temporel pondéré par la puissance estimée au-dessus du bruit propre à chaque canal. Une fluctuation de classement près du bruit n’entraîne plus automatiquement une bascule. Les changements pendant la parole restent possibles ; un canal muet ou saturé provoque toujours un secours immédiat. Aucun seuil de volume n’est ajouté à l’admission de la parole.

**Sources continues.** Le moteur conserve un passe-haut 0,97 indépendant par canal, avant gain ou débruitage. Chaque bloc porte des copies cohérentes sur la même horloge que le signal principal. L’identité de route est distincte de l’identité de sélection : choisir un autre canal ne signifie plus que le périphérique a changé. Une vraie modification de route ou de configuration invalide les états concernés. Un canal qui n’a jamais émis autre chose que des zéros peut rester omis ; après sa première apparition, il est conservé même pendant ses silences.

**Validation finale.** FireRed doit accepter le signal principal. En capture multicanale, il faut aussi qu’il accepte au moins un canal original continu sur exactement le même intervalle. Les canaux ayant contribué sont examinés en priorité, puis la recherche s’arrête au premier accord. Si un canal est identique au signal principal, sa décision est réutilisée. Les défaillances de métadonnées, trous et frontières de route ne deviennent pas une autorisation de reconnaissance. Une fois admis, Qwen reçoit toujours le PCM principal ; aucun mot reconnu ne sert à décider si l’entrée contenait de la parole.

**Wiener par canal.** Le mode volontaire `WIENER_STUDY` conserve maintenant un filtre, une calibration et des états continus par source réellement enregistrée. Une sélection différente ne remet plus ces états à zéro. Les protections de calibration restent présentes : détection de parole, vraie route modifiée, source manquante, démarrage incomplet et fenêtres qui traversent une frontière. Les canaux inutilisables n’ajoutent pas de preuve de parole.

**Décisions du produit.** Le débruitage reste en étude. La voie filtrée ne publie aucune transcription ou alerte au produit. Une fenêtre rejetée sur son canal original mais acceptée après filtrage est explicitement signalée comme telle ; elle peut être décodée uniquement dans la comparaison diagnostique. Le dernier replay montre précisément ce risque sur un passage sans parole, donc l’accord du débruiteur n’ouvre pas le moteur principal.

Les routes mono et les anciennes entrées mono sans ce nouveau payload restent compatibles. Les interfaces JavaScript de l’application ne changent pas.

## Résultats vérifiés

Les audios et les transcriptions de travail restent privés. Les chiffres suivants sont des agrégats des replays locaux, pas une mesure de fiabilité générale.

| Vérification | Résultat |
|---|---|
| Sélecteur, même capture et indications historiques de parole | 25 changements deviennent 1 ; les 23 bascules entre scores négatifs disparaissent |
| Nouveau moteur complet avec vrai FireRed et Qwen simulé pour contrôler les callbacks | 3 fenêtres principales admises, aucun faux texte principal dans ce replay |
| Vrai Qwen sur les nouvelles fenêtres principales | 3 phrases prévues sur 3 exactes |
| Ancien signal assemblé et anciennes sélections, nouveau gate seul | Faux passage bloqué ; les 3 bonnes fenêtres sont conservées bit à bit |
| Comparaison Wiener du replay multicanal | 10 paires réellement filtrées, 2 canaux utiles, 4 réinitialisations contre 39 auparavant |
| Régressions des corpus mono | 13 replays, 23 finales identiques bit à bit aux références ; dont 6 mots courts et 3 phrases de l’ancien essai à 5 mètres |
| Rupture de capture et tableaux immédiatement réutilisés | Étude invalidée sans publication diagnostique au produit ; copies et route préservées |
| Compilation | Capture contre les signatures Android API 35 et adaptateur contre les API Sherpa vérifiés ; APK complet non compilé ici |

Les tests ciblés couvrent aussi les classements fluctuants dans le bruit, une voix faible de 700 ms, les tailles de lecture différentes, le secours après source invalide, les zéros initiaux connus, les données manquantes, les fenêtres de route incohérentes, les horloges et les limites de conservation.

La comparaison des anciens corpus mono prouve que ces entrées gardent leurs fenêtres ; elle ne prouve pas à elle seule l’absence de régression de la nouvelle validation sur tous les assemblages multicanaux possibles. Le corpus de reconnaissance disponible est français. Il reste à tester les bruits, les autres appareils et les langues visées.

## Journaux et exports

`CHANNEL_COHERENCE_RESULT` explique la décision globale ; `FIRERED_CHANNEL_RESULT` détaille le canal, le pic, le gain de conditionnement, la probabilité et la durée positive. `AUDIO_FRAME` distingue la sélection, la route et les sources présentes.

Les comparaisons Wiener utilisent désormais des **paires originales et filtrées du même canal**, sous `continuous-study/channel-N/segment-ID`. Elles ne comparent pas un original assemblé à un canal filtré différent. `primaryAccepted`, `channelOriginalAccepted` et `enhancedAccepted` sont distincts. Les résultats Qwen diagnostiques sont associés au segment et au canal.

`channel-timeline.tsv` relie les échantillons des exports continus à l’horloge globale, y compris les origines tardives et les vraies interruptions. Les fichiers conservent leurs SHA-256, WAV et flottants. Les états et historiques restent bornés : 30 indices de canal au maximum, 16 secondes de conservation pour les fenêtres, 180 secondes d’étude et 64 fenêtres globales. Le nombre de sources effectivement utiles dépend du téléphone ; plusieurs canaux ouverts peuvent être muets ou dupliqués.

Le collecteur vérifie aussi la nouvelle portée `PER_CHANNEL_COHERENT`, les bornes et longueurs des fichiers, l’association des résultats et les pertes de données. Un test annoté de phrases ou de mots sans aucune vraie comparaison filtrée ne sera plus marqué terminé avec succès. Un test de silence peut légitimement ne contenir aucune fenêtre. Les fichiers récupérables sont conservés lors d’un échec.

Le modèle Qwen est partagé. Les tâches principales sont prioritaires, avec une seule tâche diagnostique en attente ; un décodage déjà lancé ne peut pas être interrompu. Certaines comparaisons peuvent donc être refusées ou ne pas se terminer avant l’arrêt. Les journaux les distinguent d’un rejet de parole. L’étude peut ajouter du coût et de la latence : ce mode est destiné aux essais.

## Fichiers principaux modifiés ou ajoutés

| Fichier Kotlin | Rôle |
|---|---|
| `GuardianAdaptiveChannels.kt` | Sélection stabilisée |
| `GuardianCaptureChannelFrame.kt` | Contrat de capture cohérente par canal |
| `GuardianChannelPreprocessor.kt` | Prétraitement continu propre à chaque source |
| `GuardianAudioEngine.kt` | Capture, route, possession et transport des sources |
| `GuardianVoiceInputRouter.kt` | Transport des métadonnées sur la bonne trame |
| `GuardianChannelWindowHistory.kt` | Fenêtres originales cohérentes, bornées et sans trous |
| `GuardianQwenPrimaryAudioProcessor.kt` | Double validation avant Qwen principal |
| `GuardianWienerChannelBank.kt` | Filtres et calibrations persistants par canal |
| `GuardianContinuousStudy.kt` | Comparaisons homologues, exports et résultats par canal |
| `GuardianProtectionService.kt` | Révision et description de la politique active |

S’y ajoutent les tests, leurs scripts de compilation, `enregistrer-guardian-canaux-coherents.ps1`, l’installateur et le manifeste. Les fichiers de l’application principale hors module ne sont pas remplacés.

## Installer puis tester

Exécuter l’installateur téléchargé avec le dossier de l’application :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\installer-moteur-v3.4.7-channel-coherence.ps1" -ProjectDirectory 'C:\Projets\guardian-voice'
```

L’archive et tous les fichiers du module sont vérifiés. L’ancien module est sauvegardé à côté du projet avant remplacement. Si le dossier est verrouillé, fermer les programmes qui l’utilisent et arrêter Gradle avant de relancer. Aucun remplacement forcé du dossier verrouillé n’est tenté.

Reconstruire ensuite l’APK avec la procédure habituelle, puis l’installer **sans désinstaller Guardian**, pour conserver Qwen. Aucun modèle supplémentaire n’est nécessaire. Le collecteur exige la révision `guardian-voice-v3.4.7-channel-coherence`.

Premier bloc : même téléphone, 3 mètres, voix douce, calme, appareil immobile.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:TEMP\enregistrer-guardian-canaux-coherents.ps1" -ProjectDirectory 'C:\Projets\guardian-voice' -PackageName 'com.hugo3154.guardianvoice' -CaptureRoute AUTO -ProcessingMode WIENER_STUDY -Profile DISTANT -TestCase PHRASES -DurationSeconds 75 -DistanceMeters 3 -VoiceLevel SOFT -NoiseDescription 'calme'
```

Suivre les consignes : arrêter puis redémarrer la protection, attendre l’enregistrement actif, garder le silence et parler uniquement aux invitations. Les silences initiaux servent aussi à calibrer les filtres. Les voix alentour seront enregistrées pendant ce diagnostic volontaire.

Le ZIP sera créé sous `C:\Projets\guardian-voice\diagnostics-voix`. Après ce bloc, les essais suivants sont le silence, les mots courts et un bruit stable sans voix, puis la distance. Garder les mêmes positions et décrire précisément le bruit. Si l’archive est trop grande, utiliser `preparer-envoi-diagnostic.ps1` et transmettre toutes les parties avec leur répartition.
