# Étude élargie des filtres classiques — 26 octobre 2026

## Conclusion

La meilleure nouvelle option sur le corpus contrôlé est **un Wiener à estimation décision-dirigée du signal utile, avec suivi adaptatif du bruit** : **23 énoncés exacts et admis sur 24**, contre **22/24 pour l’original dans ce rejeu**. Les quinze anciens énoncés au calme sont conservés, dont les six mots courts et les trois phrases à 5 m. Aucun des treize candidats négatifs contrôlés n’est admis.

La première phrase du dernier test sous aspirateur reste incorrecte. Le score ne dépasse pas le résultat déjà observé sur téléphone pour les neuf phrases récentes : il passe de 7/9 à 8/9 sur l’hôte, alors que le téléphone avait déjà obtenu 8/9 sur l’original. Le traitement n’est donc pas une amélioration démontrée sur Android.

L’ancien Wiener conserve son 9/9 récent, mais régresse sur deux anciennes phrases et rouvre un ancien négatif au calme. Son caractère trop favorable au démarrage est isolé par un contrôle de disponibilité causale décrit plus bas. Il ne constitue pas un gagnant universel.

**Décision : conserver le découpage original et tester le nouveau traitement dans une voie parallèle. Ne pas le placer en permanence devant le VAD, ne pas supprimer l’original, et ne pas activer une règle de bruit à seuil absolu à partir de ces seules prises.** Le moteur Android v3.4.5 n’a pas été modifié par cette étude.

## Protocole et portée

**474 nouveaux décodages Qwen locaux**, sans envoi des voix à un service externe :

| Étape | Contenu | Décodages |
| --- | --- | ---: |
| Première grille | 26 configurations × 3 phrases bruitées et 5 candidats négatifs récents | 208 |
| Non-régression | 4 familles retenues + original et ancien Wiener, sur 21 autres énoncés et 8 anciens négatifs | 174 |
| Transfert | 4 méthodes sur 9 anciens énoncés bruités, avec deux coupes corrélées chacun | 72 |
| Rejeu intégral | Qwen sur tous les candidats admis par deux nouveaux traitements avant FireRed | 20 |
| **Total** | | **474** |

Les 26 configurations comprennent **24 nouvelles variantes**, l’original et la référence Wiener précédente. Paramètres fixés avant la grille initiale, sans retouche par phrase ou prise. Sélection automatisée de quatre familles distinctes : d’abord aucun négatif admis, puis exacts admis, préservation des succès bruités du téléphone et erreurs en mots. Les références restent présentes dans la seconde étape. La sélection et ses dénominateurs sont publiés dans `etude-filtres-classiques/results/`.

Les résultats exacts **après admission acoustique** priment sur l’ASR forcé. Une confirmation manquante ou une ligne dupliquée provoque une erreur du scorer. Les treize négatifs sont des candidats uniques par prise/segment ; les deux variantes de préroll de l’ancienne étude ne sont pas comptées comme deux observations.

Le corpus principal comprend neuf énoncés récents et quinze anciens au calme, avec répétitions des mêmes trois phrases et trois mots courts. Il ne contient pas 24 textes différents. Les anciens contrôles ont déjà été examinés dans les études précédentes : ils ne deviennent pas un jeu aveugle indépendant. La première grille porte sur une seule ambiance d’aspirateur. La généralisation à d’autres téléphones, voix, langues et bruits reste à établir.

Qwen utilise les mêmes fichiers locaux, français imposé, CPU deux threads, limites 512/256. FireRed est la vraie confirmation JNI avec la politique de production, sans abaissement de seuil. Les paramètres et empreintes des modèles sont fournis, aucune empreinte vocale n’est publiée.

## Familles examinées

- **Wiener avec soustraction de puissance suivie d’une racine**, formule de la précédente étude : différentes forces et planchers.
- **Wiener en puissance**, atténuation directe du bruit estimé, planchers de 0,25 à 0,7.
- **Wiener décision-dirigée (DD)** : estimation du signal utile combinant le bloc courant et l’estimation passée ; plusieurs constantes temporelles.
- **Log-MMSE d’Ephraim–Malah**, gain d’amplitude logarithmique conditionnel à la parole, avec estimation DD et planchers conservateurs.
- **Soustraction spectrale généralisée**, exposants 1 et 1,5 et plancher adouci.
- **Suivi adaptatif du bruit**, pondéré par une estimation approximative de présence de signal utile, associé au Wiener, au DD ou au log-MMSE.
- **Protection de bande**, atténuation limitée dans 250–4 000 Hz.
- **Bypass progressif selon le rapport signal/bruit estimé**, sans seuil absolu de niveau et sans détection textuelle.
- Fenêtres de 512 ou 1 024 échantillons selon les variantes, lissages temporel et fréquentiel fixes.

