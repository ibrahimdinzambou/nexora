# IPTV SaaS API - Spring Boot

Implementation Spring Boot de l'API decrite dans `CAHIER_DES_CHARGES_API_IPTV.md`.

## Stack

- Java 17
- Spring Boot 3.3
- Spring Web, Security, Data JPA, Validation, Mail, Actuator
- Springdoc OpenAPI 2.6
- H2 local par defaut
- Authentification Bearer token stockee en base

## Lancer

```powershell
.\mvnw.cmd spring-boot:run
```

API locale:

- `http://localhost:8080/` - interface client Nexora
- `http://localhost:8080/api/docs`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/v3/api-docs`
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/h2-console`

H2:

- JDBC URL: `jdbc:h2:file:./data/iptv-saas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
- User: `sa`
- Password: vide

## Comptes seed

- Admin: `admin@example.com` / `password`
- User: `test@example.com` / `password`

## Exemples

Login:

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "admin@example.com",
  "password": "password"
}
```

Puis utiliser:

```http
Authorization: Bearer {token}
Accept: application/json
Content-Type: application/json
```

Swagger UI:

- Ouvrir `http://localhost:8080/swagger-ui.html`
- Cliquer sur `Authorize`
- Renseigner `Bearer {token}` ou directement le token selon l'affichage Swagger UI

Pages web:

- `http://localhost:8080/` : présentation publique de Nexora et abonnements
- `http://localhost:8080/signup.html` : inscription client avec choix de formule
- `http://localhost:8080/watch.html` : catalogue et lecteur client
- `http://localhost:8080/admin.html` : console d'administration

## Tests

```powershell
.\mvnw.cmd test
```

## SMTP et Telegram

La configuration se fait uniquement par variables d'environnement. Le fichier
`.env.example` liste toutes les variables attendues, mais Spring Boot ne charge
pas automatiquement un fichier `.env`.

Exemple PowerShell pour Gmail:

```powershell
$env:MAIL_HOST="smtp.gmail.com"
$env:MAIL_PORT="587"
$env:MAIL_USERNAME="notifications@example.com"
$env:MAIL_PASSWORD="mot-de-passe-applicatif"
$env:MAIL_FROM_ADDRESS="notifications@example.com"
$env:MAIL_FROM_NAME="Nexora"
$env:MAIL_SMTP_AUTH="true"
$env:MAIL_STARTTLS="true"
$env:MAIL_STARTTLS_REQUIRED="true"
```

Telegram logs / alertes:

```powershell
$env:TELEGRAM_ALERTS_ENABLED="true"
$env:TELEGRAM_ALERTS_BOT_TOKEN="token-du-bot-logs"
$env:TELEGRAM_ALERTS_CHAT_ID="identifiant-du-chat-logs"
```

Telegram administration:

```powershell
$env:TELEGRAM_ADMIN_ENABLED="true"
$env:TELEGRAM_ADMIN_BOT_TOKEN="token-du-bot-admin"
$env:TELEGRAM_ADMIN_CHAT_ID="identifiant-du-chat-admin"
$env:TELEGRAM_ADMIN_ALLOWED_CHAT_IDS="identifiant-du-chat-admin,autre-chat"
$env:TELEGRAM_ADMIN_READONLY_CHAT_IDS="chat-lecture-seule"
$env:TELEGRAM_ADMIN_CHAT_ROLES="identifiant-du-chat-admin:SUPER_ADMIN,autre-chat:OPS"
```

Redemarrer ensuite l'application. Les statuts et les envois de test sont
disponibles dans `http://localhost:8080/admin.html`, vue **Connecteurs**.
Pour Gmail, utiliser un mot de passe applicatif et non le mot de passe principal.

Panel Telegram:

