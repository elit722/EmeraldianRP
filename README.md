# Wathe RP — addon RolePlay pour Wathe (Fabric 1.21.1)

## Compiler le .jar
Le code source est complet mais **n'a pas encore été compilé** (voir « Statut »).

**Option A – sans rien installer (GitHub Actions)**
1. Crée un dépôt GitHub **privé** (le jar de Wathe est « All Rights Reserved » : ne le publie pas).
2. Envoie-y tout ce dossier (y compris `libs/`).
3. Onglet *Actions* → *Build* → télécharge l'artefact `watherp-jar`.

**Option B – en local** : JDK 21 + Gradle 8.12+ puis `gradle build` → `build/libs/watherp-1.0.0+mc1.21.1.jar`.

Installer le jar dans `mods/` du serveur **et** des clients (avec Wathe 1.4.x et Fabric API).

## Configuration — `config/watherp.json` (créé au 1er lancement)
| Clé | Rôle |
|---|---|
| `webhookUrl` | URL du webhook Discord (Salon → Intégrations → Webhooks). Vide = Discord désactivé |
| `discordPingRoleId` | ID d'un rôle Discord à mentionner à chaque ticket |
| `helpTypes` | Types acceptés par `/aide` |
| `helpCooldownSeconds` | Délai anti-spam par joueur |
| `proposalSeconds` | Durée de validité d'une proposition de présentation (60) |
| `proposalPopupSeconds` | Durée d'affichage de la popup (30) ; ensuite la demande reste dans le centre des notifications (menu `G`) avec les boutons Accepter / Pseudonyme / Refuser jusqu'à expiration |
| `helperCanTeleport` | Les helpers peuvent-ils se TP au joueur depuis un ticket |
| `workerUrl` / `workerApiKey` / `serverSlug` | Connexion au Worker ReLink : remplis par `/relink connect <code>` (voir « Worker ReLink ») |
| `adminRoles` | Rôles du panel qui ont automatiquement TOUTES les permissions du mod (défaut `["admin"]`) |
| `heartbeatSeconds` / `permissionTtlSeconds` | Heartbeat vers le Worker (120 s) / durée du cache des permissions (180 s) |
| `logWebhookUrl` / `logStaffActions` | Webhook Discord séparé pour le journal staff (sinon `webhookUrl`) / l'activer ou non |
| `chatCooldownMs` | Délai minimal entre deux messages de chat intégré |

