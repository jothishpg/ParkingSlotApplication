# Multi Parking System

## Encrypted application settings

Run `org.example.config.EncryptedSettingWriter` from the IDE to create or update a setting. It creates `app_settings` if needed, prompts for `key_name` and `key_value`, encrypts the value, and inserts or updates the row with the current database timestamp.

Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD` for the database connection, and set `GLOBAL_ENCRYPTION_KEY` to a Base64-encoded random 32-byte AES key. The value is hidden during input when run in a terminal console. Generate the key in PowerShell:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

Keep `GLOBAL_ENCRYPTION_KEY` outside the database and back it up securely. Losing it makes values saved by this utility undecryptable.

Apply `database/migrations/V20__create_app_settings.sql` first. The application reads and decrypts these seven settings from `app_settings`; do not set them in the environment: `GMAIL_TOKEN_ENCRYPTION_KEY`, `META_TOKEN_ENCRYPTION_KEY`, `OTP_HMAC_SECRET`, `RAZORPAY_TOKEN_ENCRYPTION_KEY`, `RAZORPAY_WEBHOOK_SECRET`, `SESSION_SECRET`, and `TOTP_ENCRYPTION_KEY`. Startup creates any that are missing, but pre-seed existing values with `EncryptedSettingWriter` before deploying if they must remain unchanged. The three `*_ENCRYPTION_KEY` values must each be Base64-encoded random 32-byte AES keys. Preserve existing values when migrating so encrypted credentials, active OTP requests, and signed sessions remain valid.

For an existing deployment, while the old values are still available in the environment, run `EncryptedSettingWriter` once per setting using the same key name and value. Verify all seven rows exist, deploy the new version, then remove those seven values from the environment. Do not change their values during this move; changing one can invalidate ciphertext, OTP requests, signatures, or webhook verification.

At startup, the application creates and encrypts any missing rows for the seven listed secrets, without replacing existing values. `GLOBAL_ENCRYPTION_KEY` must already be available in the environment, and V20 must be applied; startup fails if it cannot initialize these settings. The initial admin bootstrap continues to read `ADMIN_EMAIL`, `ADMIN_NAME`, `ADMIN_PHONE_NUMBER`, and `ADMIN_PASSWORD` from environment variables. The password is hashed before it is saved to `local_auth`; bootstrap variables are not used to replace an existing admin password.

Startup-generated settings use Base64-encoded random 32-byte values. After the first startup, open the admin-only **Razorpay Setup** page and copy the displayed `RAZORPAY_WEBHOOK_SECRET` into Razorpay's webhook configuration. Until Razorpay has the same value, webhook signatures will not verify. If preserving previously used secrets, especially encryption keys, seed those existing values with `EncryptedSettingWriter` before the first startup; generated replacements cannot decrypt older data or verify signatures made with old values.

## Razorpay payments

Checkout supports cash and native Razorpay Payment Links with merchant OAuth setup, a 15-minute window plus a request-transit allowance, signed webhooks and background reconciliation. Razorpay sends the initial link SMS when the API creates the payment link; WhatsApp link and status messages use the message queue. WhatsApp uses the existing booking template for links and pending/error updates, and the checkout template for successful payments. Apply migrations V5 through V8 in order, skipping versions already applied. See the [Razorpay setup guide](docs/razorpay-setup.md) and [payment messaging guide](docs/payment-messaging.md). This follows the existing one-business-per-database model.

## Gmail sender setup

Forgot-password emails use the one global Gmail sender account connected by an administrator at `/api/admin/email-setup`. Any administrator may connect or replace the sender. The page displays the sender address and provides reconnect and disconnect actions. OAuth access and refresh tokens are encrypted before they are written to PostgreSQL; access tokens are refreshed and their encrypted value and expiry are updated when needed.

### Before deploying

1. Apply `database/migrations/V9__create_google_oauth_config.sql` and `database/migrations/V20__create_app_settings.sql` to each customer database.
2. In the customer's Google Cloud project, enable the **Gmail API** and configure `https://www.googleapis.com/auth/gmail.send` and `https://www.googleapis.com/auth/gmail.metadata` as OAuth consent-screen scopes. Metadata enables application-managed no-reply notices; `gmail.settings.basic` is not needed.
3. Add both exact deployed callback URLs to the OAuth client's authorized redirect URIs:
   `https://<your-public-host>/<your-app-context>/api/auth/google/callback`
   `https://<your-public-host>/<your-app-context>/api/admin/email-setup/callback`
   The application generates both callback URLs from `APP_BASE_URL` (the public application base URL): Google sign-in uses `/api/auth/google/callback`, and Gmail setup uses `/api/admin/email-setup/callback`. Register these exact URLs with the Google OAuth client; no redirect-URL environment variables are needed.
