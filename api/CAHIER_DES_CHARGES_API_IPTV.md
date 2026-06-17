# Cahier des Charges Complet - API IPTV SaaS

Version: 2.0  
Date: 12 juin 2026  
Projet: API-NETFLIX / Backend Laravel IPTV  
Stack: Laravel 11, PHP 8.2, Sanctum, Blade, Vite, Tailwind, SQLite par défaut  
Statut: API fonctionnelle avec back-office admin, facturation, support, monitoring et streaming IPTV

## 1. Résumé Exécutif

Cette API fournit une plateforme IPTV privée orientée SaaS. Elle permet à des utilisateurs et organisations de gérer des abonnements, de consulter un catalogue IPTV, d’ouvrir des sessions de streaming, de recevoir des factures, de créer des tickets support et d’être supervisés par un back-office admin.

Le backend expose des routes REST sous `/api`, sécurisées par Laravel Sanctum. Le front inclus est un back-office Blade consommant directement l’API via Axios et un token stocké côté navigateur.

Objectifs principaux:

- Authentifier les utilisateurs et protéger l’accès aux ressources.
- Gérer des organisations, membres, plans, abonnements et paiements.
- Distribuer les flux IPTV via un système round-robin entre comptes fournisseurs.
- Maintenir les sessions stream avec heartbeat et fermeture automatique.
- Administrer comptes IPTV, utilisateurs, paiements, factures, support et monitoring.
- Envoyer des emails transactionnels via SMTP.
- Envoyer des alertes opérationnelles via Telegram.
- Produire des factures PDF simples et téléchargeables.

## 2. Périmètre Fonctionnel

### Inclus

- Authentification: inscription, connexion, déconnexion, profil, OTP email, 2FA, reset mot de passe.
- Gestion SaaS: organisations, membres, plans, abonnements, essais, changement de plan.
- Paiement manuel: demande de paiement, validation admin, rejet, preuve, expiration.
- Facturation: génération facture, stockage PDF, envoi email, renvoi, téléchargement.
- IPTV: comptes Xtream/M3U, catalogue live/VOD/séries, cache, proxy stream.
- Streaming: ouverture, URL, heartbeat, fermeture, nettoyage automatique.
- Support: tickets client, réponses, notes internes, assignation, statut.
- Admin: dashboard KPI, clients, utilisateurs, abonnements, paiements, factures, comptes IPTV, sessions, support, monitoring, paramètres.
- Observabilité: health, métriques, audit logs, uptime checks.
- Notifications: SMTP et Telegram.
- Documentation API: route `/api/docs` et Swagger L5 via `/api/documentation`.

### Hors périmètre actuel

- Paiement automatique par passerelle externe.
- Player vidéo complet avancé côté front.
- Multi-langue complète.
- Factures PDF avec moteur HTML/PDF avancé.
- Webhooks de paiement.
- Application mobile native.

## 3. Architecture Technique

### Vue d’ensemble

Navigateur / Admin Blade  
-> Axios avec token Sanctum  
-> Routes Laravel `/api`  
-> Controllers  
-> Services métier  
-> Eloquent Models  
-> Base SQLite ou autre DB configurée  
-> Fournisseurs externes: IPTV Xtream/M3U, SMTP, Telegram

### Technologies

| Couche | Technologie | Rôle |
|---|---|---|
| Backend | Laravel 11 / PHP 8.2 | API REST, logique métier, sécurité |
| Auth | Laravel Sanctum | Tokens Bearer |
| Front admin | Blade + JS + Vite | Back-office consommant l’API |
| CSS | Tailwind + CSS custom | Interface admin et publique |
| Base | SQLite par défaut | Données app, jobs, sessions, cache |
| Emails | SMTP Laravel Mail | OTP, support, factures |
| Alertes | Telegram Bot API | Alertes paiement, incidents, ops |
| Docs | L5 Swagger | Documentation interactive |

## 4. Modules Applicatifs

### Authentification et Sécurité

Fichiers principaux:

- `AuthController`
- `SecurityService`
- `CheckPermission`
- `EnsureSubscriptionActive`
- `AuditRequest`
- `User`

Fonctions:

- Création de compte avec email et mot de passe.
- Connexion et génération de token Sanctum.
- Déconnexion du token courant ou de tous les tokens.
- OTP email et vérification email.
- 2FA par code email.
- Détection de connexions suspectes.
- Audit des actions sensibles.
- Rate limits par contexte: auth, API, stream, admin.

