# Guardian : résilience et étude CPU

Branche de travail : `codex/guardian-resilience-cpu`, base `a39e884311f5b7a5aff955309e89d286ca215dfd`.

## Corrections réalisées

- Exceptions des consommateurs Qwen isolées : elles ne bloquent plus le drain, même si le rapporteur d’erreur échoue. Les événements QWEN_PUBLISH ne déclarent pas un callback en échec comme publié.
- Valeurs NaN et infinies refusées pour les finales et partielles principales.
- Inférence Qwen principale dépassant 30 s signalée par un watchdog. C’est un signal de dégradation, pas une interruption native ni une libération dangereuse du modèle ; un résultat ultérieur peut rétablir l’état. Un appel natif réellement bloqué n’est pas tué de force.
- Pannes/saturation remontées dans la santé du moteur ; heartbeat de capture indépendant des résultats ASR. La saturation d’entrée peut être signalée par le producteur même si le worker natif ne répond plus.
- Alertes en échec SQLite conservées avec leur identifiant pour réessai, sans faire avancer l’horodatage de confirmation durable. Journal de secours atomique sous noBackupFilesDir, sans audio ; réimport idempotent au démarrage et aux heartbeats du service. Une entrée corrompue est conservée et signalée sans bloquer les autres. Les identités child/family/device/familyCode doivent correspondre avant reprise.
- Reprise bornée : 64 événements RAM et 128 fichiers de secours, erreurs explicites en cas de saturation. Si SQLite ET le journal échouent, aucune durabilité ne peut être garantie : l’erreur reste visible. La file RAM seule ne résiste pas à la mort du processus.
- Les uploads prioritaires s’ajoutent à la chaîne WorkManager avec APPEND_OR_REPLACE au lieu d’annuler le worker courant. Ils peuvent donc attendre le backoff d’un prédécesseur ; aucun délai réseau garanti n’est revendiqué. Les ACK SQLite restent inchangés.
- Les logs ordinaires modifiés dans VoiceEngine/service ne contiennent plus les phrases, seulement des longueurs. Les diagnostics explicites existants restent un mode d’export sensible.
- Nouveaux champs JS optionnels : voicePipelineError, voiceDeliveryError, voicePendingDeliveryCount. L’interface produit doit les présenter ; aucun nouvel écran n’est livré ici.

Les règles de compréhension de la détresse et des citations ne sont pas modifiées. Les paramètres acoustiques et le défaut Qwen à deux threads restent ceux de la version de référence.

## Mesures CPU réellement exécutées sur Linux

Deux captures de 92,38 s et 88,62 s ; échauffement puis trois répétitions. Les entrées privées restent hors du dépôt. Les bancs reproductibles sont dans `modules/guardian-native-protection/tests/bench/`.

Hôte : INTEL(R) XEON(R) PLATINUM 8573C. Quota CPU : quatre cœurs équivalents (`400000 100000`). NCNN épinglé à `e956fbf9bd97fe945df35d736747dc816f8b5f42`, Release/O2, CPU série sans OpenMP. FireRed/FSMN emploient les vrais headers, modèles et frontends du moteur ; pas des mocks. Sherpa-ONNX 1.13.8 pour Qwen et GTCRN.

100 % signifie un cœur pleinement occupé. Ce ne sont ni des pourcentages Android, ni des pourcentages de batterie. Les étapes ont été isolées ; leurs coûts ne constituent pas une mesure de bout en bout de l’application.

| Poste | CPU médian équivalent d’un cœur | Entrée mesurée |
|---|---:|---|
| Prétraitement quatre canaux | 0,05–0,06 % | captures complètes |
| Prétraitement + LONG | 0,41–0,43 % | captures complètes |
| FireRed continu | 4,26–4,37 % | captures complètes |
| FireRed de confirmation | 0,35–0,36 % | fenêtres de parole |
| FSMN de confirmation | 0,48–0,50 % | fenêtres de parole |
| GTCRN streaming | 6.88 % | six fenêtres de parole |

Pour Java, le CPU ci-dessus est celui du thread de calcul, hors GC/JIT des autres threads. Les métriques natives et Python sont celles du processus. Les lectures de fichiers sont exclues des boucles DSP ; initialisation et échauffement exclus des valeurs stationnaires. Les confirmations sont volontairement toutes exécutées par le banc : le moteur réel peut les court-circuiter selon les admissions. Les probabilités ne sont pas comparées par ce banc de coût. Aucun appel réseau ou traitement de notification n’est inclus.

### Qwen : économie CPU contre délai

Six fenêtres fixes, modèles/configuration identiques ; première répétition d’échauffement exclue. Le temps inclut accept_waveform et decode, pas la lecture disque. La mémoire haute du processus complet de benchmark est d’environ 1.69 Gio ; ce n’est pas la mémoire mesurée de l’application Android.