4. Generate a random 32-byte AES key, Base64-encode it, and store it in `app_settings` under `GMAIL_TOKEN_ENCRYPTION_KEY` using `EncryptedSettingWriter`. This key encrypts the database-stored OAuth client secret and Gmail tokens; it must remain stable across restarts. Zoho client secrets and tokens also use this key. `GLOBAL_ENCRYPTION_KEY` remains in the deployment environment and encrypts/decrypts values in `app_settings`. For local PowerShell:

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   ```

   Keep both keys stable and backed up. Changing or losing `GMAIL_TOKEN_ENCRYPTION_KEY` makes stored Gmail tokens and the Google OAuth client secret undecryptable. If that setting is lost, provision a replacement, re-enter the OAuth client credentials in Email Setup, and reconnect Gmail.
5. Apply `database/migrations/V1__create_email_sender_config.sql` if not already applied, then run the manual schema SQL in [automatic Gmail replies](docs/gmail-auto-replies.md) before deployment. No auto-reply migration file is provided. Reconnect Gmail once to grant metadata access.
6. If the OAuth consent screen is in Testing mode, add the authorizing administrator as a test user. Google testing-mode refresh tokens for non-basic scopes can expire after seven days. For ongoing production use, complete the consent-screen publishing and any Google verification requirements applicable to the project.
7. Deploy the app over HTTPS, sign in as an administrator, and open **Email Setup**. Enter the Google OAuth web client ID and secret there; the secret is encrypted at rest and is never returned to the page. This single per-database client is used for Google sign-in and Gmail authorization. Changing it disconnects the saved Gmail sender, which must then be connected again.
8. Use **Connect Gmail**, select the desired sender account, and grant access. The callback checks the granted send scope and verified Google account, then sends a one-time setup confirmation email to that account to verify Gmail API access. It replaces the stored sender only after those checks succeed.

No Gmail access/refresh tokens are entered manually. A missing or undecryptable sender setup disables email password reset; SMS reset remains available. Disconnect removes the application's stored credentials but does not revoke the Google grant in the user's Google Account settings.

## Zoho Cliq OAuth setup

The application stores one shared Zoho Cliq OAuth client and one organization connection. An administrator can configure it at **Zoho Cliq Setup**. The client secret and OAuth access/refresh tokens are encrypted at rest with the `GMAIL_TOKEN_ENCRYPTION_KEY` database setting; access tokens are refreshed when needed. Admins can create one organization-scoped webhook bot through the Cliq v3 Bots API. The bot's message handler forwards incoming messages to `/api/cliq/webhook`, which replies through Cliq's per-event `response_url`. Users can request booking details by sending `BookingID:19, Vehicle_Number:TN28MA1829`; the bot checks both values and reports the fee accrued through the current time for active bookings.

1. Apply migrations V17 through V19 in order after V16, skipping any already applied. V18 safely removes the old data-center column if it exists; V19 stores the created bot ID, unique name, configuration, and handler status.
2. Create a server-based OAuth client in the Zoho India API Console (`https://api-console.zoho.in/#web`). Register the exact callback URL shown on the setup page:
   `https://<your-public-host>/<your-app-context>/api/admin/zoho-setup/callback`
   Set `ZOHO_REDIRECT_URI` to this exact URL and `APP_BASE_URL` to the public application base URL. If `ZOHO_REDIRECT_URI` is omitted, the callback is built from `APP_BASE_URL`.