Règles:

- Les routes privées exigent `auth:sanctum`.
- Les routes de streaming exigent un abonnement actif via `subscription`.
- Les routes admin exigent `permission:admin.access` puis permissions fines.

### Organisation et SaaS

Fichiers principaux:

- `OrganizationController`
- `OrganizationService`
- `AdminSaasController`
- `Organization`
- `Subscription`
- `Plan`

Fonctions:

- Chaque utilisateur appartient à une organisation courante.
- Les organisations ont des membres, un owner, un statut et une facturation.
- Les plans définissent les limites: utilisateurs, comptes IPTV, streams simultanés, stockage.
- Les abonnements peuvent être `trialing`, `active`, `past_due`, `suspended`, `cancelled`.

### Facturation et Paiements

Fichiers principaux:

- `BillingController`
- `SubscriptionService`
- `InvoiceService`
- `PaymentTransaction`
- `Invoice`
- `PaymentMethod`

Fonctions:

- Liste des plans publics.
- Liste des moyens de paiement.
- Démarrage d’un essai.
- Demande de paiement manuelle avec référence unique.
- Validation admin d’un paiement.
- Rejet avec motif.
- Activation ou renouvellement d’abonnement après validation.
- Génération de facture PDF simple.
- Envoi email de facture.
- Téléchargement et renvoi de facture.

Cycle de paiement:

1. Le client choisit un plan.
2. Il crée une demande de paiement.
3. La transaction est `pending`.
4. L’admin valide ou rejette.
5. En validation, l’abonnement devient `active`.
6. Une facture est générée, stockée, envoyée par email.
7. Une alerte Telegram peut être envoyée.

### IPTV et Catalogue

Fichiers principaux:

- `IptvService`
- `CatalogController`
- `CatalogAccessService`
- `IptvAccount`

Fonctions:

- Support de comptes Xtream et M3U.
- Récupération des catégories live/movie/series.
- Récupération des contenus.
- Récupération des infos de série.
- Cache catalogue configurable via `IPTV_CACHE_TTL`.
- Synchronisation des limites Xtream.
- Vérification santé compte IPTV.
- Désactivation après échecs selon configuration.

### Streaming et Sessions

Fichiers principaux:

- `StreamController`
- `RoundRobinService`
- `SessionService`
- `UserSession`

Fonctions:

- Ouverture de contenu par `type` et `item_id`.
- Ouverture historique par `channelId`.
- Sélection du compte IPTV le moins occupé.
- Création d’une session avec token UUID.
- Génération d’URL de streaming.
- Proxy stream public par token.
- Heartbeat pour maintenir la session.
- Fermeture explicite.
- Nettoyage automatique des sessions inactives.

Algorithme round-robin:

1. Filtrer comptes actifs, non expirés, non désactivés.
2. Exclure comptes saturés (`active_streams >= max_streams`).
3. Trier par taux d’occupation `active_streams / max_streams`.
4. Verrouiller la ligne (`lockForUpdate`) en transaction.
5. Incrémenter `active_streams`.
6. Libérer le slot à la fermeture.

### Support

Fichiers principaux:

- `SupportController`
- `SupportTicket`
- `SupportMessage`
- `TransactionalMail`

Fonctions:

- Création de ticket par client.
- Liste des tickets client.
- Réponse client/admin.
- Filtrage des messages internes.
- Assignation admin.
- Changement de statut: `open`, `answered`, `pending`, `closed`.
- Envoi email lors de l’ouverture ou de la réponse support.

### Monitoring et Observabilité

Fichiers principaux:

- `OpsController`
- `ObservabilityService`
- `UptimeService`
- `UptimeCheck`
- `AuditLog`

Fonctions:

- Health API: statut `ok` ou `degraded`.
- Métriques: streams actifs, capacité, comptes sains, erreurs, audit.
- Audit logs.
- Uptime checks configurables.
- Exécution manuelle d’un check uptime.
- Alertes d’erreurs par seuil configurable.

### Dashboard Admin

Le dashboard admin consomme maintenant `/api/admin/saas/dashboard`.

Données renvoyées:

