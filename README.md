# Archi MCP Server

Plugin pour [Archi](https://www.archimatetool.com/) qui expose les modèles ArchiMate **ouverts dans Archi** à un assistant IA (Mistral Vibe, Claude Code, tout client MCP) via le [Model Context Protocol](https://modelcontextprotocol.io/).

Contrairement aux serveurs MCP qui lisent des fichiers `.archimate`, le plugin agit sur le modèle vivant :

- **Synchronisation :** les modifications apparaissent tout de suite dans l'arbre du modèle et dans les vues.
- **Annulation :** chaque appel d'outil devient une commande Archi, annulable avec **Edition > Annuler** (Ctrl/Cmd+Z).
- **Règles ArchiMate :** les relations sont validées par Archi, et l'IA reçoit la liste des types autorisés.
- **Contexte :** l'IA peut savoir ce que l'utilisateur a sélectionné, et ouvrir une vue à l'écran.

> Testé avec Archi 5.10.0 (JRE 21).

## Outils exposés

| Outil | Rôle |
|---|---|
| `list_models` | Modèles ouverts : id, fichier, modifications non sauvegardées, taille |
| `list_element_types` | Types d'éléments (par couche), types de relations, ids de points de vue |
| `search_elements` | Recherche par texte, type ou propriété |
| `get_element` | Détail d'un élément ou d'une relation : documentation, propriétés, relations, vues |
| `list_views` / `get_view` | Vues et leur contenu : nœuds imbriqués, positions, connexions |
| `get_selection` | Sélection courante dans Archi et vue active |
| `create_model` | Nouveau modèle vide |
| `create_element` | Nouvel élément (type tolérant : `BusinessActor`, `business-actor`, `Business Actor`) |
| `create_relationship` | Nouvelle relation validée (`Serving` équivaut à `ServingRelationship`) |
| `update_element` | Nom, documentation, propriétés (fusionnées ; `null` supprime une clé) |
| `create_view` | Nouvelle vue, avec un point de vue facultatif |
| `add_to_view` | Ajoute un élément sur une vue et trace ses relations avec ce qui y est déjà |
| `add_relationship_to_view` | Trace une relation existante sur une vue |
| `open_view` | Ouvre la vue dans l'éditeur d'Archi |
| `save_model` | Sauvegarde un modèle qui a déjà un fichier |
| `validate_model` | Lance le validateur d'Archi (relations invalides, éléments inutilisés, vues vides, doublons…) et renvoie les problèmes avec l'id de l'objet concerné |

## Construire le plugin

Le plus simple est le `Makefile` :

```bash
make build
```

Cette commande :
1. télécharge Archi dans `.archi-sdk/` au premier lancement (build Linux officiel, SHA-256 vérifié) ;
2. compile le plugin et lance les tests unitaires ;
3. affiche le chemin du `.archiplugin`.

Le build d'Archi téléchargé sert uniquement de SDK : le plugin produit marche sur tous les OS.

**Choix du mode :** le Makefile utilise le JDK local s'il est en version 21 ou plus (sur macOS : `brew install openjdk@21`). Sinon, il construit dans Docker (`eclipse-temurin:21-jdk`), sans rien installer. `make info` indique le mode retenu ; `USE_DOCKER=1` ou `USE_DOCKER=0` le force.

| Commande | Rôle |
|---|---|
| `make build` | Plugin + tests unitaires |
| `make package` | Plugin sans les tests |
| `make install` | Copie le plugin dans `~/.archi/dropins` (en remplaçant l'ancienne version), puis il faut redémarrer Archi |
| `make uninstall` | Retire le plugin des dropins |
| `make test` | Tests unitaires |
| `make e2e` / `make e2e-remote` / `make clients` | Tests dans un vrai Archi (voir [Tester](#tester)) |
| `make clean` / `make distclean` | Supprime `build/` / supprime aussi le SDK téléchargé |

**Variables :**
- `VERSION=1.2.0` : fixe la version (par défaut, celle de `gradle.properties`).
- `ARCHI_HOME=/Applications/Archi.app/Contents/Eclipse` : avec un JDK local, compile contre un Archi installé au lieu de télécharger le SDK.
- `DROPINS=…` : change le dossier d'installation.

Sans `make`, les commandes équivalentes sont `./scripts/fetch-archi.sh`, puis `./scripts/build-with-docker.sh` ou `./gradlew build`.

Le build produit deux fichiers :

- `build/dist/archi-mcp-<version>.archiplugin` : le paquet à installer dans Archi ;
- `build/libs/fr.redteams.archi.mcp_<version>.<horodatage>.jar` : le bundle OSGi.

## Installer dans Archi

1. **Aide > Manage Plug-ins… > Install New…**, puis choisir le fichier `.archiplugin`.
2. Redémarrer Archi.

Pour développer, `make install` copie directement le plugin dans `~/.archi/dropins`, en supprimant l'ancienne version. Redémarrez ensuite Archi.

## Configurer

Ouvrez **Edition > Préférences > MCP Server** (**Archi > Réglages… > MCP Server** sur macOS).

- **Activation :** le serveur démarre en même temps qu'Archi.
- **Adresse :** le serveur écoute sur `127.0.0.1`, port **18765** par défaut. Le champ **Listen on** permet de choisir une autre adresse (voir [Accès depuis une autre machine](#accès-depuis-une-autre-machine)).
- **Jeton :** un jeton *bearer* est généré au premier démarrage. Le bouton **Generate** en crée un nouveau.
- **Connexion d'un client :** la page affiche l'état du serveur. Pour le client choisi (Claude Code, OpenCode ou Mistral Vibe), elle donne aussi la commande ou la configuration prête à copier, avec l'URL et le port réels.

### Accès depuis une autre machine

Avec **Listen on** = `0.0.0.0`, le serveur écoute sur toutes les interfaces réseau. Vous pouvez aussi indiquer l'IP d'une interface précise, par exemple celle d'un VPN.

- **Jeton :** il devient obligatoire. La case « Require a bearer token » est cochée et grisée, et le serveur refuse de démarrer sans jeton.
- **Avertissement :** la page de préférences en affiche un, et l'état du serveur indique `REMOTE ACCESS ENABLED`.
- **URL :** les configurations affichées utilisent l'adresse IP locale (LAN) de la machine. Appliquez-les sur la machine cliente.
- **Adresses acceptées :** seules les adresses IP (IPv4, IPv6) et `localhost` sont acceptées. Un nom d'hôte est refusé, ce qui évite toute résolution DNS.

> ⚠️ Le trafic passe en **HTTP non chiffré**, jeton compris. Toute personne qui obtient le jeton peut lire et modifier les modèles ouverts. Réservez cette option à un réseau de confiance, avec une règle de pare-feu (en n'autorisant que le port 18765 aux bonnes machines). 


## Connecter un client

Les configurations ci-dessous ont été vérifiées contre Archi avec Claude Code 2.1, OpenCode 1.18 et Mistral Vibe 2.26. Le serveur s'y appelle `archi` dans les trois clients.

**1. Définir le jeton dans une variable d'environnement.** Aucun client ne stocke le jeton dans son fichier de configuration : tous lisent la variable `ARCHI_MCP_TOKEN` au moment de se connecter. Ajoutez-la à `~/.zshrc` ou `~/.bashrc` (sous Windows : `setx ARCHI_MCP_TOKEN <jeton>`), puis ouvrez un nouveau terminal.

```bash
export ARCHI_MCP_TOKEN="<jeton affiché dans Préférences > MCP Server>"
```

**2. Déclarer le serveur dans le client.** La page de préférences génère ces configurations avec l'URL réelle. Si Archi écoute sur le réseau, remplacez `127.0.0.1` par l'IP de la machine Archi.


### Mistral Vibe

```bash
vibe mcp add archi --url http://127.0.0.1:18765/mcp --api-key-env ARCHI_MCP_TOKEN
```

- **Configuration écrite :** la commande l'écrit dans `~/.vibe/config.toml`, avec `transport = "streamable-http"` et une authentification statique qui lit `ARCHI_MCP_TOKEN`.
- **Accès distant :** si Archi est sur une autre machine (URL `http://` hors localhost), Vibe exige `--allow-insecure-http`.
- **Vérification :** dans Vibe, `/mcp archi` liste les outils.
- **Nom des outils :** Vibe les préfixe par le nom du serveur (`archi_create_element`…). Vous pouvez donc demander une confirmation avant les modifications :

```toml
[tools.archi_create_element]
permission = "ask"
```

### Claude Code

```bash
claude mcp add --scope user --transport http archi http://127.0.0.1:18765/mcp --header 'Authorization: Bearer ${ARCHI_MCP_TOKEN}'
```

- **Guillemets simples :** ils sont indispensables. Claude Code garde ainsi `${ARCHI_MCP_TOKEN}` tel quel dans sa configuration et le remplace à chaque connexion.
- **`--scope user` :** il rend le serveur disponible dans tous vos projets.
- **Vérification :** `claude mcp list` doit afficher `archi … ✔ Connected`.

### OpenCode

Dans `~/.config/opencode/opencode.json` (ou dans `opencode.json` à la racine d'un projet), en fusionnant avec une éventuelle section `mcp` existante :

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "archi": {
      "type": "remote",
      "url": "http://127.0.0.1:18765/mcp",
      "enabled": true,
      "oauth": false,
      "headers": {
        "Authorization": "Bearer {env:ARCHI_MCP_TOKEN}"
      }
    }
  }
}
```

- **`"oauth": false` :** sans lui, OpenCode tente une authentification OAuth dès qu'il reçoit une réponse 401.
- **Vérification :** `opencode mcp list` doit afficher `✓ archi connected`.

### Autres clients

Tout client compatible avec le transport *Streamable HTTP* peut se connecter.

Utilisez `127.0.0.1` plutôt que `localhost` : certains clients essaient d'abord IPv6 (`::1`), et le serveur n'écoute pas en IPv6.

### Exemples de demandes

- « Dans Archi, liste les applications qui servent le processus *Traiter une commande*. »
- « Crée une vue de coopération applicative avec le CRM, l'ERP et leurs flux. »
- « Documente les éléments sélectionnés. »

## Tester

**Tests unitaires** (protocole et transport HTTP, sans Archi) :

```bash
./gradlew test
```

**Test de bout en bout** : il lance le vrai Archi (build Linux) sans écran, avec Xvfb dans Docker, installe le plugin, puis appelle chaque outil en HTTP.

```bash
./e2e/run.sh            # configuration par défaut (127.0.0.1)
./e2e/run.sh --remote   # Archi écoute sur 0.0.0.0, client dans un autre conteneur
```

Il couvre l'authentification, le cycle de vie MCP, la création, la validation et la recherche, les vues et connexions automatiques, et l'état « non sauvegardé ». Il vérifie aussi l'adresse d'écoute : avec `127.0.0.1`, le serveur doit être injoignable depuis un autre conteneur ; avec `--remote`, toute la suite passe par le réseau Docker. Sur Mac Apple Silicon, Archi tourne en émulation x86_64 : le démarrage prend environ 30 s.

**Test des clients** : il vérifie que Claude Code, OpenCode et Mistral Vibe (dernières versions, chacun dans un conteneur) se connectent au plugin dans Archi, avec exactement les configurations ci-dessus. Il n'est pas lancé en CI, car il dépend de paquets tiers qui évoluent souvent.

```bash
./e2e/clients.sh
```

## Publier une release

Le workflow GitHub Actions [`build.yml`](.github/workflows/build.yml) s'exécute sur chaque push sur `main` et sur chaque pull request. Il :

1. télécharge Archi ;
2. lance le build et les tests unitaires ;
3. lance les tests de bout en bout dans les deux modes (`127.0.0.1` et `0.0.0.0`).

Les fichiers produits sont disponibles comme artefact `archi-mcp` du run.

**Sur un tag quelconque**, le workflow fait la même chose, puis crée une **release GitHub**. Elle contient le `.archiplugin`, le jar et `SHA256SUMS.txt`, avec des instructions d'installation et des notes générées depuis les commits.

```bash
git tag v0.2.0
git push origin v0.2.0
```

**Format du tag :** c'est lui qui donne la version, au format `X.Y.Z`, `vX.Y.Z` ou `X.Y.Z-suffixe`.
- Exemple : `v1.2.0` produit `archi-mcp-1.2.0.archiplugin`, avec le bundle `1.2.0.<horodatage>`.
- Un suffixe (`1.2.0-rc1`) marque la release comme pré-release.
- Un tag qui n'est pas une version fait échouer le workflow dès le début.

La version d'Archi utilisée par la CI est définie par `ARCHI_VERSION` dans le workflow.

## Architecture

```
src/main/java/fr/redteams/archi/mcp/
├── Activator.java              cycle de vie du bundle, journal (Error Log d'Archi)
├── Startup.java                démarre le serveur avec le workbench (org.eclipse.ui.startup)
├── McpServerController.java    démarrage/arrêt selon les préférences, jeton, instructions MCP
├── McpPreferences.java, PreferenceInitializer.java
├── ui/McpPreferencePage.java   page de préférences
├── server/                     MCP générique, sans dépendance Archi (testé unitairement)
│   ├── McpHttpServer.java      transport Streamable HTTP (com.sun.net.httpserver du JRE)
│   ├── McpProtocolHandler.java JSON-RPC : initialize, ping, tools/list, tools/call
│   └── Tool, ToolRegistry, ToolResult, Arguments, Schema, …
└── archi/                      outils Archi
    ├── ReadTools.java / EditTools.java / ViewTools.java
    ├── ArchiAccess.java        exécution dans le thread UI, recherche par id, pile de commandes
    ├── ArchiJson.java          sérialisation des objets du modèle
    ├── ConceptTypes.java       résolution des types ArchiMate
    └── ModelCommand.java       commande GEF annulable
```

**Choix techniques :**

- **Pas de SDK MCP Java.** Il aurait fallu embarquer Jackson, Reactor et un conteneur de servlets dans un bundle OSGi. Le sous-ensemble du protocole utile ici (outils, réponses JSON synchrones) tient en deux classes, au-dessus du serveur HTTP du JRE qu'Archi fournit déjà. La seule dépendance embarquée est Gson (`lib/`).
- **Thread UI.** EMF n'est pas thread-safe : chaque outil s'exécute via `Display.syncExec`, comme les actions d'Archi.
- **Annulation.** Les écritures passent par la `CommandStack` du modèle (`model.getAdapter(CommandStack.class)`). Une modification faite par l'IA s'annule donc comme une modification manuelle.
- **Transport.** Les réponses sont en `application/json`, sans flux SSE. Un GET renvoie 405, et une session inconnue renvoie 404 (cas d'un Archi redémarré), ce qui pousse le client à se réinitialiser.


## Sécurité

- **Écoute :** sur la boucle locale (`127.0.0.1`) par défaut. L'écoute sur le réseau est une option explicite, qui impose un jeton.
- **Authentification :** un jeton *bearer* est exigé par défaut (comparaison à temps constant). Il est stocké en clair dans les préférences Eclipse d'Archi (`~/.archi/.metadata/.plugins/org.eclipse.core.runtime/.settings/fr.redteams.archi.mcp.prefs`).
- **En-tête `Origin` :** il est vérifié pour bloquer le DNS rebinding depuis un navigateur.
- **Écriture disque :** aucun outil n'écrit sur disque en dehors de `save_model`, et aucun ne supprime d'objet.

## Licence

[MIT](LICENSE). Archi est un logiciel distinct, sous sa propre licence ; ce dépôt ne contient ni ne redistribue aucun fichier d'Archi.

<sub>Contact : contact@redteams.fr</sub>