3. Keep the `GMAIL_TOKEN_ENCRYPTION_KEY` database setting and `GLOBAL_ENCRYPTION_KEY` environment value stable and backed up. The former protects Gmail and Zoho OAuth credentials; the latter encrypts settings in `app_settings`.
4. Sign in as an administrator, open **Zoho Cliq Setup**, enter the OAuth client ID and secret, save them, then select **Connect Zoho**. This setup uses the Zoho India OAuth server and requests `ZohoCliq.Bots.CREATE`, `ZohoCliq.Bots.READ`, `ZohoCliq.Bots.UPDATE`, and `ZohoCliq.Bots.DELETE`. Reconnect after deploying this version so the saved grant includes all four scopes.
5. In **Create webhook bot**, enter a display name (up to 20 characters) and description (up to 300 characters). The bot's access level is always set to `organization`; it is not requested from the administrator. The app supplies the public HTTPS `/api/cliq/webhook` URL. Creating the bot also creates its `message_handler` with `message`, `user`, and `chat` permissions, so Cliq can forward incoming messages to the application.
6. Booking confirmation WhatsApp messages include both the booking ID and vehicle number. Ticket assigners must enter vehicle numbers in the format `AB01A1234` or `AB01CD1234` (two letters, two digits, one or two letters, four digits); spaces and hyphens are ignored when validating and storing the number. Cliq users can retrieve a booking by sending `BookingID:19, Vehicle_Number:TN28MA1829`; matching ignores case, whitespace, and hyphens. Active bookings show a fee calculated through the current time; completed bookings show the final recorded fee. Users can send `Fee details` (or ask about fees, rates, or charges) to see the current per-minute pricing tiers for each vehicle type, read live from `vehicle_rate`.
7. Admins can use **Alert Current Bookings** from the dashboard after re-entering their admin password. Alerts can target all active bookings, a floor, or a floor/slot. The message is delivered to each distinct active-booking phone number with the assigned booking confirmation WhatsApp template.

The create call uses Zoho's `POST /api/v3/bots` and then `POST /api/v3/bots/{BOT_ID}/handlers`. Refer to the official [Create a bot](https://www.zoho.com/cliq/help/restapi/v3/bots/#create-a-bot), [Create a handler](https://www.zoho.com/cliq/help/restapi/v3/bots/#create-a-handler-for-a-specific-bot), and [OAuth scopes](https://www.zoho.com/cliq/help/restapi/v3/oauth/) documentation. If bot creation succeeds but handler setup fails, use **Retry message handler**; do not create another bot.

From **Zoho Cliq Setup**, select **Fetch bot details from Zoho** to call `GET https://cliq.zoho.in/api/v3/bots/{BOT_ID}` and display the returned details in the page. This requires the `ZohoCliq.Bots.READ` scope.

Use **Delete bot from Zoho Cliq** to permanently delete the configured bot and its handlers in Zoho, then remove its local configuration. The action requires the `ZohoCliq.Bots.DELETE` scope; reconnect Zoho if the existing authorization does not include it. A failed Zoho deletion leaves the local bot configuration intact.

## Facebook / WhatsApp setup

Each customer database stores one Meta App and one selected Business Portfolio. An administrator enters that customer's App ID and App Secret under **Facebook / WhatsApp Setup**; the server validates the app credentials and encrypts the secret before saving them. The admin then authorizes the app, selects a Business Portfolio and one or more WhatsApp Business Accounts, and completes system-user token provisioning. The temporary OAuth user token is held only in a short-lived encrypted, HttpOnly cookie during the selection/provisioning steps; it is not stored in PostgreSQL. The app secret and generated system-user tokens are encrypted at rest.

### Before deploying

1. Configure the Meta app with the WhatsApp product and request access to `business_management`, `whatsapp_business_management`, `whatsapp_business_messaging`, and `whatsapp_business_manage_events`. Complete Meta app review/Advanced Access requirements applicable to the customer app and its intended users.
2. Register the exact public HTTPS callback URL in the Meta app's Facebook Login settings:
   `https://<your-public-host>/<your-app-context>/api/admin/facebook-setup/callback`
   The application generates this callback URL from `APP_BASE_URL` using `/api/admin/facebook-setup/callback`. Register that exact URL in the Meta app; no redirect-URL environment variable is needed.
3. Generate a random 32-byte AES key, Base64-encode it, and store it in `app_settings` under `META_TOKEN_ENCRYPTION_KEY` using `EncryptedSettingWriter`. This stable key encrypts customer App Secrets, generated system-user tokens, and the short-lived OAuth flow cookie. Back it up securely; changing or losing it makes saved Meta credentials undecryptable. `GLOBAL_ENCRYPTION_KEY` remains in the environment and must also be backed up.
4. Set `APP_BASE_URL` to the public application base URL in the deployment environment and deploy over HTTPS. The Meta Graph API version is configured in the application as `v23.0`.
5. Sign in as an administrator, open **Facebook / WhatsApp Setup**, validate the customer's App ID and App Secret, then connect Facebook. The authorizing Facebook account must have the necessary access to the Business Portfolio and WhatsApp assets. Setup saves the portfolio, WABAs, phone numbers, system-user identities, permissions, assignments, and generated tokens only after the provisioning checks succeed.