Le suivi adaptatif est une approximation explicitement documentée ; il n’est pas présenté comme une implémentation de MCRA, IMCRA ou OM-LSA. Aucun transcript, signal propre de référence ou vocabulaire composé des phrases attendues n’entre dans les filtres. Aucun gain global, normalisation ou écrêtage de sortie n’est ajouté. Un masque spectral borné à 1 peut néanmoins accroître ponctuellement une crête temporelle par modification des annulations de phase ; les tests vérifient les masques et la stabilité, pas une fausse promesse de crête toujours décroissante.

## Résultats complets sur les candidats originaux

| Traitement | Récents exacts admis / 9 | Anciens au calme / 15 | Total / 24 | Négatifs admis / 13 |
| --- | ---: | ---: | ---: | ---: |
| Original, hôte | 7 | **15** | 22 | **0** |
| **Wiener DD adaptatif** | **8** | **15** | **23** | **0** |
| Log-MMSE adaptatif | 8 | 14 | 22 | 0 |
| Wiener DD avec bruit fixe | 8 | 14 | 22 | 0 |
| Soustraction généralisée, puissance 1,5 | 8 | 13 | 21 | 0 |
| Ancien Wiener | **9** | 13 | 22 | **1** |

Le Wiener DD adaptatif conserve les 6/6 mots courts et les 3/3 prises à 5 m de ces contrôles. Ses 24 fenêtres parlées sont admises ; une transcription bruitée reste fausse. Deux variantes DD plus agressives de la grille initiale admettent un négatif récent tardif et sont écartées de la sélection.

« Aucun négatif admis sur 13 » n’est pas une garantie d’absence de faux déclenchement sur une écoute prolongée, ni une mesure des règles métier d’urgence. Les reconnaisseurs ont été forcés aussi sur les négatifs pour analyse ; ces textes ne deviennent pas des publications lorsque FireRed refuse la fenêtre.

## Le découpage doit rester sur l’original

Les deux premières familles sélectionnées, soustraction généralisée et DD adaptatif, ont aussi été rejouées **avant le VAD** sur les trois prises complètes. Les blocs ont les tailles et séquences exactes du téléphone ; aucun flush de silence artificiel n’est ajouté. Tous les nouveaux candidats admis sont décodés.

| Chaîne avant VAD | Calme exacts / 6 | Bruit exacts / 3 | Sorties non vides dans la prise bruitée | Sortie hors des fenêtres parlées originales |
| --- | ---: | ---: | ---: | ---: |
| Original, référence de rejeu | 6 | 1 | 2 | 0 |
| DD adaptatif → FireRed → Qwen | 6 | 1 | 4 | **1** |
| Soustraction généralisée → FireRed → Qwen | 6 | 2 | 4 | **1** |

Le changement de frontières et un texte supplémentaire en fin de prise annulent le bénéfice attendu. Un filtre meilleur sur des segments donnés ne devient donc pas automatiquement un meilleur front-end pour le détecteur.

La voie prometteuse est : **original → détection/découpage ; filtre continu parallèle → extraction du même intervalle ; confirmation et ASR sur ce candidat**. Réouvrir un candidat rejeté reste une décision à valider, même lorsque les petits contrôles négatifs passent.

## Transfert aux anciennes prises bruitées

Ce contrôle utilise neuf énoncés dans trois prises, à deux positions de coupe fixes. Les 18 fenêtres sont corrélées deux à deux ; ce ne sont pas 18 nouveaux énoncés ni un nouveau découpage Android.

| Traitement | Coupes exactes et admises / 18 | Énoncés exacts aux deux coupes / 9 |
| --- | ---: | ---: |
| Original | 0 | 0 |
| Ancien Wiener | 5 | 2 |
| Soustraction généralisée | 5 | 1 |
| DD adaptatif | 4 | 2 |