Données (rangs helper, tickets, dossiers warn/note, noms RP de la partie en cours, file d'envoi vers le Worker) : `config/watherp-data.json`. Chats intégrés : `config/watherp-chats.json`.

## Commandes
| Commande | Qui | Effet |
|---|---|---|
| `/aide` | tous | Ouvre le **menu d'aide** : type, message, bouton **Anonymiser le ticket** (recommandé pour les questions de rôle), bouton **Demander un chat privé** |
| `/aide <type> [message]` | tous | Version rapide (ticket non anonyme) |
| `/relink connect <code> [url]` · `status` · `reload [joueur]` · `disconnect` | permission `rp.worker` | Appairage au panel ReLink, état, rechargement des permissions |
| `/r rankup helper <joueur>` / `/r derank helper <joueur>` | OP (niv. 2) | Donne / retire le rang helper |
| `/r innactive` (ou `/r inactive`) | staff | Se met **inactif** : plus d'alertes d'aide |
| `/r active` | staff | Se remet **disponible** : les alertes d'aide reviennent |
| `/r forcechange name <joueur> <nom>` | staff | Change le nom RP (**joueur hors ligne accepté** : appliqué à sa reconnexion) |
| `/r reset name <joueur>` | staff | Réaffiche le menu de nom RP au joueur (**hors ligne accepté** : le menu s'ouvre à sa reconnexion) |
| `/r ticket <claim\|close\|reopen\|tp\|chat\|deanon\|delete> <id>` | helper | Actions sur un ticket (utilisé par le panneau et les boutons du chat) |
| `/rpnom` | tous | Rouvre le menu de nom RP / affiche son nom |
| `/r warn <joueur> <raison>` | staff | Avertissement : enregistré, le joueur reçoit chat + titre à l'écran. **Hors ligne (fuyard)** : l'avertissement lui est affiché à sa prochaine connexion |
| `/r note <joueur> <texte>` | staff | Note privée (le joueur n'est pas prévenu) |
| `/r records <joueur>` | staff | Liste ses avertissements / notes dans le chat |
| `/r record delete <id>` | OP (niv. 2) | Supprime un dossier |
| `/r voirpseudo` | staff | Bascule : voir les vrais pseudos en partie (sinon rien n'est affiché pour les joueurs non présentés) |
| `/r names player <joueur>` / `/r names rp <nom>` | staff | Historique des noms RP (depuis le Worker) d'un joueur / qui a porté ce nom |
| `/r webhook set <url>` · `clear` · `status` · `test` | OP (niv. 2) | Webhook Discord des tickets, sans éditer le fichier. L'URL est validée (webhook Discord uniquement) et jamais réaffichée en entier |
| `/r worker url <url>` · `key <clé>` · `clear` · `status` | OP (niv. 2) | Connexion au Worker ReLink ; `status` teste l'URL, la clé et le scope |

`<joueur>` accepte un pseudo ou un nom RP, **en ligne ou hors ligne** (tant qu'il s'est connecté au moins une fois depuis l'installation du mod). Un nom RP avec espaces s'écrit `Jean_Pierre` ou entre guillemets `"Jean Pierre"` (obligatoire avec des accents : `"Hélène"`). Si deux joueurs hors ligne ont le même nom RP, la commande refuse et demande le pseudo exact.
**Attention** : les commandes `/r webhook set` et `/r worker key` contiennent un secret, qui apparaît dans `logs/latest.log` du serveur (journal des commandes). Si le fichier de logs est partagé, utilisez plutôt `config/watherp.json`.

## Permissions (gérées dans l'Admin Panel ReLink)
- **Rôle Admin du panel** = toutes les permissions du mod automatiquement. Sinon, chaque commande demande sa permission, déclarée au Worker au démarrage (à attribuer dans le panel) :
  `rp.helper` (menu helper) · `rp.helper.manage` (`/r rankup|derank helper`) · `rp.name.force` (`/r forcechange`, `/r reset`) · `rp.warn` (`warn`, `note`, `records`) · `rp.record.delete` · `rp.names.read` (`/r names`) · `rp.pseudo.see` (`/r voirpseudo`) · `rp.webhook` · `rp.worker` (`/relink`, `/r worker`).
- **Helper** (menu d'assistance, tickets, chat intégré) = rang local `/r rankup helper` **ou** permission `rp.helper` **ou** Admin. Les commandes `/r …` restent soumises à leurs permissions, même pour un helper.
- Tant que le serveur n'a **jamais** été appairé, les OP niveau 2 passent (sinon impossible de taper `/relink connect` la première fois). Une fois relié, les OP n'ont plus de passe-droit. La console passe toujours.
- Les permissions sont lues à la connexion puis rafraîchies (cache 3 min) ; si le Worker est injoignable, le dernier cache est gardé, jamais de permission « par défaut ».

## Touches (modifiables dans Options → Contrôles → Wathe RP)
`H` **Aide** (menu d'aide, aussi bouton « Aide » en haut à droite du menu pause) · `G` **menu de commandes** (invite de commande seule, pour tous) · `Y` **Messagerie** (ouvre aussi directement le chat d'une popup d'invitation) · `N` panneau d'assistance (helper) — **le curseur est directement dans le champ de commande en bas du panneau** : tapez la commande (le `/` est facultatif), Entrée pour l'envoyer, ↑/↓ pour l'historique. Le retour du serveur s'affiche sous le champ. Les boutons « Avertir… » / « Note… » pré-remplissent `/r warn <pseudo> ` (marche aussi pour l'auteur d'un ticket déjà déconnecté). « Se téléporter » sur un ticket dont l'auteur est parti vous amène là où il a fait sa demande · `J` accepter une présentation · `K` se présenter sous pseudonyme.

## Tickets anonymes, suppression
- Ticket **anonymisé** : ni pseudo, ni nom RP, ni UUID, ni position ne sont envoyés aux helpers (le serveur n'envoie aucune de ces données au client) ni à Discord. Téléportation et avertissement impossibles tant qu'il est anonyme.
- Le helper peut **Désanonymiser** (si besoin) : le joueur est prévenu, et le journal Discord note l'action **sans** nommer le joueur.
- Le panneau permet aussi **Supprimer** (confirmation en deux clics, supprime aussi le message Discord), **Chat privé** (ouvre un chat avec l'auteur et prend le ticket en charge ; l'auteur reste « Joueur anonyme » pour les helpers) et affiche les demandes de chat privé.

## Chat intégré (Messagerie)
- **Helper** : menu « Chat intégré » (onglet du panneau ou touche `Y`) : chat direct avec n'importe quel joueur en ligne (popup chez lui avec la touche pour ouvrir), **chat helper** commun, création / fermeture de **groupes**, **Ajouter…** des membres à un chat (ils reçoivent la même popup).
- **Joueur** : retrouve ses chats avec la touche `Y` tant que le staff ne les a pas fermés ; un chat fermé disparaît de sa liste et reste lisible (lecture seule) pour les helpers membres.
- Tout est vérifié côté serveur (membres, fermeture, anonymat). Historique persistant (300 lignes / chat).

## Notifications
- Quand le menu de chat n'est pas ouvert, chaque nouveau message affiche une popup avec la touche Messagerie **lue dans tes réglages** (la touche ouvre directement ce chat). Au plus 3 popups à la fois (les plus anciennes partent), disparition au bout de 6 s (invitations : 20 s ; alertes staff : 8 s).
- Menu `G` : boutons pour **couper les notifs de chat** et (helpers) **les notifs staff**, plus le **Centre des notifications** (historique, clic = ouvre le chat ou le panneau, bouton Messagerie). Les notifs coupées restent consultables dans le centre. Préférences dans `config/watherp-client.properties`.

## Menu de commandes (chat de Wathe)
Le mod **ne touche pas** au chat : il reste masqué en partie comme Wathe l'a prévu, et rien dans le mod ne le réactive (vérifié). La touche `G` ouvre juste une invite de commande (`/aide`, `/rpnom`…), avec historique et retour du serveur.

## Noms RP
- **Aucun chiffre** dans le prénom RP (le champ les refuse, le serveur revérifie ; les pseudonymes de présentation les acceptent encore). **Échap est désactivé** dans le menu de nom RP pendant une partie (pas hors partie ni pour spectateurs/créatifs) ; le menu se ferme à la fin de la partie.
- Au lancement d'une partie, chaque joueur doit saisir un nom, **ou** cliquer « Conserver [nom précédent] comme mon identité » (le champ n'est pas prérempli ; Entrée ne valide qu'un nom saisi). Définitif pour la partie, sauf staff. Le verrou survit à un redémarrage du serveur (noms de la partie sauvegardés).
- Maj + clic gauche sur un joueur → *Se présenter* / *Se présenter sous pseudonyme*. L'autre reçoit une proposition (valable 60 s ; popup 30 s, puis centre des notifications ; l'expiration est suspendue pendant la saisie du pseudonyme) en bandeau à l'écran **et** dans le chat (le chat de Wathe est masqué pour les joueurs en vie, d'où le bandeau).
- En partie, un joueur à qui l'on ne s'est pas présenté n'a **aucun nom affiché** (ni dans le HUD, ni au-dessus de la tête). Seuls les staff ayant activé `/r voirpseudo` voient les vrais pseudos (aussi pour les spectateurs/créatifs : plus de révélation automatique).
- La proposition de présentation (bandeau + message du chat) affiche la touche **actuellement assignée** dans Options → Contrôles, et prévient si aucune touche n'est assignée.
- Les commandes vanilla acceptent noms RP / pseudonymes à la place du pseudo (accents et casse ignorés, `_` = espace). Un joueur ne peut viser que des personnes qu'il connaît ; le staff peut viser tout nom RP.
- Les noms RP font **32 caractères max** (`maxNameLength` dans la config, plafonné à 32). Minecraft limite les noms de sélecteurs à 16 caractères : le mixin `EntitySelectorReaderMixin` relève cette limite (serveur **et** client, donc le mod doit être sur les deux) pour que `/tp Nom_Roleplay_Tres_Long`, `/msg`, etc. fonctionnent. Les anciennes configs à `16` sont migrées automatiquement vers `32`.

## Worker ReLink : appairage, permissions, historique
`/relink connect ABCD-EFGH` (code du panel, console ou OP) → le mod enregistre URL + slug + clé, déclare ses permissions, envoie un heartbeat toutes les 2 min et récupère les permissions de chaque joueur à sa connexion (`/relink status`, `/relink reload`). Routes : `/mc/servers/pair|register`, `/mc/servers/<slug>/permissions`, `/mc/rp/names` (plus de préfixe `/api/v1`).

### Historique des noms RP (Worker ReLink / D1)
Chaque nom RP défini (saisi, conservé ou imposé par le staff) et chaque pseudonyme présenté à quelqu'un est envoyé au Worker et stocké dans la table `rp_name_history`. Les événements sont mis en file (sauvegardée sur disque) et renvoyés jusqu'à ce que le Worker réponde : une panne du Worker ne perd rien et ne ralentit pas le jeu.
Installation : voir `worker/INTEGRATION.md` (redéployer `rp-names.js`, qui accepte la clé d'appairage), puis `/relink connect <code>`.

## Statut / limites
- **Version 1.1 (tickets anonymes, chats, permissions Worker, menu Aide/commandes, règles de noms)** : syntaxe vérifiée (javac, hors résolution des classes Minecraft), Worker testé (`node worker/test-rp-names.mjs`), mais **non compilé ni testé en jeu**. Points à valider au premier build : `ScreenEvents.AFTER_INIT` + `Screens.getButtons` (bouton Aide du menu pause), `TextFieldWidget.setTextPredicate`, `Screen.shouldCloseOnEsc`.
- Touches par défaut : H, G, Y (modifiables). Si elles gênent un autre mod, changez-les dans Options → Contrôles.
- Le chat intégré liste les joueurs **en ligne** pour « Nouveau chat » / « Ajouter » ; le chat depuis un ticket marche aussi pour un auteur hors ligne (popup à sa connexion).
- Dernière modification (noms 32, `/r active`, saisie de commande dans le panneau, actions hors ligne) : syntaxe vérifiée, **non compilée** ; à valider au premier build (cible du mixin `EntitySelectorReader#readRegular` et `ClientPlayNetworkHandler#sendChatCommand` notamment). Les commandes qui passent par un profil de compte (`/op`, `/ban`, `/whitelist`) ne résolvent pas les noms RP.
- **Non compilé ni testé en jeu** : l'environnement de développement n'avait pas accès à Maven/Mojang. La syntaxe est validée ; les appels à l'API Yarn 1.21.1 et aux classes Wathe (relevées par `javap` dans le jar) peuvent demander de petits ajustements au premier build.
- Les mixins sont en `defaultRequire: 0` : si une cible change, le mod se charge quand même mais la fonction concernée est inactive (voir `latest.log`).
- Textes en français (en dur).
- Les modifications de cette version (panneau trié/dossiers, commandes, Worker) n'ont **pas** été compilées non plus : syntaxe vérifiée, Worker testé sur SQLite, mais les appels Yarn/Wathe restent à valider au premier build.
- Les présentations (qui connaît qui) ne sont pas sauvegardées : après un redémarrage en pleine partie, les noms RP restent verrouillés mais les joueurs doivent se re-présenter.