The admin system-user token receives only `business_management` and is kept for system-user/business administration; it is not used to send WhatsApp messages. Employee system-user tokens receive the WhatsApp permissions without `business_management` and are assigned to selected WABAs. Outgoing notifications load and decrypt an active employee token and its assigned phone number ID from PostgreSQL at send time; `META_WHATSAPP_ACCESS_TOKEN` and `META_WHATSAPP_PHONE_NUMBER_ID` are not used. The Meta Graph API version is configured in the application as `v23.0`. The admin controls the Meta employee system users in Meta Business Settings; the application does not create a Meta system user for each local application employee. If no eligible employee token or phone number is configured, WhatsApp message delivery is unavailable. `MetaOAuthService.credentialsForPermission(...)` provides the eligible employee token for a required permission, including WhatsApp management for template operations.

### WhatsApp message templates

After connecting the Business Portfolio and provisioning an employee system-user token with `whatsapp_business_management` access to the selected WABA, open **Facebook / WhatsApp Setup → Templates**. The page lists template name, category, language, status, and a component preview. Administrators can submit templates for Meta review, edit components of approved or rejected templates, and delete a single language variant without deleting the template's other languages.

The editor accepts WhatsApp component JSON so it can represent text and image headers, body text and parameter examples, footers, buttons, carousels, authentication components, and other supported component types. JPEG and PNG header example images up to 5 MB can be uploaded from the editor; the application obtains a temporary Meta upload handle and inserts it into the header component. Video and document uploads are not supported. Meta validates template structure and policy on submission; API validation or permission errors are shown in the admin page. New or edited templates must complete Meta review before they can be used to send messages.

The same Templates page lets administrators assign an approved template to booking confirmation or checkout. Booking requires exactly five distinct body variables; checkout requires exactly four. Templates with variables in headers or buttons, incompatible body counts, or a non-approved status are disabled, with the reason shown below the choices. The service re-fetches and validates an assignment before sending, so a Meta-side template change cannot silently produce a malformed notification. Booking and checkout notifications are not sent unless an approved compatible template is explicitly assigned. OTP remains on the current booking-template behavior and is not admin-assignable; its message type is configured for one body variable when OTP template selection is enabled later.

Apply `database/migrations/V4__create_whatsapp_template_assignments.sql` after the existing database migrations before deploying this template-assignment feature. It creates or updates the three message-type rules and creates the assignment table; it does not assign a template automatically.

## User authenticator two-factor authentication

Users can enable TOTP under **Security / Two-factor authentication** after signing in. Setup presents a QR code and a standard Base32 key for any TOTP-compatible authenticator app. The QR code is generated by the application and is not sent to an external QR service. The user must verify a current 6-digit code before 2FA is enabled. Ten one-time recovery codes are shown once; the user can replace them after proving possession of an authenticator/recovery code. Disabling 2FA requires a current factor and, for accounts with a local password, the password as well.

When 2FA is enabled, both password and Google sign-in require a second verification step before the normal `AUTH_TOKEN` session is issued. Users without 2FA keep the existing login flow. Five failed codes invalidate a login challenge; repeated failures also temporarily lock further challenges for that account.

Before deploying:

1. Apply `database/migrations/V2__create_user_two_factor_auth.sql` to the same PostgreSQL database after V1.
2. Generate a random 32-byte AES key, Base64-encode it, and store it in `app_settings` under `TOTP_ENCRYPTION_KEY` using `EncryptedSettingWriter`. The key encrypts each user's authenticator secret at rest; it must remain stable and be backed up securely. Losing or changing it prevents affected users from generating valid codes until 2FA is reset. `GLOBAL_ENCRYPTION_KEY` must be set in the environment to decrypt this setting.

   On PowerShell, generate a key with:

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   ```

3. Deploy over HTTPS. The authenticator secret is displayed only during setup. Users should store their recovery codes securely; each can be used once.

The migration is required before deploying this application version. Existing accounts remain unaffected until each user enables 2FA.

Users who lose both their authenticator and recovery codes can start **Lost authenticator and recovery codes?** from the login or two-factor verification page. They verify using the existing password-reset OTP channels and rate limits, then enroll a replacement authenticator and receive a new set of recovery codes. This flow reuses `password_reset_requests` with a `purpose` discriminator; it does not create another request table. Apply `database/migrations/V3__add_two_factor_recovery_purpose.sql` after V2 and `database/migrations/V10__allow_open_recovery_requests_by_purpose.sql` before deploying this recovery flow. V10 allows one open password-reset request and one open authenticator-recovery request per user, without one cancelling the other. Existing rows remain password-reset requests. V3 also conditionally renames the reset-token columns to purpose-neutral action-token names.