- Clients: total, actifs, suspendus.
- Billing: MRR, abonnements actifs, trial, past due, suspendus, pending payments, revenue vérifié.
- IPTV: comptes totaux, actifs, streams actifs, capacité, santé, incidents.
- Sessions: actives et totales.
- Support: tickets ouverts et récents.
- Paiements récents.
- Alertes globales.
- Revenue trends: daily, weekly, monthly.
- Factures: total, envoyées, téléchargées.

## 5. Endpoints API

Préfixe principal: `/api`  
Version secondaire disponible: `/api/v1` pour quelques routes de lecture.

### Auth

| Méthode | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Inscription |
| POST | `/api/auth/login` | Connexion |
| POST | `/api/auth/2fa/verify` | Vérification 2FA |
| POST | `/api/auth/forgot-password` | Demande reset |
| POST | `/api/auth/reset-password` | Reset mot de passe |
| POST | `/api/auth/email/verify` | Vérification OTP email |
| POST | `/api/auth/email/resend` | Renvoyer OTP |
| GET | `/api/auth/me` | Profil connecté |
| POST | `/api/auth/logout` | Logout token courant |
| POST | `/api/auth/logout-all` | Logout global |
| POST | `/api/auth/2fa/enable` | Activer 2FA |
| POST | `/api/auth/2fa/disable` | Désactiver 2FA |

### Billing Client

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/billing/plans` | Plans disponibles |
| GET | `/api/billing/payment-methods` | Moyens de paiement |
| GET | `/api/billing/current` | Abonnement courant |
| POST | `/api/billing/trial` | Démarrer essai |
| POST | `/api/billing/payments` | Demander paiement |
| GET | `/api/billing/payments` | Historique paiements |
| POST | `/api/billing/change-plan` | Changer de plan |
| POST | `/api/billing/cancel` | Annuler abonnement |

### Organisations

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/organizations` | Liste organisations accessibles |
| POST | `/api/organizations` | Créer organisation |
| GET | `/api/organizations/{id}` | Détail organisation |
| PUT | `/api/organizations/{id}` | Modifier organisation |
| GET | `/api/organizations/{id}/members` | Membres |
| POST | `/api/organizations/{id}/members` | Ajouter membre |
| PATCH | `/api/organizations/{id}/members/{userId}` | Modifier membre |
| DELETE | `/api/organizations/{id}/members/{userId}` | Supprimer membre |

### Catalogue et Streaming

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/catalog/categories` | Catégories par type |
| GET | `/api/catalog/items` | Contenus live/movie/series |
| GET | `/api/catalog/series/{seriesId}` | Infos série |
| GET | `/api/stream/groups` | Groupes live |
| GET | `/api/stream/channels` | Chaînes live |
| POST | `/api/stream/open` | Ouvrir contenu typé |
| POST | `/api/stream/open/{channelId}` | Ouvrir live historique |
| GET | `/api/stream/url/{sessionToken}` | URL directe/proxy |
| POST | `/api/stream/heartbeat/{sessionToken}` | Maintenir session |
| DELETE | `/api/stream/close/{sessionToken}` | Fermer session |
| GET | `/api/stream/proxy/{sessionToken}` | Proxy stream public |

### Factures

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/invoices` | Liste factures |
| GET | `/api/invoices/{id}` | Détail facture |
| GET | `/api/invoices/{id}/download` | Télécharger PDF |
| POST | `/api/invoices/{id}/resend` | Renvoyer facture |

