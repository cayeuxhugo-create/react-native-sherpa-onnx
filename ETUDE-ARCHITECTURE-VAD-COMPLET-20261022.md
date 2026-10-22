# Guardian : comparaison des architectures VAD sur enregistrements complets

## Résultat et décision

Le candidat le plus prometteur pour la suite est **FireRed streaming pour former
les segments, puis FireRed non streaming pour les confirmer avant Qwen**, avec
une queue audio de 1 200 ms. Cette architecture n'a plus besoin de Silero pour
le prédécoupage. Sur les enregistrements d'origine, elle reproduit exactement les
12 répétitions, sans appel Qwen sur les 500 secondes sans voix.

Ce n'est pas encore un remplacement validé pour le téléphone : un mot synthétique
très faible régresse, les essais à niveau réduit restent dégradés, et aucune mesure
Android n'a été faite. Les variantes exploratoires ont été évaluées sur le même
corpus, pas sur un jeu indépendant. La v3.3 livrée et le téléphone sont inchangés.

L'étude précédente sur des fenêtres déjà préparées ne suffisait pas à départager
les architectures. Ici, chaque VAD doit d'abord trouver la parole dans le flux
complet ; Qwen reconnaît ensuite les fenêtres réellement produites.

## Données et périmètre

24 entrées continues, provenant des données déjà disponibles :

- quatre enregistrements de parole : 1 m, 3 m, et deux tests à 5 m ; trois
  répétitions de la même phrase dans chacun, soit 12 répétitions ;
- cinq séquences de 100 secondes sans voix : deux ambiances calmes, aspirateur,
  ventilateur et bruit blanc, soit 500 secondes ;
- 15 mots courts synthétiques : cinq mots à trois niveaux, avec le silence
  avant/après déjà présent dans les fichiers de contrôle.

Les cinq contrôles sans voix sont reconstruits à partir des fenêtres contiguës
validées de l'étude précédente, dans l'ordre temporel, sans raccord supprimé :
chaque séquence contient exactement 1 600 000 échantillons à 16 kHz.

Les quatre enregistrements de parole sont aussi rejoués à -6 dB, puis à -6 dB avec
retrait des cinq premières millisecondes. Ces transformations ne sont ni de nouveaux
locuteurs, ni une simulation physique d'une distance supplémentaire : elles testent
la sensibilité au niveau et à l'alignement des trames, sans changer le rapport
signal/bruit d'origine.

La parole avec un véritable bruit ambiant supplémentaire n'est pas évaluée ici :
les contrôles aspirateur/ventilateur ne contiennent aucune voix. Les mélanges
synthétiques des études antérieures ne remplacent pas ce test réel manquant.

## Architectures comparées

Toutes utilisent l'audio ASR original enregistré, le même Qwen local, et le
segmenter Kotlin de production : pré-roll 2 s, fin après 1 200 ms de silence,
plafond 8 s, recouvrement 400 ms et préférence pour une pause au rollover.
La queue audio est de 800 ms sauf pour l'ablation explicite à 1 200 ms.

1. **Chaîne actuelle reproduite sur hôte** : trois vues Silero (original gain 1,
   original gain max 4, GTCRN compensé), politique actuelle de combinaison, puis
   confirmation FireRed non streaming sur chaque fenêtre finale. Le paramètre
   Silero max_speech_duration est explicitement fixé à 30 s comme en production.
2. **FireRed streaming seul** : modèle streaming officiel, seuil 0,5, moyenne sur
   cinq trames, début après huit trames positives, fin après vingt trames de silence.
   L'état début/fin du post-traitement officiel commande le segmenter, pas le seul
   booléen de probabilité brute. Le gain FireRed est causal, limité à 64 et par la
   crête, sur chaque bloc de capture de 50 ms.
3. **FireRed non streaming en fenêtres glissantes** : nouvelle analyse toutes les
   500 ms sur les quatre dernières secondes disponibles. Les décisions servent au
   découpage ; les fenêtres glissantes ne sont pas directement toutes envoyées à
   Qwen. Les recouvrements sont consolidés par le segmenter.

Variantes supplémentaires : fenêtres glissantes de 8 s ; décisions glissantes
retardées de 1,6 s pour bénéficier du contexte suivant ; confirmation finale
FireRed des segments streaming/glissants ; queue audio portée de 800 à 1 200 ms
pour le streaming. Les confirmations finales et la queue plus longue sont des
variantes exploratoires, évaluées après examen des premiers résultats.

Une décision glissante retardée n'est validée qu'après observation effective du
contexte requis. Aucun modèle ne voit la suite du fichier avant son instant
simulé. Aucun silence artificiel n'est ajouté à la fin pour provoquer une sortie.
Une partie finale non encore confirmée reste donc en attente, comme dans un flux
interrompu. Les contrôles synthétiques disposent déjà de silence après le mot.

## Résultats sur les entrées d'origine