- `/admin` : aide et menu avec boutons inline
- `/whoami`, `/admin_status` : chat_id, role et etat du bot admin
- `/status`, `/health`, `/sessions`, `/capacity` : supervision
- `/iptv` ou `/accounts` : liste des comptes IPTV
- `/account_33` : detail d'un compte
- `/add_m3u Nom | URL playlist`
- `/add_xtream Nom | URL base | username | password`
- `/test_account 33`, `/sync_limits 33`, `/clear_cache 33`
- `/active_sessions`, `/close_session 12`, `/stale_sessions`, `/cleanup_sessions`
- `/client email@example.com`, `/suspend_user 5`, `/reactivate_user 5`
- `/pending_payments`, `/verify_payment 42`, `/reject_payment 42 | raison`
- `/tickets`, `/urgent_tickets`, `/reply_ticket 7 | message`
- `/smtp_status`, `/smtp_test email@example.com`, `/telegram_status`, `/torbox_status`
- `/addons` : liste des add-ons installes
- `/users recherche` : recherche d'utilisateurs actifs
- `/assign_addon addonId userId,userId` : partage d'un add-on prive avec des utilisateurs

Le bot d'administration est separe du bot de logs. Le bot logs utilise
`TELEGRAM_ALERTS_BOT_TOKEN` et ne fait qu'envoyer les alertes. Le bot admin
utilise `TELEGRAM_ADMIN_BOT_TOKEN` et traite les commandes.

Le panel n'accepte que les chats declares dans `TELEGRAM_ADMIN_ALLOWED_CHAT_IDS`. Les
chats declares dans `TELEGRAM_ADMIN_READONLY_CHAT_IDS` peuvent consulter mais pas
executer d'action d'ecriture. `TELEGRAM_ADMIN_CHAT_ROLES` accepte le format
`chatId:ROLE` avec `SUPER_ADMIN`, `ADMIN`, `OPS`, `BILLING` ou `SUPPORT`;
sans role explicite, un chat autorise garde le role `SUPER_ADMIN`. Les actions
sensibles demandent confirmation par bouton inline avec expiration courte. Toutes les commandes sont ajoutees au journal d'audit. Les alertes
automatiques IPTV previennent quand un compte tombe, expire bientot ou depasse
le seuil de saturation configure.

Compatibilite: les anciennes variables `TELEGRAM_BOT_TOKEN` et
`TELEGRAM_CHAT_ID` restent acceptees comme alias du bot logs, pas du bot admin.

## Interface client

Le dossier `netflix-main` sert de référence visuelle pour le site client. La version
intégrée à Spring Boot se trouve dans:

- `src/main/resources/static/index.html`
- `src/main/resources/static/assets/app.css`
- `src/main/resources/static/assets/app.js`
- `src/main/resources/static/assets/images/`

L’interface est servie directement par Spring Boot sur `http://localhost:8080/`.
Elle propose le catalogue Direct/Films/Séries, la recherche, l’inscription, la
connexion, le profil client et l’ouverture des sessions de streaming via l’API.
Les playlists M3U configurées dans les comptes IPTV sont chargées côté serveur:
leurs URLs ne sont jamais exposées au navigateur. Les flux MPEG-TS sont lus via
`mpegts.js` 1.8.0, distribué sous licence Apache-2.0 dans `assets/vendor/`.

Sans authentification, un catalogue de découverte est affiché. Pour tester le
catalogue connecté:

- `test@example.com` / `password`
- `admin@example.com` / `password`

## Couverture fonctionnelle

Routes implementees:

- Auth: inscription, login, profil, logout, OTP email, 2FA, reset password
- SaaS: organisations, membres, plans, abonnements
- Billing: paiements manuels, validation/rejet admin, factures PDF simples
- IPTV: comptes fournisseurs, catalogue demo, health, sync limites
- Streaming: open, url, proxy redirect, heartbeat, close, cleanup service
- Support: tickets, reponses, messages internes admin
- Admin: dashboard, clients, subscriptions, invoices, users, IPTV, sessions
- Ops: health, metrics, audit logs, uptime checks
- Docs: `/api/docs`, Swagger UI `/swagger-ui.html`, OpenAPI JSON `/v3/api-docs`, redirection `/api/documentation`

## Add-ons communautaires