### Support Client

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/support/tickets` | Tickets client |
| POST | `/api/support/tickets` | Créer ticket |
| GET | `/api/support/tickets/{id}` | Détail ticket |
| POST | `/api/support/tickets/{id}/reply` | Répondre |

### Administration

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/admin/saas/dashboard` | Dashboard global |
| GET | `/api/admin/saas/customers` | Clients |
| GET | `/api/admin/saas/subscriptions` | Abonnements |
| GET | `/api/admin/saas/invoices` | Factures |
| POST | `/api/admin/saas/customers/{id}/suspend` | Suspendre client |
| POST | `/api/admin/saas/customers/{id}/reactivate` | Réactiver client |
| GET | `/api/admin/stats` | Stats legacy |
| GET | `/api/admin/accounts` | Comptes IPTV |
| POST | `/api/admin/accounts` | Créer compte IPTV |
| PUT | `/api/admin/accounts/{id}` | Modifier compte IPTV |
| DELETE | `/api/admin/accounts/{id}` | Supprimer compte IPTV |
| GET | `/api/admin/accounts/{id}/status` | Statut distant/local |
| POST | `/api/admin/accounts/{id}/sync-limits` | Sync limites |
| POST | `/api/admin/accounts/{id}/refresh-cache` | Vider cache |
| GET | `/api/admin/sessions` | Sessions |
| DELETE | `/api/admin/sessions/{id}` | Fermer session |
| GET | `/api/admin/users` | Utilisateurs |
| PATCH | `/api/admin/users/{id}/toggle` | Activer/désactiver |
| PATCH | `/api/admin/users/{id}/role` | Changer rôle |
| POST | `/api/admin/users/{id}/categories` | Catégories autorisées |
| GET | `/api/admin/billing/plans` | Plans admin |
| POST | `/api/admin/billing/plans` | Créer plan |
| PUT | `/api/admin/billing/plans/{id}` | Modifier plan |
| GET | `/api/admin/billing/payment-methods` | Moyens paiement admin |
| POST | `/api/admin/billing/payment-methods` | Créer moyen |
| PUT | `/api/admin/billing/payment-methods/{id}` | Modifier moyen |
| GET | `/api/admin/billing/payments` | Paiements admin |
| POST | `/api/admin/billing/payments/{id}/verify` | Valider paiement |
| POST | `/api/admin/billing/payments/{id}/reject` | Rejeter paiement |
| GET | `/api/admin/support/tickets` | Tickets admin |
| GET | `/api/admin/support/tickets/{id}` | Détail ticket |
| POST | `/api/admin/support/tickets/{id}/reply` | Répondre |
| PATCH | `/api/admin/support/tickets/{id}/assign` | Assigner |
| PATCH | `/api/admin/support/tickets/{id}/status` | Changer statut |
| GET | `/api/admin/ops/health` | Health |
| GET | `/api/admin/ops/metrics` | Métriques |
| GET | `/api/admin/ops/audit-logs` | Audit logs |
| GET | `/api/admin/ops/uptime-checks` | Uptime checks |
| POST | `/api/admin/ops/uptime-checks` | Créer check |
| POST | `/api/admin/ops/uptime-checks/{id}/run` | Exécuter check |
| GET | `/api/admin/legal` | Documents légaux |
| POST | `/api/admin/legal` | Créer document |
| PUT | `/api/admin/legal/{id}` | Modifier document |

## 6. Modèle de Données

Tables principales:

- `users`: identité, rôle, activation, email vérifié, OTP, sécurité.
- `organizations`: espace client, owner, statut, facturation.
- `organization_user`: membres, rôles internes, statut.
- `plans`: offres, prix, limites.
- `subscriptions`: abonnement organisation, statut, dates.
- `payment_methods`: moyens de paiement configurables.
- `payment_transactions`: demandes, preuves, validation, référence.
- `invoices`: factures, PDF, statut, métadonnées.
- `iptv_accounts`: comptes fournisseur, type, santé, limites, streams.
- `user_sessions`: sessions de lecture, token, heartbeat.
- `support_tickets`: tickets support.
- `support_messages`: messages ticket.
- `legal_documents`: CGU, confidentialité, autres documents.
- `audit_logs`: journal des actions sensibles.
- `uptime_checks`: sondes de disponibilité.
- `personal_access_tokens`: tokens Sanctum.
- `jobs`, `cache`, `sessions`: tables Laravel.

## 7. Configuration `.env`

Les valeurs ci-dessous proviennent de `.env.example` et de la configuration locale observée. Les secrets réels sont volontairement masqués.

### Application

| Variable | Valeur actuelle ou exemple | Description |
|---|---|---|
| `APP_NAME` | Laravel | Nom app |
| `APP_ENV` | local | Environnement |
| `APP_URL` | `http://192.168.11.200:8001` | URL backend locale |
| `FRONTEND_URL` | `http://192.168.11.200:5173` | URL front/Vite |
| `DB_CONNECTION` | sqlite | Base utilisée localement |
| `ALLOWED_ORIGINS` | localhost + IP LAN | CORS autorisés |

### IPTV