| Architecture | Répétitions exactement reproduites /12 | Dont à 5 m /6 | Appels Qwen sans voix sur 500 s | Mots synthétiques reconnus /15 |
|---|---:|---:|---:|---:|
| Chaîne actuelle : Silero/GTCRN + FireRed | 9 | 3 | 0 | 7 |
| FireRed streaming seul, queue 800 ms | 11 | 5 | 6 | 5 |
| FireRed glissant 4 s, sans retard ajouté | 9 | 3 | 1 | 6 |
| FireRed streaming + confirmation, queue 800 ms | 11 | 5 | 0 | 5 |
| **FireRed streaming + confirmation, queue 1 200 ms** | **12** | **6** | **0** | **6** |

« Exactement reproduite » est une mesure stricte du texte : une substitution d'un
seul mot fait perdre l'exactitude de cette répétition, même si la parole a été
captée. Une occurrence complète doit se trouver dans une sortie Qwen individuelle.
Les comptes sont plafonnés au nombre attendu et les occurrences excédentaires sont
comptées séparément. Ce n'est pas un rappel acoustique avec annotations mot à mot.

Sur les 96 mots de référence des 12 répétitions, le taux d'erreurs de mots (WER)
est de 4,17 % pour la chaîne actuelle, 1,04 % pour streaming + confirmation avec
queue de 800 ms, et 0 % avec queue de 1 200 ms. Le streaming seul atteint pourtant
12,5 % de WER : ses bonnes phrases sont accompagnées de mots supplémentaires dans
les périodes sans parole des enregistrements vocaux. Compter uniquement les phrases
retrouvées aurait masqué ce problème.

Les six appels sans voix du streaming produisent des textes non vides. La fenêtre
sans voix admise par les variantes glissantes produit aussi un texte non vide.
La confirmation finale élimine les six appels streaming, mais ne supprime pas le
faux passage des variantes glissantes dans ce corpus. Ce sont des transcriptions
sans voix, **pas une mesure des alertes Guardian** : les règles d'urgence complètes
ne sont pas exécutées dans ce banc de test.

## Voix affaiblie : aucune architecture n'a tout résolu

| Architecture | Répétitions exactes à -6 dB /12 | À -6 dB et décalage de 5 ms /12 |
|---|---:|---:|
| Chaîne actuelle | 7 | 7 |
| FireRed streaming seul, queue 800 ms | 9 | 10 |
| FireRed glissant 4 s, sans retard ajouté | 8 | 8 |
| FireRed streaming + confirmation, queue 800 ms | 9 | 9 |
| FireRed streaming + confirmation, queue 1 200 ms | 10 | 8 |

Le meilleur résultat sur les enregistrements d'origine ne signifie donc pas une
victoire dans chaque variation. La confirmation peut encore refuser une fenêtre
faible utile. La queue de 1 200 ms améliore les originaux et un essai à -6 dB,
mais dégrade un autre essai par rapport à la queue de 800 ms.

Les fenêtres glissantes de 8 s ne sont pas meilleures : 8/12 répétitions exactes
sur les originaux, contre 9/12 pour les fenêtres de 4 s sans retard. Le retard
volontaire de 1,6 s ne donne pas non plus un gain régulier de précision.

## Mots courts : distinguer VAD et Qwen

Les dix variantes admettent les **15/15 mots synthétiques** : aucune de ces entrées
ne disparaît complètement avant Qwen. La reconnaissance correcte est pourtant
comprise entre 5/15 et 7/15 selon le découpage. Le résultat antérieur « 15/15
conservés » concernait seulement l'admission, pas une transcription juste.

Le candidat avec queue de 1 200 ms récupère un mot perdu avec la queue de 800 ms,
mais conserve une régression sur « non » au niveau synthétique le plus faible
(-60 dBFS nominal) : la chaîne actuelle le reconnaît et le candidat ne le reconnaît
pas. Il n'est donc pas acceptable de considérer les mots courts comme résolus.

Ces mots sont synthétiques et fortement atténués après la préparation du signal.
Ils ne reproduisent pas toute la chaîne microphone/gain d'un véritable téléphone.
Les taux 6/15 et 7/15 ne sont pas des taux de réussite d'urgence en situation réelle.
Il faut de vrais mots courts, prononcés doucement par plusieurs personnes.

## Latence et calcul

Dans les 32 variantes d'entrée, porter la queue de 800 à 1 200 ms ne change **aucun
instant de remise des fenêtres** au décodeur : le moteur attendait déjà 1 200 ms
pour clôturer. Il conserve simplement jusqu'à 400 ms supplémentaires de son déjà
observé. Cela peut néanmoins augmenter le travail et le temps de décodage Qwen.

Les fenêtres glissantes avec retard de 1,6 s ajoutent effectivement ce contexte
futur observé, plus l'arrondi du pas de 500 ms. Ce retard ne doit pas être présenté
comme du streaming instantané. Sur les contrôles synthétiques reconnus, leur
retard médian après la fin nominale du fichier-mot est proche de 2,82 s avant Qwen,
contre environ 1,03 s pour le candidat streaming. Ces médianes portent sur des
sous-ensembles de mots reconnus différents ; elles ne mesurent pas une latence
Android et ne remplacent pas une annotation de fin de parole réelle.

