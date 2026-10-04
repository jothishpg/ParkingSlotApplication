# Multi Parking System

## Gmail sender setup

Forgot-password emails use the one global Gmail sender account connected by an administrator at `/api/admin/email-setup`. Any administrator may connect or replace the sender. The page displays the sender address and provides reconnect and disconnect actions. OAuth access and refresh tokens are encrypted before they are written to PostgreSQL; access tokens are refreshed and their encrypted value and expiry are updated when needed.

### Before deploying

1. In the Google Cloud project used by the existing OAuth client, enable the **Gmail API** and configure `https://www.googleapis.com/auth/gmail.send` as an OAuth consent-screen scope.
2. Add the exact deployed callback URL to the OAuth client's authorized redirect URIs:
   `https://<your-public-host>/<your-app-context>/api/admin/email-setup/callback`
   Set `GMAIL_REDIRECT_URI` to that exact URL. Keep `GOOGLE_REDIRECT_URI` for the existing sign-in callback; the two redirect URIs are different.
3. Configure `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` for the same OAuth web client already used by Google sign-in. Configure `APP_BASE_URL` as the public application base URL used to build admin links.
4. Generate a random 32-byte AES key, Base64-encode it, and set it as `GMAIL_TOKEN_ENCRYPTION_KEY` in the deployment platform's secret/environment settings. For local PowerShell:

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   ```

   Keep this key stable and backed up. Changing or losing it makes the saved Gmail tokens undecryptable; administrators will have to reconnect the account.
5. Apply `database/migrations/V1__create_email_sender_config.sql` to the PostgreSQL database **before** deploying/starting this application version.
6. If the OAuth consent screen is in Testing mode, add the authorizing administrator as a test user. Google testing-mode refresh tokens for non-basic scopes can expire after seven days. For ongoing production use, complete the consent-screen publishing and any Google verification requirements applicable to the project.
7. Deploy the app over HTTPS, then sign in as an admin and use **Email Setup → Connect Gmail**. Select the desired sender account and grant access. The callback checks the granted send scope and verified Google account, then sends a one-time setup confirmation email to that account to verify Gmail API access. It replaces the stored sender only after those checks succeed.

No Gmail access/refresh tokens are entered manually. A missing or undecryptable sender setup disables email password reset; SMS reset remains available. Disconnect removes the application's stored credentials but does not revoke the Google grant in the user's Google Account settings.

## Facebook / WhatsApp setup

Each customer database stores one Meta App and one selected Business Portfolio. An administrator enters that customer's App ID and App Secret under **Facebook / WhatsApp Setup**; the server validates the app credentials and encrypts the secret before saving them. The admin then authorizes the app, selects a Business Portfolio and one or more WhatsApp Business Accounts, and completes system-user token provisioning. The temporary OAuth user token is held only in a short-lived encrypted, HttpOnly cookie during the selection/provisioning steps; it is not stored in PostgreSQL. The app secret and generated system-user tokens are encrypted at rest.

### Before deploying

1. Configure the Meta app with the WhatsApp product and request access to `business_management`, `whatsapp_business_management`, `whatsapp_business_messaging`, and `whatsapp_business_manage_events`. Complete Meta app review/Advanced Access requirements applicable to the customer app and its intended users.
2. Register the exact public HTTPS callback URL in the Meta app's Facebook Login settings:
   `https://<your-public-host>/<your-app-context>/api/admin/facebook-setup/callback`
   Set `META_FACEBOOK_REDIRECT_URI` to that exact URL. It is application configuration and is not entered by customers.
3. Generate a random 32-byte AES key, Base64-encode it, and set it as `META_TOKEN_ENCRYPTION_KEY`. This stable key encrypts customer App Secrets, generated system-user tokens, and the short-lived OAuth flow cookie. Back it up securely; changing or losing it makes saved Meta credentials undecryptable.
4. Optionally set `META_WHATSAPP_API_VERSION` (defaults to `v23.0`) and `APP_BASE_URL` in the deployment environment. Deploy over HTTPS.
5. Sign in as an administrator, open **Facebook / WhatsApp Setup**, validate the customer's App ID and App Secret, then connect Facebook. The authorizing Facebook account must have the necessary access to the Business Portfolio and WhatsApp assets. Setup saves the portfolio, WABAs, phone numbers, system-user identities, permissions, assignments, and generated tokens only after the provisioning checks succeed.

The admin system-user token receives only `business_management` and is kept for system-user/business administration; it is not used to send WhatsApp messages. Employee system-user tokens receive the WhatsApp permissions without `business_management` and are assigned to selected WABAs. Outgoing notifications select an active employee token at send time. The admin controls the Meta employee system users in Meta Business Settings; the application does not create a Meta system user for each local application employee. If no eligible employee token or phone number is configured, WhatsApp message delivery is unavailable. `MetaOAuthService.credentialsForPermission(...)` provides the eligible employee token for a required permission, including WhatsApp management for template operations.

## User authenticator two-factor authentication

Users can enable TOTP under **Security / Two-factor authentication** after signing in. Setup presents a QR code and a standard Base32 key for any TOTP-compatible authenticator app. The QR code is generated by the application and is not sent to an external QR service. The user must verify a current 6-digit code before 2FA is enabled. Ten one-time recovery codes are shown once; the user can replace them after proving possession of an authenticator/recovery code. Disabling 2FA requires a current factor and, for accounts with a local password, the password as well.

When 2FA is enabled, both password and Google sign-in require a second verification step before the normal `AUTH_TOKEN` session is issued. Users without 2FA keep the existing login flow. Five failed codes invalidate a login challenge; repeated failures also temporarily lock further challenges for that account.

Before deploying:

1. Apply `database/migrations/V2__create_user_two_factor_auth.sql` to the same PostgreSQL database after V1.
2. Generate a random 32-byte AES key, Base64-encode it, and set it as `TOTP_ENCRYPTION_KEY` in the deployment environment. The key encrypts each user's authenticator secret at rest; it must remain stable and be backed up securely. Losing or changing it prevents affected users from generating valid codes until 2FA is reset.

   On PowerShell, generate a key with:

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   ```

3. Deploy over HTTPS. The authenticator secret is displayed only during setup. Users should store their recovery codes securely; each can be used once.

The migration is required before deploying this application version. Existing accounts remain unaffected until each user enables 2FA.
