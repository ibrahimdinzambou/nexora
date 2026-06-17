# Nexora

Monorepo propre pour l'application Nexora IPTV SaaS.

## Structure

- `api/` : API Spring Boot.
- `frontend/` : frontend statique HTML/CSS/JavaScript.

## Notes

- Les secrets locaux ne sont pas inclus. Utilisez `api/.env.example` comme base de configuration.
- Les fichiers runtime (`target`, `data`, logs, dossiers IDE) sont exclus.
- En deploiement separe, servez `frontend/` derriere le meme domaine que l'API ou configurez un proxy vers les routes `/api/...`.