L’original avait deux transcriptions exactes en ASR forcé, mais la confirmation refuse ces fenêtres. Ce résultat ne contredit pas les scores antérieurs obtenus avec d’autres frontières réelles. Les filtres récupèrent une partie de l’information, mais l’ensemble reste insuffisant. Le meilleur résultat récent ne se généralise donc pas à toutes les anciennes conditions bruitées.

## Démarrage, causalité et calibration

La précédente référence estimait le bruit sur les premières secondes puis appliquait ce profil également au début du fichier. Deux des cinq négatifs récents commencent avant trois secondes : leur traitement utilisait potentiellement une estimation pas encore disponible au moment réel de la capture.

Toutes les nouvelles variantes transmettent l’original jusqu’à trois secondes, puis passent au traitement sur un fondu de 100 ms. Leur profil n’utilise que des fenêtres STFT entièrement contenues dans 0,5–3 s. Cela suppose **un intervalle sans parole confirmé par le protocole**, pas une calibration universelle valide si quelqu’un parle dès le démarrage.

Les tests de préfixe changent la fin du signal : les sorties déjà finalisées ne doivent pas bouger. La borne conservatrice analyse + recouvrement-addition est une fenêtre complète, soit 32 ms à 512 points ou 64 ms à 1 024 points. Les tests portent aussi sur la reconstruction, le silence, les petits tableaux, les formules analytiques DD/log-MMSE et l’identité de la référence précédente.

Un contrôle supplémentaire de compatibilité, annoncé après la grille initiale, montre qu’on peut garder la formule exacte de l’ancien Wiener en attendant sa disponibilité réelle : profil connu au plus tard à 3,008 s, amorçage interne sur le préfixe mémorisé, original jusqu’à 3,04 s et fondu jusqu’à 3,14 s. Au-delà de 3,14 s, le PCM est identique à celui de l’ancien filtre. Pour tout candidat commençant avant cette limite, **confirmation et ASR** prennent entièrement l’original ; on ne change pas de voie au milieu de ce candidat.

Les 37 fenêtres correspondent alors bit à bit soit à la référence originale, soit à l’ancien filtre déjà décodé. Les résultats sont réutilisés, **pas comptés comme de nouveaux décodages** : 22/24, zéro négatif admis sur les treize contrôles. Ce contrôle corrige le démarrage, pas les deux régressions de parole au calme. Il ne prouve ni une calibration sûre en présence de parole initiale, ni un déclenchement automatique du débruitage.

## Limites d’une activation conditionnelle

Sur ces prises, le bruit initial de la capture aspirateur est nettement plus fort et plus aigu que celui des prises calmes. Mais un seuil absolu à −50 dBFS ne laisse que 3 dB de marge à la prise calme la plus proche ; un gain d’environ ×1,42 suffirait à la faire changer de classe. Une telle règle serait dépendante de la route et du téléphone et est trop adaptée à ces données. Elle n’est pas retenue comme solution validée.

Un stress synthétique ajoute 12 dB de bruit après quatre secondes. Le DD adaptatif atténue environ 6 dB le bruit connu, mais seulement 1,6 dB juste après le changement et environ 2 dB plusieurs secondes plus tard. Ce suivi simple réagit donc trop lentement à certains changements d’ambiance. Cette mesure est un contrôle de fonctionnement du filtre, pas un score de reconnaissance sur une voix réelle.

## Recommandation

- Garder la capture multicanale v3.4.5 à vérifier sur téléphone : la présente étude ne recrée pas les canaux absents des trois prises mono.
- Tester le **DD adaptatif comme voie secondaire sur les segments originaux**, avec l’original conservé et une calibration ne contenant pas de parole.
- Ne pas traiter en permanence le signal envoyé au détecteur ; ne pas augmenter globalement le gain pour compenser.
- Valider les mots courts, le démarrage immédiat avec parole, les changements de bruit, les transitions de canal et de nouveaux énoncés sur appareil avant d’activer cette voie dans le produit.

Les 19 tests numériques et de scoring passent. Les poids Qwen et la confirmation native restent inchangés. Aucun APK, aucun déploiement, aucune amélioration de batterie ni supériorité sur toutes les langues ou tous les téléphones n’est revendiqué.

Scripts et agrégats : `etude-filtres-classiques/`. Les WAV, textes reconnus, identifiants d’enregistrement et empreintes vocales restent privés.
