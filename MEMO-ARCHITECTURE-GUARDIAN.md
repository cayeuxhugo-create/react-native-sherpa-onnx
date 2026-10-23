# Architecture retenue pour poursuivre Guardian Voice

Décision de travail conservée à la demande de l'utilisateur le 22 octobre 2026.
Ce mémo est le point de reprise du projet ; il ne signifie pas que la version
expérimentale est validée en production ou déjà installée sur le téléphone.

Chaîne principale retenue : capture continue → **FireRed streaming pour détecter
et découper → FireRed non streaming pour confirmer → Qwen local sur l'audio
original → règles Guardian**.

Paramètres du candidat étudié : 16 kHz mono, pré-roll 2 s, fin après 1 200 ms de
silence, queue audio 1 200 ms, plafond 8 s, overlap 400 ms, pause préférée au
rollover. Streaming : seuil 0,5, lissage 5 trames, minimum parole 8 trames,
minimum silence 20 trames. Confirmation : seuil 0,5, lissage 5 trames,
min_speech_frame=20 (confirmation effective à la 21e trame positive).
Gain max 64, limité par la crête, sur les copies FireRed ; Qwen reçoit l'original.

Sur le replay hôte : 12 répétitions exactement reconnues sur 12, dont six à 5 m ;
aucun appel Qwen pendant les 500 s sans voix. Les 15 mots synthétiques sont admis,
mais Qwen n'en reconnaît correctement que 6/15, contre 7/15 pour la chaîne actuelle.
Le « non » synthétique le plus faible régresse ; les variantes à -6 dB restent
imparfaites. Aucune validation multi-téléphones, de batterie ou d'alertes réelles.

Version intégrée le 23 octobre : **v3.4 FireRed streaming**. Le module remplace
le prédécoupage Silero/GTCRN par FireRed streaming, puis confirmation FireRed
non streaming et Qwen sur l'original. GTCRN est désactivé pour cette chaîne.
Les 15 492 décisions de capture et les 27 fenêtres admises du replay JNI sont
identiques au prototype étudié ; APK et téléphone restent à valider.
Guide : [livraison v3.4](LIVRAISON-FIRERED-STREAM-V3.4.md).
La précédente livraison v3.3 (fd3c96d180113dd2ab42033c768ed309705f5087)
conservait Silero/GTCRN ; elle reste une version de retour possible.

Étude de référence : [comparaison sur les flux complets](ETUDE-ARCHITECTURE-VAD-COMPLET-20261022.md),
commit 9d0d1c38b0e47f2fff2d864ae2d5fbaa4161e848.

Recherche complémentaire autorisée : modèles spécialisés de détection des mots et
phrases d'urgence, avec priorité au français, au fonctionnement local sans réseau,
aux mots courts et à la voix douce/distante. Cette voie doit être indépendante du
blocage du VAD principal ; elle ne doit pas être présentée comme un détecteur
sémantique de danger sans validation. Un score de mot-clé ne constitue pas à lui
seul une alerte confirmée.

Ne pas changer le téléphone, la sélection des micros ou la chaîne livrée au seul
motif d'un résultat synthétique. Séparer clairement code disponible, poids
accessibles, essais réellement exécutés et besoins d'entraînement/licence.

Comparaison ASR du 23 octobre : [Qwen, Parakeet v3 et Whisper](COMPARAISON-RECONNAISSEURS-20261023.md).
Sur les sorties FireRed françaises, Qwen reste la référence (12/12 phrases,
dont 6/6 à 5 m). Les mots courts, la couverture européenne et les performances
Android restent à valider ; aucun remplacement de production issu de ce test.

Mise à jour v3.4.1 : voie GTCRN indépendante en comparaison uniquement pendant
un diagnostic NOISY avec WAV. Aucun texte ni alerte issus de cette voie ;
FireRed et Qwen continuent sur l’original. Paires original/GTCRN avec index
et pertes explicites. Valider le bruit réel avant tout secours automatique.
Guide : [comparaison GTCRN](LIVRAISON-GTCRN-COMPARAISON-V3.4.1.md).