| Variable | Valeur | Description |
|---|---|---|
| `IPTV_CACHE_TTL` | 900 | Durée cache catalogue, en secondes |
| `IPTV_VALIDATE_STREAM_ON_OPEN` | true | Validation stream à l’ouverture |
| `IPTV_STREAM_OPEN_ATTEMPTS` | 3 | Tentatives d’ouverture |
| `IPTV_STREAM_VALIDATE_TIMEOUT` | 5 | Timeout validation |
| `IPTV_HEALTH_DISABLE_AFTER_FAILURES` | 3 | Désactivation après échecs |
| `IPTV_EXPIRY_ALERT_DAYS` | 7 | Alerte expiration compte |

### Sécurité et limites

| Variable | Valeur | Description |
|---|---|---|
| `REQUIRE_EMAIL_VERIFICATION` | false | Vérification email obligatoire |
| `EMAIL_VERIFICATION_EXPIRES` | 60 | Expiration lien/code email |
| `EMAIL_OTP_TTL` | 15 | Durée OTP email |
| `TWO_FACTOR_CODE_TTL` | 10 | Durée code 2FA |
| `AUTH_RATE_LIMIT_PER_MINUTE` | 10 | Limite auth |
| `API_RATE_LIMIT_PER_MINUTE` | 120 | Limite API |
| `STREAM_RATE_LIMIT_PER_MINUTE` | 60 | Limite stream |
| `ADMIN_RATE_LIMIT_PER_MINUTE` | 60 | Limite admin |
| `SUSPICIOUS_LOGIN_WINDOW_MINUTES` | 10 | Fenêtre détection login suspect |
| `SUSPICIOUS_LOGIN_THRESHOLD` | 5 | Seuil login suspect |

### Billing

| Variable | Valeur | Description |
|---|---|---|
| `DEFAULT_TRIAL_PLAN` | free | Plan d’essai par défaut |
| `DEFAULT_TRIAL_DAYS` | 7 | Durée essai |
| `PAYMENT_REQUEST_EXPIRES_HOURS` | 24 | Expiration demande paiement |
| `SUBSCRIPTION_GRACE_DAYS` | 0 | Grâce abonnement |
| `SUBSCRIPTION_REMINDER_DAYS` | 7,3,1 | Rappels avant échéance |

## 8. SMTP et Emails

Le système utilise Laravel Mail avec le mailer SMTP.

Configuration locale observée:

| Variable | Valeur documentée | Rôle |
|---|---|---|
| `MAIL_MAILER` | smtp | Transport email |
| `MAIL_SCHEME` | smtp | Schéma SMTP déclaré |
| `MAIL_HOST` | smtp.gmail.com | Serveur SMTP |
| `MAIL_PORT` | 587 | Port SMTP |
| `MAIL_USERNAME` | adresse Gmail configurée | Identifiant SMTP |
| `MAIL_PASSWORD` | *** masqué *** | Mot de passe/app password |
| `MAIL_FROM_ADDRESS` | adresse Gmail configurée | Expéditeur |
| `MAIL_FROM_NAME` | `${APP_NAME}` | Nom expéditeur |
| `INVOICE_COMPANY_NAME` | `${APP_NAME}` ou valeur dédiée | Nom société facture |
| `INVOICE_COMPANY_EMAIL` | `${MAIL_FROM_ADDRESS}` | Email société facture |

Emails envoyés:

- OTP de vérification email.
- Codes 2FA.
- Reset password.
- Ouverture ticket support.
- Réponse support.
- Facture après validation paiement.
- Renvoi de facture.

Exigences SMTP:

- Utiliser un mot de passe applicatif Gmail, jamais le mot de passe principal.
- Ne pas versionner `.env`.
- En production, utiliser `MAIL_SCHEME=tls` ou config SMTP validée.
- Sur Gmail, vérifier SPF/DKIM/DMARC si un domaine personnalisé est utilisé.

## 9. Telegram

Le service `TelegramAlertService` envoie des messages vers l’API Bot Telegram.

Configuration locale observée:

| Variable | Valeur documentée | Rôle |
|---|---|---|
| `TELEGRAM_ALERTS_ENABLED` | true | Active les alertes |
| `TELEGRAM_BOT_TOKEN` | *** masqué *** | Token bot |
| `TELEGRAM_CHAT_ID` | *** masqué *** | Destination |
| `TELEGRAM_API_IP` | 149.154.166.110 | IP forcée via cURL resolve |

Fonctionnement:

