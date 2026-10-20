# Étude du moteur continu et des déclenchements dans le silence

Étude du 20 octobre 2026. Relectures des WAV disponibles sur ordinateur avec Sherpa ONNX 1.13.8 ; les résultats ne sont pas des mesures de latence, batterie ou température sur Android. Aucun changement du moteur installé sur le téléphone.

## Décision issue des essais

Conserver Qwen pour la transcription finale. Le continu français Kroko est un candidat pour une voie rapide expérimentale, mais ne doit pas encore commander les alertes. Le modèle mobile français de 2023, dans la configuration essayée, obtient de moins bons résultats. Une voie dédiée aux mots importants reste à développer et à valider avec des mots réellement prononcés : ce rapport ne la présente pas comme opérationnelle.

Le continu traite progressivement les échantillons ; ce ne sont pas des relances intermédiaires de Qwen. Les deux modèles ont reçu les WAV originaux après le gain existant, en blocs de 50 ms, sans filtrer au préalable leurs résultats avec le VAD. Cela permet d'observer leurs propres émissions dans le silence. Recherche gloutonne, CPU deux threads, endpoint après 1,2 seconde de silence suivant la parole ; fin technique à 20 secondes ; trois secondes de zéros ajoutées uniquement pour vider le contexte du modèle. Les résultats distinguent ce vidage.

## Reconnaissance de la parole distante

Chaque cas contient trois répétitions de la même phrase de huit mots. Exactitude après normalisation de la casse et de la ponctuation ; un point isolé n'est pas compté comme une phrase. Une phrase inexacte peut néanmoins être largement intelligible.

| Cas enregistré | Continu Kroko 2025 : phrases exactes | Continu mobile 2023 : phrases exactes |
|---|---:|---:|
| 1 mètre | 3/3 | 1/3 |
| 3 mètres | 3/3 | 1/3 |
| Ancien test à 5 mètres | 2/3 | 1/3 |
| Dernier test à 5 mètres | 1/3 | 0/3 |

Le taux d'erreur sur les mots de Kroko est respectivement 0 %, 0 %, 4,17 % et 8,33 %. Sur le dernier cas, Qwen installé avait produit les trois phrases exactes avec un fragment supplémentaire provenant de la coupure technique ; Kroko ajoute deux erreurs de mots sur les 24 mots attendus. Ce dernier ne constitue donc pas un remplacement validé de Qwen.

Les deux modèles ont aussi été essayés sur une vue nettoyée par le GTCRN existant, avec restitution des gains. Les résultats à distance se dégradent globalement : envoyer systématiquement le signal nettoyé au reconnaisseur n'est pas retenu. Le nettoyage reste utile comme vue de détection de parole, sans imposer ce signal à la transcription.

Le temps de calcul sur ordinateur est inférieur à la durée audio. Cela ne mesure ni le délai avant le premier mot sur téléphone, ni le délai d'une alerte. Kroko actualise ici ses hypothèses à des intervalles voisins de 1,28 seconde : l'alimentation par blocs de 50 ms ne garantit pas une réponse en 50 ms.

## Silence et faux déclenchements

L'extrait de six secondes entourant la phrase inventée de l'ancien test silencieux a été rejoué : aucun texte avec les deux modèles continus ; aucune trame de parole admise par le VAD v3.2. Cela couvre ce cas connu, pas les nouveaux faux déclenchements signalés.

Dix minutes de contrôles synthétiques ont ensuite été générées : bruit blanc, rose, ventilateur simulé et clics, 150 secondes chacun. Chaque bruit comporte trois niveaux de 50 secondes : -65, -45 et -25 dBFS RMS avant écrêtage éventuel.

| Mesure sur ces 600 secondes | Résultat |
|---|---:|
| VAD v3.2 : trames admises comme parole | 0 |
| Kroko : mots provisoires / mots finaux | 0 / 0 |
| Mobile 2023 : mots provisoires / mots finaux | 0 / 0 |

Un mot supplémentaire apparaît toutefois à la fin d'un ancien WAV à 5 mètres avec le modèle mobile 2023. Il doit rester identifié comme une émission suspecte, sans attribuer automatiquement au silence tout passage non annoté. Les bruits générés ne reproduisent pas toutes les propriétés du micro, de la pièce ou d'un téléphone réel.

**Les faux déclenchements signalés par l'utilisateur restent donc à expliquer.** Pour chaque nouveau cas, l'analyse doit distinguer :

1. Le VAD ouvre-t-il réellement une séquence de parole ?
2. Le reconnaisseur produit-il un mot provisoire puis le retire-t-il ?
3. Un texte final incorrect est-il produit ?
4. Une règle Guardian transforme-t-elle ce texte en alerte ?
5. Une restitution sonore de l'application est-elle reprise par le microphone ?

Aucun seuil de volume supplémentaire n'a été ajouté pour masquer ces cas.

## Mots courts et voie dédiée

