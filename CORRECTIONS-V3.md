# Corrections du moteur vocal v3

Cette version modifie le moteur : contexte vocal plus long, gain limité réservé
aux modèles, signal original conservé pour Qwen et débruitage utilisé comme
soutien bref après confirmation vocale principale. Elle corrige aussi la
création d’une seconde fenêtre silencieuse lorsque la fin atteint 8 secondes.

## Mesures avant/après

Une référence française publique de 16 mots, provenant du paquet officiel
Qwen3-ASR 0.6B int8 du 25 mars 2026 ; Sherpa 1.13.8. Même voix atténuée,
quantifiée PCM16, gain de capture simulé fixe ×6 et bruit coloré synthétique.
Routage, gain neural, règles de soutien et segmentation Kotlin exécutés ;
vrais modèles natifs Silero, GTCRN et Qwen. Fenêtres finales seules évaluées ;
les modèles Android et la capture microphone ne sont pas exécutés ici.
Les durées Qwen du JSON sont celles du serveur de test, pas du téléphone.

Référence : « Alice et moi sommes allés à Paris voyager en train au printemps, c'était très amusant. »

Les nombres sont des distances d’édition de mots sur 16 mots, sans la casse
ni la ponctuation. Ce ne sont pas des scores de confiance. Le niveau indiqué
est celui de la référence atténuée avant le gain simulé ; ce n’est pas une distance.

| Cas | Profil | v2 : erreurs / 16 | v3 : erreurs / 16 |
| --- | --- | --- | --- |
| −50 dBFS, calme | DISTANT | 0 | 0 |
| −50 dBFS, calme | NOISY | non mesuré | 0 |
| −50 dBFS, bruit, SNR +15 dB | DISTANT | 0 | 1 |
| −50 dBFS, bruit, SNR +15 dB | NOISY | 0 | 1 |
| −50 dBFS, bruit, SNR +5 dB | DISTANT | 14 | 1 |
| −50 dBFS, bruit, SNR +5 dB | NOISY | 13 | 1 |
| −60 dBFS, calme | DISTANT | 0 | 0 |
| −60 dBFS, calme | NOISY | non mesuré | 0 |
| −60 dBFS, bruit, SNR +15 dB | DISTANT | 1 | 0 |
| −60 dBFS, bruit, SNR +15 dB | NOISY | 0 | 0 |
| −60 dBFS, bruit, SNR +5 dB | DISTANT | 14 | 0 |
| −60 dBFS, bruit, SNR +5 dB | NOISY | 17 | 0 |
| −70 dBFS, calme | DISTANT | 1 | 1 |
| −70 dBFS, calme | NOISY | 1 | 1 |
| −24 dBFS, calme | DISTANT | 1 | 1 |
| −24 dBFS, calme | NOISY | 0 | 1 |

Le cas difficile à +5 dB SNR s’améliore fortement. Dans le bruit plus léger
à −50 dBFS, une erreur apparaît alors que la v2 en faisait zéro. Une erreur
reste aussi dans plusieurs cas supplémentaires, souvent sur le nom propre.
Ces résultats ne constituent donc pas une amélioration universelle ni une
validation générale sur plusieurs locuteurs.

## Bruit seul et garde-fous

16 séquences de 30 secondes : bruit blanc, bruit rose, ventilateur harmonique
et bruits impulsifs, chacun à −60 et −30 dBFS, dans les deux profils.
Résultat v3 : **0 fenêtre finale dans les 16 cas**, donc aucun décodage Qwen.
Les deux cas de bruit seul de 10 secondes du rejeu vocal ne déclenchent pas non plus.
Le VAD principal est obligatoire : le bruit débruité ne peut pas ouvrir la parole.
Le soutien GTCRN expire après 600 ms sans nouvelle confirmation principale.

## Vérifications

- 16 tests du cœur : continuité, pré-roll, mots faibles, faux silence VAD,
  limite de 8 s, fin unique, routage borné, repli et entrées invalides.
- 4 tests de concurrence du décodeur et 4 tests des traces.
- 4 contrôles du gain neural/historique/soutien et 1 test de l’export WAV
  sur quatre voies : 29 contrôles au total, tous réussis.
- Compilation des adaptateurs, y compris GTCRN, contre l’API officielle
  Sherpa 1.13.8 vérifiée par SHA-256, avec stubs Android : réussie.
- Collecteur exécuté sous PowerShell 7 avec adb simulé : version v3 et
  profils vérifiés, quatre cas à 1 m, WAV mono identique après copie binaire,
  restauration des fichiers de contrôle et ZIP valide. Un ancien APK est
  refusé même s’il produit des traces au bon profil.
- L’AAR personnalisé de l’application, le projet Android complet, Windows
  et un téléphone réel n’étaient pas disponibles pour compiler/tester un APK.

## Premier essai sur téléphone

Suivre `LIRE-MOI.md` / `diagnostics/LIRE-MOI.md`. Commencer à **1 m**, dans le
calme puis avec un bruit constant sans voix. Le collecteur court utilise
D0_SILENCE, D1_VOICE, N0_NOISE, N1_VOICE. Les distances 3 et 5 m restent dans
le protocole `-Full`, à utiliser après validation à courte distance.
Une personne qui parle autour du téléphone est de la parole réelle et peut
être transcrite : il n’y a pas de sélection du locuteur.