1. Le service reçoit un message et un contexte.
2. Il vérifie que Telegram est activé et configuré.
3. Il formate le message en Markdown.
4. Il POST vers `https://api.telegram.org/bot{token}/sendMessage`.
5. Si `TELEGRAM_API_IP` est défini, il force la résolution DNS de `api.telegram.org`.
6. En cas d’échec, il log l’erreur sans bloquer l’action métier.

Usage actuel:

- Alerte paiement vérifié.
- Alertes possibles de monitoring, erreurs, santé IPTV et expiration.

Sécurité:

- Le token bot et le chat id sont secrets.
- Si un token a été partagé dans un document, un commit ou une capture, il doit être révoqué et recréé dans BotFather.

## 10. Permissions et Rôles

Rôles globaux attendus:

- `super_admin`
- `admin`
- `billing`
- `support`
- `ops`
- `user`

Permissions utilisées:

- `admin.access`
- `customer.read`
- `customer.write`
- `billing.read`
- `billing.write`
- `invoice.read`
- `support.read`
- `support.write`
- `ops.read`
- `ops.write`

Principe:

- Les admins accèdent au back-office selon leurs permissions.
- Les clients accèdent seulement à leur organisation, factures, support et streaming.
- Les admins peuvent forcer la fermeture des sessions.
- Les messages support internes sont masqués aux clients.

## 11. Workflows Détaillés

### Connexion utilisateur

1. POST `/api/auth/login`.
2. Si 2FA requis, POST `/api/auth/2fa/verify`.
3. Stockage du token côté navigateur.
4. Appels API avec `Authorization: Bearer {token}`.
5. GET `/api/auth/me` pour afficher identité.

### Ouverture stream

1. Client choisit un contenu.
2. POST `/api/stream/open` ou `/api/stream/open/{channelId}`.
3. Middleware vérifie abonnement.
4. `RoundRobinService` sélectionne un compte.
5. `SessionService` crée une session.
6. API retourne token et/ou URL.
7. Client appelle heartbeat périodiquement.
8. Fermeture explicite ou nettoyage automatique après inactivité.

### Validation paiement

1. Client crée une demande paiement.
2. Admin voit le paiement `pending`.
3. Admin valide via `/api/admin/billing/payments/{id}/verify`.
4. `SubscriptionService` active l’abonnement.
5. `InvoiceService` crée PDF et envoie email.
6. `TelegramAlertService` envoie alerte.
7. Dashboard se met à jour via API.

### Support

1. Client crée ticket.
2. Email transactionnel envoyé.
3. Admin consulte, assigne, répond.
4. Réponse non interne envoyée par email.
5. Ticket peut être `answered`, `pending` ou `closed`.

## 12. Front-Office et Back-Office

Routes web:

- `/`: page publique.
- `/login`: connexion.
- `/register`: inscription.
- `/dashboard`: redirection vers `/admin/dashboard`.
- `/admin/dashboard`: dashboard.
- `/admin/customers`: clients.
- `/admin/users`: utilisateurs.
- `/admin/subscriptions`: abonnements.
- `/admin/payments`: paiements.
- `/admin/invoices`: factures.
- `/admin/accounts`: comptes IPTV.
- `/admin/sessions`: sessions.
- `/admin/support`: support.
- `/admin/monitoring`: monitoring.
- `/admin/settings`: paramètres.

Le back-office est une interface Blade, mais sa donnée vient de l’API. La logique JS se trouve dans `resources/js/app.js`.

## 13. Données Initiales

Seeder:

- Admin: `admin@example.com` / `password`.
- User test: `test@example.com` / `password`.
- Plans: free, basic, pro, enterprise.
- Moyens de paiement: mobile money, virement bancaire.
- Documents légaux: terms, privacy.
- Uptime check API Health.

Note: les identifiants de seed sont destinés au développement et doivent être changés ou supprimés en production.

## 14. Exigences Non Fonctionnelles

Performance:

- Cache IPTV par défaut: 900 secondes.
- Réponses API standard: cible < 300 ms hors fournisseur IPTV.
- Ouverture stream: retry configurable.
- Éviter saturation via limites `max_streams`.

Disponibilité:

- Uptime checks configurables.
- Health API disponible pour supervision.
- Scheduler requis pour nettoyage sessions et checks périodiques.

Sécurité:

- Secrets uniquement dans `.env`.
- APP_KEY obligatoire pour chiffrement.
- HTTPS obligatoire en production.
- CORS limité.
- Rate limiting actif.
- Audit logs pour actions sensibles.
- Tokens Sanctum révocables.

