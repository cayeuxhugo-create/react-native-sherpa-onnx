# Étude — calibration du bruit, choix de canal et gain de parole

## Décision

Le correctif d'initialisation est intégré : une trame de démarrage entièrement nulle ne calibre plus le bruit ambiant. Les échantillons restent transmis. Les signaux faibles non nuls conservent les mêmes bornes de calibration. La trame suivante peut donc initialiser l'estimateur au lieu de comparer un bruit déjà présent à un plancher fictivement bas.

Un mode expérimental ajoute la sélection stable pendant la portion de parole et supprime le gain de capture dans cette voie. L'original avant gain pilote FireRed ; un gain constant par segment n'est permis qu'après confirmation neurale. Un gain de segment amplifie aussi le bruit inclus dans le segment : il ne sépare pas physiquement voix et bruit.

Le plafond recommandé pour ce candidat est **×1** : les essais ×2 et ×4 introduisent une régression. La possibilité de les comparer est conservée explicitement, sans activation automatique. L'étude ne démontre pas une récupération complète des phrases sous aspirateur.

## Corpus et méthode

Enregistrements réels privés sur un Samsung SM-S916U1, Android 16, français : silence, phrases douces à 1 et 3 m, six mots courts à 3 m, trois routes sous aspirateur, et trois anciennes phrases à 5 m. Pas de référence propre simultanée, ni de niveau sonore externe calibré. Les transcriptions et WAV ne sont pas publiés.

Rejeux avec le vrai FireRed JNI, les politiques et le segmenter de production. Dix-huit entrées initiales, puis deux rejeux finaux stéréo après ajustement de l'initialisation de la comparaison des canaux. La sélection stéréo est rejouée avec la même classe de production, le passe-haut mono 0,97 et un retour d'activité neurale synchrone sur le thread hôte. Sur Android, ce retour est asynchrone : les résultats ne garantissent pas des frontières identiques.

Qwen int8 0,6B, sherpa-onnx 1.13.8, French, CPU deux threads, max_total_len=512, max_new_tokens=256. **83 nouveaux décodages** : 38 premiers essais, 42 comparaisons supplémentaires de plafonds de gain et trois contrôles stéréo finaux. Pas de reconnaissance réseau. Les fenêtres initiales sont sauvegardées avant/après gain pour les comparaisons.

## Résultats

| Réglage de gain après confirmation | Éléments calmes corrects /15 |
|---|---:|
| Référence enregistrée | 15 |
| Candidat sans gain supplémentaire (×1) | 15 |
| Candidat, plafond ×2 | 14 |
| Candidat, plafond ×4 | 14 |

Les quinze éléments comprennent six phrases entre 1 et 3 m, six mots courts et trois anciennes phrases à 5 m. Les six mots courts et les trois phrases à 5 m restent corrects dans les variantes testées. La régression ×2/×4 porte sur une phrase calme à 3 m. Les modèles ne sont pas monotones en précision lorsque l'on augmente le niveau.

Le silence réel du corpus ne déclenche aucun appel Qwen confirmé dans le candidat. Ce résultat court ne qualifie pas un taux de faux positifs par heure, ni les autres langues ou téléphones.

Dans le rejeu fermé du WAV à deux canaux sous aspirateur, la sélection historique avec capture à gain unitaire admet une fenêtre sans phrase complète correcte ; le candidat à canal verrouillé et gain unitaire admet deux fenêtres, dont une phrase correcte. Le score passe ainsi de **0/3 à 1/3** sur cette comparaison hôte. La première phrase reste incorrecte et la deuxième n'est pas entièrement récupérée. Il s'agit d'un progrès local, pas d'une solution au bruit validée sur appareil.

Les variantes mono avant gain ne rétablissent pas les trois phrases sous aspirateur. Enlever le gain ne suffit donc pas. Les régressions observées avec ×2/×4 justifient de ne pas activer une amplification supplémentaire par défaut.

## Implémentation

- `GuardianNoiseFloorSeed` refuse uniquement le zéro numérique comme première mesure ; pas de période de silence imposée et pas de suppression de trame.
- En mode candidat, les planchers des canaux ne sont pas initialisés à partir des zéros de démarrage.
- Les scores de sélection restent fondés sur le contraste voix/bruit dans la bande utile, les limites de saturation et les contraintes de validité. Ils ne deviennent pas une mesure directe d'exactitude ASR.
- La vue est choisie au début de la portion signalée par le VAD, puis tenue jusqu'à sa fin. Un canal invalidé autorise un repli. Une transition conserve le fondu existant. Aucune géométrie physique de microphones n'est inventée.
- Le gain de capture est désactivé pour le candidat. La copie correspondante avant gain est exigée dans le processeur ; un tampon absent ou incohérent n'est pas remplacé silencieusement par une version amplifiée.
- FireRed confirme avant tout gain de segment. Le coefficient est constant pour la fenêtre entière, plafonné, sans modification de l'entrée originale. Une confirmation peut elle-même être erronée : le libellé signifie preuve du modèle, pas certitude absolue de parole.
- Aucun seuil FireRed ni durée de segmentation n'a été modifié. GTCRN reste en comparaison.

## Validation et limites

Tests host : zéros de démarrage, signal très faible non nul, gain refusé sans confirmation, coefficient constant, absence de mutation, conservation d'un canal pendant la parole, repli et remise à zéro. Test d'intégration avec vrai JNI et faux décodeur : PCM attendu au décodeur, modes ORIGINAL et CANDIDATE, plafonds 1/2/4, malgré un faux gain de capture injecté et le recyclage des tampons. Compilation des interfaces avec stubs Android ; elle ne constitue pas une compilation complète de l'application.

Le nouveau collecteur contrôle le mode appliqué, restaure le fichier de commande, récupère le WAV multicanal omis précédemment et peut appeler le répartiteur de ZIP local au-delà de 32 Mio. La collecte est vérifiée avec ADB simulé, y compris erreur de mode et WAV stéréo manquant.

La correction d'initialisation est active dans le mode de référence. Son effet complet sur le contrôleur historique de gain Android n'est pas reproduit par le banc : les fichiers historiques contiennent déjà le gain appliqué par l'ancien build. Le candidat contourne ce contrôleur en gardant le gain de capture à un.

Les choix de canal/gain restent activés uniquement pendant un diagnostic WAV explicitement demandé. Ils ne deviennent pas automatiquement la politique habituelle. Aucune nouvelle APK n'a été construite ici, aucun téléphone n'a exécuté ce code modifié. La prochaine validation doit confronter le candidat à la référence avec le même appareil et les mêmes conditions. Il reste du travail sur les phrases masquées par le bruit ; aucun résultat ne justifie de prétendre à une performance générale sur toutes les langues européennes.