Contrôles synthétiques eSpeak NG français : « aide », « au secours », « non », « oui », « stop », à trois niveaux (-35, -50, -60 dBFS), soit quinze cas. Ils servent à détecter des régressions ; ils ne remplacent pas des voix humaines, des accents ou un test à distance réelle.

| Chaîne testée | Mots correctement transcrits |
|---|---:|
| VAD v3.2 + segmenter Kotlin réel + Qwen | 7/15 |
| Continu Kroko 2025 | 4/15 |
| Continu mobile 2023 | 0/15 |

Le VAD actuel admet les quinze cas, mais la transcription échoue sur plusieurs : **détecter la parole et comprendre le mot sont deux problèmes différents**. Sur les neuf contrôles « aide / au secours / stop », les résultats exacts sont 3/9 pour Qwen et 0/9 pour les modèles continus. Ces chiffres ne permettent de valider aucune nouvelle voie d'alerte.

La future voie dédiée devra suivre les mots configurés par Guardian, conserver la preuve acoustique et la stabilité des hypothèses, et être évaluée en observation avant de déclencher des actions. Aucun nouveau mot d'alerte ni comportement de protection n'est introduit dans cette livraison.

## Nouveau test sur le téléphone : dix minutes au total

`test-silence-guardian.ps1` orchestre six captures de 100 secondes avec le build actuel : trois en silence, puis trois avec un bruit constant sans voix. Profil DISTANT et route AUTO conservés pour isoler le changement d'environnement. Ce découpage respecte la limite native de 180 secondes par WAV, démarrage compris.

Placer le téléphone comme lors des déclenchements indésirables. Ne pas parler ; éviter télévision, radio et autres personnes pour ces six blocs. À chaque bloc, le script ferme Guardian ; ouvrir ensuite l'application et activer la protection au signal du collecteur. À la fin des 100 secondes, arrêter complètement la protection et valider. Les consignes générales du collecteur invitant à parler ne s'appliquent pas à cette étude : garder le silence.

Le script utilise le collecteur existant et, en cas d'absence de confirmation de fermeture des WAV après une capture complète, ferme Guardian et appelle le récupérateur vérifié. Les contrôles du collecteur doivent avoir été restaurés. Un bloc interrompu ou inférieur à 99 secondes n'est pas compté comme réussi. Le récupérateur contrôle les empreintes et peut réparer uniquement les en-têtes des copies locales lorsque cela est nécessaire.

Un dossier et un ZIP récapitulatifs sont créés dans `diagnostics-voix/Etude-silence-*`. Les dossiers de capture individuels restent conservés. Un ZIP partiel est conservé en cas d'échec. Le ZIP contient les voix éventuellement captées, les transcriptions et les informations de diagnostic ; le transmettre ici pour analyse.

Cette collecte exige `enregistrer-guardian.ps1` et `recuperer-guardian-wav.ps1` dans le même dossier que le nouveau script. Elle n'exige ni nouveau build ni réinstallation de Qwen. Après l'analyse du silence, compléter avec de vrais mots courts à 1, 3 et 5 mètres, puis avec du bruit : les deux étapes ne sont pas remplacées par les contrôles synthétiques.

## Reproduction et vérifications

- Scripts de relecture : `etude-streaming/replay_streaming.py`, alimentation réellement incrémentale et sorties provisoires/finales horodatées.
- Contrôles reproductibles : `etude-streaming/prepare_controls.py`, numpy/scipy et WAV eSpeak sources ; dix-neuf fichiers régénérés et comparés octet par octet.
- Résultats agrégés sans audio ni identifiant de session : `etude-streaming/results-summary.json`.
- Orchestration PowerShell vérifiée avec ADB et collecteurs simulés : six blocs réussis, récupération après absence de WAV_SAVED, absence de démarrage et bloc trop court. Ces simulations ne constituent pas un essai du script sur le téléphone réel.
- Le module Android et les fichiers de l'application ne sont pas modifiés. La voie dédiée et l'intégration du continu dans l'application ne sont pas encore implémentées. Les licences des modèles devront être vérifiées avant toute distribution ; aucun modèle ni WAV utilisateur n'est inclus dans les fichiers publiés.

Modèles officiels de la publication `asr-models` de k2-fsa/sherpa-onnx :

- `sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2`, SHA-256 `e6ffd3dc43725cd6c8137b05c739f15607d0df946b9b90eb141e10059efca024`.
- `sherpa-onnx-streaming-zipformer-fr-2023-04-14-mobile.tar.bz2`, SHA-256 `c2df15fc44ff9b116ee7084e12216525dfe2324ccecc4e35d28456e0bb7b5419`.

Exemple :

```bash
PYTHONPATH=/chemin/vers/dependances python etude-streaming/replay_streaming.py \
  --model /chemin/vers/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06 \
  --wav /chemin/vers/enregistrement-asr.wav \
  --output /chemin/vers/nouveaux-resultats.json
```

Le script refuse d'écraser une sortie existante. Les résultats détaillés contiennent du texte reconnu et doivent rester privés.