Le panneau `Administration > Connecteurs` permet d'installer un manifeste communautaire.
L'add-on reste en statut `PENDING` jusqu'a son approbation. Le serveur n'execute
aucun code tiers : il interroge uniquement des routes JSON compatibles avec le
format Stremio de base.

Un add-on peut aussi etre marque `Usage prive`. Dans ce mode:

- il est visible par son proprietaire et, si le super-admin le decide, par une
  liste restreinte de comptes actifs;
- seul le super-admin peut modifier cette liste de partage;
- tous les autres utilisateurs, y compris les administrateurs non selectionnes,
  ne peuvent ni parcourir ses catalogues ni resoudre directement ses identifiants;
- la preuve de licence n'est pas exigee, car le catalogue n'est pas publie aux
  utilisateurs de la plateforme;
- le proprietaire conserve la responsabilite de respecter les conditions des
  sources et du fournisseur Debrid utilise.

Exemple de manifeste:

```json
{
  "id": "org.example.free-cinema",
  "name": "Free Cinema",
  "version": "1.0.0",
  "description": "Catalogue de films sous licence libre",
  "catalogs": [
    { "type": "movie", "id": "free", "name": "Films libres" }
  ]
}
```

Pour un manifeste situe a `https://addon.example/manifest.json`, les routes
attendues sont:

```text
GET https://addon.example/catalog/movie/free.json
GET https://addon.example/meta/movie/{id}.json
GET https://addon.example/stream/movie/{id}.json
```

Le catalogue retourne `metas` ou `items`, la fiche retourne `meta`, et la route
de lecture retourne `streams`. Les URL media directes sont acceptees lorsqu'elles
appartiennent a la liste blanche de l'add-on. Les flux Stremio avec `infoHash`
peuvent etre resolus par TorBox lorsque `TORBOX_API_TOKEN` est configure.

Pour ASA, utilisez de preference l'URL privee generee par
`https://asa.00696900.xyz/configure#tokens` apres avoir renseigne TorBox.
Le manifeste public expose surtout des torrents bruts, tandis que le manifeste
configure peut fournir directement les liens video TorBox. Cette URL privee
contient une configuration sensible: ne la partagez pas et conservez l'add-on
en mode prive. Autorisez au minimum `.tb-cdn.io` et `.torbox.app` dans les
domaines de diffusion.

Les options Stremio declarees dans `catalogs[].extra` sont exposees comme
filtres de catalogue. ASA fournit notamment les filtres annee, studio,
interprete, tag et qualite. La pagination `skip` est chargee progressivement
avec le bouton `Charger la suite ASA`, et le catalogue `search` est utilise
automatiquement par la recherche globale.

Les types de catalogue Stremio personnalises sont acceptes lorsqu'un unique type
standard (`movie`, `series` ou `live`) est declare dans `types`. Par exemple, un
catalogue distant `porn` avec `"types": ["movie"]` est expose comme catalogue
`movie` dans Nexora, tout en conservant `porn` dans l'URL distante.

Configuration TorBox:

```properties
TORBOX_API_TOKEN=...
TORBOX_API_BASE_URL=https://api.torbox.app
TORBOX_ALLOWED_DOWNLOAD_HOSTS=.torbox.app,.tb-cdn.io
TORBOX_MAX_WAIT_SECONDS=90
```

Le jeton reste cote serveur. Nexora envoie le magnet a TorBox, selectionne le
plus grand fichier video, attend sa disponibilite puis relaie l'URL HTTPS
temporaire. Le navigateur ne recoit ni le magnet ni le jeton TorBox.

Regles de securite:

- manifeste HTTPS public uniquement;
- reponses JSON limitees en taille et sans redirection automatique;
- licence et URL de preuve obligatoires avant approbation;
- exception de licence limitee aux add-ons prives et a leur proprietaire;
- domaines de flux explicitement autorises;
- URL TorBox limitees aux domaines de telechargement autorises;
- toute modification ou actualisation replace l'add-on en attente;
- un catalogue marque `18+` exige une attribution explicite de sa categorie a
  l'utilisateur.
