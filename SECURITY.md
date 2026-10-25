# Security

The artwork manager is a temporary paired HTTP service on the local network. Keep it on a trusted LAN; do not expose its port to the internet. It closes when sharing expires, Mero leaves the foreground, or the screen turns off.

Home Assistant uses HTTPS with normal certificate validation. Its token is encrypted with an app-owned Android Keystore key, stored in private app data and excluded from artwork exports. A credential still inherits its Home Assistant account's permissions. Use a dedicated read-only account.

Report suspected vulnerabilities privately through GitHub's **Report a vulnerability** option when available. If it is unavailable, open an issue asking for a private contact without including exploit details or credentials. Public reports must not contain tokens, passwords, private server names, pairing codes or device backups.