Maintenabilité:

- Séparation Controller / Service / Model.
- Tests PHPUnit présents.
- Swagger disponible.
- Variables d’environnement centralisées.

## 15. Commandes d’Exploitation

Installation:

```bash
composer install
npm install
cp .env.example .env
php artisan key:generate
php artisan migrate --seed
npm run build
```

Développement:

```bash
php artisan serve --host=127.0.0.1 --port=8000
npm run dev
```

Tests:

```bash
php artisan test
```

Scheduler:

```bash
php artisan schedule:run
```

Commandes planifiées déclarées:

- `sessions:cleanup` toutes les minutes.
- `iptv:health-check --disable-invalid` toutes les 15 minutes.

## 16. Critères de Recette

### Auth

- Un utilisateur peut s’inscrire.
- Un utilisateur peut se connecter et obtenir un token.
- Une route protégée refuse l’accès sans token.
- Un utilisateur peut se déconnecter.
- OTP/2FA fonctionnent avec SMTP actif.

### Billing

- Les plans sont listés.
- Un paiement peut être demandé.
- Un admin peut valider ou rejeter.
- Une validation active l’abonnement.
- Une facture est générée, envoyée et téléchargeable.

### IPTV

- Un admin peut créer un compte IPTV.
- Le catalogue remonte depuis les comptes actifs.
- Le round-robin ne dépasse pas `max_streams`.
- La fermeture de session décrémente `active_streams`.
- Le cleanup ferme les sessions inactives.

### Support

- Un client peut créer un ticket.
- Un admin peut répondre.
- Les messages internes restent invisibles côté client.
- Les emails support sont envoyés.

### Admin

- Le dashboard affiche des KPI depuis l’API.
- Les pages admin chargent les données réelles.
- Les permissions bloquent les rôles non autorisés.
- Les actions sensibles sont auditées.

### Monitoring

- `/api/admin/ops/health` retourne un statut.
- Les uptime checks sont listés et exécutables.
- Telegram envoie une alerte lorsque configuré.

## 17. Risques et Points d’Attention

- Les fournisseurs IPTV peuvent être instables: prévoir cache, retries, health check.
- Les tokens Telegram et mots de passe SMTP doivent rester secrets.
- Les comptes seed ne doivent pas rester en production.
- SQLite convient au local, mais production devrait utiliser MySQL/PostgreSQL.
- Le proxy stream peut consommer beaucoup de bande passante.
- Les factures PDF sont minimalistes; un moteur PDF dédié peut être nécessaire.
- Le projet contient un front Blade admin, pas une SPA indépendante complète.

## 18. Recommandations Production

- `APP_ENV=production`
- `APP_DEBUG=false`
- Base MySQL/PostgreSQL managée ou VPS.
- HTTPS via Nginx/Caddy.
- Queue worker permanent.
- Cron Laravel scheduler.
- Rotation logs.
- Sauvegardes DB et storage.
- Regénération des secrets si exposés.
- Monitoring externe du endpoint `/up` et `/api/admin/ops/health`.
- Domaine email avec SPF/DKIM/DMARC.

## 19. Annexes

### Headers API

```http
Authorization: Bearer {token}
Accept: application/json
Content-Type: application/json
```

### Codes HTTP principaux

| Code | Signification |
|---|---|
| 200 | Succès |
| 201 | Créé |
| 401 | Non authentifié |
| 402 | Abonnement requis ou paiement requis |
| 403 | Permission insuffisante |
| 404 | Ressource introuvable |
| 422 | Validation ou action impossible |
| 429 | Rate limit |
| 503 | Aucun compte IPTV disponible ou health dégradé |

### Glossaire

- IPTV: télévision via protocole internet.
- Xtream: API fournisseur IPTV courante.
- M3U: playlist de flux.
- VOD: vidéo à la demande.
- HLS: protocole de streaming.
- Round-robin: distribution équilibrée.
- Heartbeat: signal de maintien de session.
- Sanctum: auth token Laravel.
- OTP: code à usage unique.
- MRR: revenu mensuel récurrent.
- Uptime: disponibilité d’un service.

---

Document rédigé à partir du code source actuel, des routes Laravel, des services métier et de la configuration environnementale observée. Les secrets SMTP et Telegram sont volontairement masqués.