Temps mural du détecteur sur hôte, par seconde d'audio des entrées d'origine :

| Architecture | Secondes de calcul de détection/confirmation par seconde audio |
|---|---:|
| Chaîne actuelle reproduite | 0,086 |
| FireRed streaming + confirmation, queue 1 200 ms | 0,016 |
| FireRed glissant 4 s | 0,029 |
| FireRed glissant 8 s avec retard | 0,054 |

Ces chiffres concernent les implémentations de replay Python/ONNX et leurs
appels natifs, modèles déjà chargés. Ils excluent le démarrage JVM du replay,
la capture, les routes/micros, l'ordonnancement Android et le décodage Qwen.
La reproduction actuelle recalcule GTCRN sur tout le flux ; la disponibilité et
l'activation dynamique réelles de GTCRN sur téléphone ne sont pas reproduites
exactement. On ne peut donc en déduire ni un facteur d'économie de batterie,
ni un gain CPU identique dans l'application. Laisser GTCRN actif ailleurs dans
le moteur conserverait aussi son coût.

## Vérifications et limites de la reproduction

- 320 lignes de replay : 24 entrées, variations de niveau pour les quatre fichiers
  vocaux, dix architectures/variantes. Ce ne sont pas 320 essais indépendants.
- 235 inférences Qwen réellement exécutées ; les fenêtres byte à byte identiques
  partagent un cache vérifié par empreinte de l'audio, des modèles et des réglages.
  Un résultat en cache n'est jamais chronométré comme une nouvelle inférence.
- Qwen local Sherpa 1.13.8, français, deux threads, max_total_len 512,
  max_new_tokens 256 ; audio original des fenêtres, pas de débruitage supplémentaire.
- Segmenter Kotlin de production pour les architectures de base. Pour la queue
  de 1 200 ms, copie de recherche ne changeant que cette constante ; aucun fichier
  de production n'est modifié.
- Les blocs PCM produits par le segmenter sont vérifiés contre les blocs originaux.
  Un rollover au plafond peut omettre du silence sans stocker une fenêtre contiguë
  unique ; le banc vérifie les morceaux dans l'ordre au lieu de supposer un seul
  intervalle. Aucune transformation de la voix n'est ajoutée pour Qwen.
- Trois contrôles avec modèles réels vérifient qu'étendre le fichier ne modifie
  pas les décisions déjà engagées sur un préfixe de huit secondes.
- 119 confirmations vérifiées contre le cœur NCNN de la livraison v3.3 : aucune
  décision différente pour les fenêtres de la chaîne actuelle et du nouveau
  candidat avec queue 1 200 ms. La détection streaming reste un replay ONNX,
  pas une intégration JNI streaming Android.
- Le baseline recalcule GTCRN et les VAD à partir du WAV ASR quantifié. Il reproduit
  la logique de combinaison, mais pas bit à bit les états natifs, le routage
  adaptatif, la disponibilité de l'enhancement et les délais du téléphone.
- Tous les originaux réels viennent du même téléphone et du même contexte de tests.
  Une seule phrase répétée ne représente pas toutes les demandes d'aide.
- Aucune annotation phonétique manuelle ne permet ici d'attribuer chaque erreur
  précisément au VAD, au bord de fenêtre ou au modèle Qwen.
- Aucun APK, aucun téléphone et aucun service distant n'ont été utilisés/modifiés.
  Aucun WAV, texte reconnu ni empreinte d'une voix privée n'est publié.

## Suite recommandée

Prototyper **FireRed streaming → découpage préservant le début → confirmation
FireRed non streaming → Qwen sur original**, et comparer les queues de 800/1 200 ms
sur téléphone. Ce prototype pourrait remplacer Silero comme premier détecteur ;
ce n'est pas le branchement actuellement livré dans v3.3.

Avant activation par défaut : vérifier le chargement natif streaming, mesurer
les temps et les pertes de frames, puis tester des vrais mots d'urgence courts à
1 m/3 m/5 m, avec et sans bruit, et sur d'autres téléphones. La régression du mot
très faible doit rester un critère bloquant de validation, pas être noyée dans
le meilleur score sur les longues phrases. Un jeu neuf doit départager les variantes.

La v3.3 reste une base expérimentale avec confirmation FireRed. Cette étude donne
une raison mesurée d'explorer un premier détecteur FireRed, sans prétendre qu'un
simple remplacement par FireRed streaming seul supprime les faux passages.

## Fichiers reproductibles

`etude-vad-complet/` contient la préparation des manifests privés, le replay Kotlin,
la comparaison causale, les variantes de confirmation, le décodage Qwen avec cache,
les contrôles de causalité et les synthèses agrégées. Les empreintes des modèles
et les nombres de validations figurent dans `validation-summary.json`.

Les données et les sorties détaillées restent privées. Le manifeste requis contient
`id`, `label`, `path`, et pour les voix `expected`/`expectedRepetitions`. Les fichiers
sont mono 16 kHz PCM16 WAV ou float32 little-endian `.f32`. Voir le README du dossier
pour les commandes et dépendances.
