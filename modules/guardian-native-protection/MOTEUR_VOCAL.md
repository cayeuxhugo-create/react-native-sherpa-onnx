> Version actuelle v3.2 : lire `ETUDE-MOTEUR-V3.2.md` et `GUIDE-ENREGISTREMENT-SIMPLE.md`. Les sections v3/v3.1 ci-dessous sont historiques. GTCRN assiste maintenant le VAD en DISTANT sur matériel recommandé ; Qwen garde le PCM original ; cinq WAV et pré-enregistrement de 2 s.

> Mise à jour v3.1 : utiliser `DIAGNOSTIC-SIMPLE-V3.1.md` et `diagnostics/enregistrer-guardian.ps1` pour la collecte. Les WAV sont désormais privés et le script les active sans changement JavaScript. Les instructions de collecte externes ci-dessous décrivent la version v3 précédente.

# Moteur vocal v3

## Deux profils, une capture, un décodeur

| Profil | Détection | Entrée Qwen |
| --- | --- | --- |
| DISTANT | Silero sur la voie principale, avec gain limité réservé au VAD | Voie principale avec son gain existant |
| NOISY | Même autorisation principale ; GTCRN peut soutenir 600 ms après une détection principale | Même voie principale préservant les consonnes |

GTCRN ne peut pas ouvrir une prise de parole à lui seul. Dans ces essais,
remplacer directement le signal Qwen par le signal débruité dégradait les
mots et pouvait transformer du bruit en fausse présence vocale. La v3
utilise le débruitage uniquement comme soutien bref de la détection.
Le RMS, les étiquettes Far Voice et les mots proposés par Qwen ne peuvent
pas autoriser une prise de parole.

## Corrections

- Vue Silero amplifiée au maximum ×4, limitée par le pic du bloc ; le PCM
  remis à Qwen reste inchangé. Le seuil neural reste 0,5 et les durées
  minimales parole/silence restent 128 ms, à 16 kHz.
- Pré-roll de 1 s pour les attaques faibles et confirmations VAD tardives.
  Fin après 1,2 s sans détection ; queue conservée jusqu'à 800 ms.
  Les hypothèses partielles restent espacées d'une seconde d'audio.
- Limite technique de 8 s et chevauchement de 400 ms pendant la parole.
  Si la fin silencieuse atteint cette limite, attendre sa confirmation
  dans le même segment borné, sans créer une nouvelle fenêtre de silence.
- Entrée GTCRN relevée au maximum ×6, limitée par le pic, puis compensée
  avec l'historique du gain correspondant aux échantillons d'entrée.
  La compensation accepte les sorties natives retardées ou partielles.
- En NOISY, un deuxième Silero suit la vue débruitée. Il ne peut que
  soutenir les 600 ms suivant une confirmation de la voie principale.
  Il ne maintient pas la parole indéfiniment sur du bruit nettoyé.
- Capture/gain existants conservés. Jointure par numéro ET horodatage,
  repli borné après 200 ms, entrées désordonnées rejetées. Discontinuité
  ou saturation : abandon du segment, reset des deux VAD et invalidation
  des résultats Qwen en vol. Un modèle VAD manquant provoque une erreur
  explicite, sans repli vers une admission fondée sur le seul volume.

La transcription reste celle de toutes les paroles audibles, y compris
une personne autour du téléphone. Il n'y a pas de sélection du locuteur.

## Installation, collecte et validation

Suivre `diagnostics/LIRE-MOI.md` : remplacement du module, conservation de
l'AAR/modèles Qwen et de la configuration applicative, reconstruction Android.
`voiceProfile` accepte DISTANT (défaut) et NOISY ; `voiceDiagnosticsEnabled`
est faux par défaut. Les changements nécessitent un arrêt et un nouveau
démarrage complet de la protection. getNativeConfig expose ces réglages.

Les logs structurés indiquent révision, profil, voie ASR, vues VAD, gain,
segments, pertes, attente/durée Qwen et publications. Les enregistrements
mono sont facultatifs, limités et distincts des réglages de capture.

Tests locaux : `tests/run-host-tests.sh`, `tests/check-reference-api.sh`.
Si Kotlin n'est pas installé, fournir KOTLIN_COMPILER_CP et KOTLIN_STDLIB.
La vérification d'API compile avec les sources officielles Sherpa 1.13.8
vérifiées par SHA-256 et des stubs Android ; SHERPA_AAR permet d'indiquer
votre véritable AAR pour une vérification de signatures.
Ce contrôle n'est pas une compilation complète d'APK ni une exécution JNI Android.
Les modèles Silero/GTCRN restent ceux fournis avec leurs licences dans assets.
Les résultats de rejeu des vrais modèles sont dans `tests/results/v3-replay.json`
et `tests/results/v3-noise.json`. Voir aussi `CORRECTIONS-V3.md`.