| Configuration | CPU total pour six fenêtres | Latence médiane par fenêtre | CPU pendant le décodage |
|---|---:|---:|---:|
| 1 thread(s) demandé(s) | 13.30 s | 2.20 s | 100 % d’un cœur |
| 2 thread(s) demandé(s) | 25.58 s | 1.43 s | 295 % d’un cœur |

Un thread réduit le temps CPU d’environ 48 %, mais augmente la latence médiane d’environ 54 %. Les six textes sont identiques entre un et deux threads et stables sur trois répétitions dans ce banc. Cela ne prouve pas une équivalence sur tous les audios ou sur Android. Le nombre de threads demandé ne borne pas le CPU total de toutes les sessions natives : le processus à deux threads dépasse effectivement deux cœurs lors de certaines étapes. La cause détaillée par pool natif n’a pas été profilée ; aucune explication unique n’est affirmée.

### Conclusion d’optimisation

Le traitement classique n’est pas le principal levier de cette mesure. Optimiser LONG au prix d’un changement du signal aurait peu d’intérêt face au coût des réseaux. Le coût de FireRed/GTCRN devient permanent si ces vues sont actives en continu ; Qwen ajoute des pointes lors des décodages. Les modes d’étude peuvent cumuler plusieurs vues, donc ne doivent pas être confondus avec un chemin original minimal.

Le levier mesuré le plus net est Qwen à un thread : à tester sur téléphone avec le même corpus, en vérifiant délais, files, textes, température et énergie. Le défaut de production n’est pas changé sur la seule base d’un serveur plus rapide. L’étude ne permet pas de déclarer le moteur entièrement optimisé ni de promettre une autonomie.

## Mesure sur téléphone à terminer

`mesurer-cpu-guardian.ps1` collecte le temps CPU du processus via adb /proc/PID/stat ; pas d’audio ni de texte reconnu. Exemple PowerShell :

```powershell
.\mesurer-cpu-guardian.ps1 -DurationSeconds 180 -OutputCsv "$env:TEMP\guardian-original.csv"
```

La capture doit distinguer silence, bruit seul, parole au calme et sous aspirateur, puis ORIGINAL et ADAPTIVE_CANDIDATE avec les mêmes scénarios. Exécuter aussi une mesure de référence application inactive. Le collecteur détecte les changements de PID et de démarrage ; il vérifie CLK_TCK au lieu de supposer une fréquence. Il peut être bloqué par les permissions /proc ou l’absence de getconf selon l’appareil : ce blocage doit être diagnostiqué, pas remplacé par un chiffre inventé. Le script n’a pas été exécuté ici (pas de téléphone adb ni de PowerShell).

Les diagnostics activés ajoutent INPUT_CPU_METRIC pour le worker d’entrée : threadCpuMs, processingWallMs, actions, backlog et pertes. Ce compteur n’inclut ni le worker Qwen ni les pools des bibliothèques. Le collecteur /proc complète la vision du processus entier. Les diagnostics WAV/logs ajoutent eux-mêmes du travail ; comparer des modes de diagnostic identiques et faire aussi une session sans exports.

## Voix venant d’un téléviseur

Le code inspecté n’implémente pas de classifieur validé « téléviseur versus personne présente ». Une mention de speaker verification dans les capacités n’est pas une telle implémentation. Reconnaître une identité vocale ne prouve pas que cette voix n’est pas rejouée par un haut-parleur.

Une étude acoustique peut rechercher des indices de reproduction, de spatialisation et de proximité. Elle devra inclure plusieurs téléviseurs, distances, volumes et pièces, des voix proches/lointaines, et surtout une vraie demande de l’utilisateur pendant que la TV parle. On ne peut pas garantir la séparation dans tous les cas à partir d’un signal micro seul. Aucun filtre TV ni règle de citation n’a été ajouté par ce correctif.

## Vérifications et limites de livraison

Suite hôte, injections de panne, vrai VoiceEngine compilé/exécuté avec stubs de plateforme, journal JSON recréé et repris, compilation API adaptateur contre Sherpa officiel épinglé, vérification TypeScript. Les doubles Android servent à exercer les contrats JVM : ils ne remplacent pas AtomicFile/SQLite/WorkManager réels sur appareil.

Pas de compilation APK du projet Guardian complet, pas de test WorkManager ni de panne disque Android réelle, pas de profil batterie ou thermique, pas de validation d’alertes reçues. Le dépôt contient le module et les installateurs, pas un projet Expo Guardian complet prêt à compiler ici. La fermeture de l’application conserve le comportement existant d’arrêt du service ; le contrat de protection après fermeture reste à définir et à vérifier.

Les corrections ne valent pas qualification professionnelle du produit. Les validations de détresse et citations restent volontairement pour une étape ultérieure, conformément à votre demande.
