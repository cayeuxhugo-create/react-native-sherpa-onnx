# Continuité des fenêtres après huit secondes

Ce correctif de source conserve les vrais échantillons lorsque la parole reprend après un court silence au-delà du tampon de huit secondes. Avant correction, ces blocs silencieux étaient comptés pour le délai de fin, mais omis du PCM si la parole reprenait avant ce délai. La fenêtre suivante concaténait deux portions non contiguës tout en annonçant une tranche continue. Cela pouvait fausser la comparaison avec les canaux conservés dans l’historique.

Un tampon borné à 1,2 seconde garde maintenant ces échantillons. Si la parole reprend, ils sont réinjectés après le découpage et avant la nouvelle parole, sans recompter le temps. Si la phrase se termine naturellement ou qu’une rupture réinitialise le pipeline, le tampon est vidé. Les délais, le plafond de huit secondes, le chevauchement de 400 ms et les limites de coupe sur pause restent inchangés.

Les tests utilisent des échantillons synthétiques uniques et normalisés pour vérifier la correspondance exacte entre chaque PCM et son intervalle source. Les décisions VAD déjà enregistrées ont aussi été rejouées sans relancer les modèles : 52 fenêtres sur huit voies sont désormais exactes. Les cinq fenêtres principales des deux essais restent identiques, avec leurs bornes et leurs octets. Le défaut n’avait pas été déclenché dans ces cinq fenêtres ; il ne faut donc pas lui attribuer les phrases manquées de ces essais.

Les résultats agrégés sont dans `modules/guardian-native-protection/tests/results/segmenter-clock-continuity-summary.json`. Aucune voix ni transcription privée ne figure dans ces tests ou ce rapport public.

Cette branche fournit le code et les vérifications. Ce correctif n’est pas encore dans l’APK testé ni dans son archive d’installation v3.5.0 ; son intégration à une prochaine livraison Android demandera une nouvelle compilation. Le correctif PowerShell 5.1 du collecteur est indépendant et reste utilisable avec l’APK actuel.
