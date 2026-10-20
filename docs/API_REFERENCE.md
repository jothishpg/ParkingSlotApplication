# Multi Parking System API Reference

## Scope and conventions

The JAX-RS API is mounted at `/api/*` (see `src/main/webapp/WEB-INF/web.xml`).
Therefore, the full path for a resource route is `/api` followed by the path below.
For example, `POST /auth/login` is `POST /api/auth/login`.

`Form` means `application/x-www-form-urlencoded`; `Query` values are URL query
parameters; `Path` values are part of the URL. Unless stated otherwise, the
administration and booking pages return HTML, not JSON. The payment status,
verification, event, and webhook routes explicitly marked JSON return JSON.
Browser cookies are set or read by the application and are not ordinary form
parameters. Routes requiring a role use the authenticated `AUTH_TOKEN` session.

## Login, signup, and session

| Method and route | Parameters | Access and use |
|---|---|---|
| `POST /auth/login` | Form: `email`, `password` | Public. Checks local credentials. Starts two-factor verification when enabled; otherwise signs in and redirects according to the user's role. |
| `POST /auth/signup` | Form: `name`, `email`, `phoneNumber`, `password`, `confirmPassword` | Public. Creates a local `USER` account; phone number must be 10 digits and the passwords must match. |
| `GET /auth/google/start` | Query: optional `mode` (`login` or `signup`; anything other than `signup` is treated as login) | Public. Starts Google OpenID Connect sign-in/signup and redirects to Google. |
| `GET /auth/google/callback` | Query: `code`, `state`; optional `error` | Public; Google redirect. Validates state, exchanges the authorization code, verifies the Google ID token and signs in or starts signup. |
| `POST /auth/google/complete` | Form: `phoneNumber`; Cookie: `GOOGLE_SIGNUP_PENDING` | Public, requires an unexpired pending Google signup cookie. Completes Google signup after collecting a 10-digit phone number. |
| `POST /auth/logout` | — | Public. Clears the authentication and pending-two-factor cookies, then redirects to the login page. |
| `GET /auth/2fa` | Cookie: `AUTH_2FA_PENDING` | Public, requires a valid pending two-factor cookie. Displays the authenticator/recovery-code form. |
| `POST /auth/2fa/verify` | Form: `code`; Cookie: `AUTH_2FA_PENDING` | Public, requires a valid pending two-factor cookie. Checks the one-time code and finishes login. |
| `GET /auth/me` | Cookie: `AUTH_TOKEN` | Public route that reports whether the cookie is valid. Returns JSON with `success` and, when signed in, `user.userId`, `user.name`, `user.email`, and `user.role`. |

Google signup additionally uses the temporary, HttpOnly `GOOGLE_SIGNUP_PENDING`
cookie; the server does not accept a user ID or Google identity from the form.

## User profile

Profile routes require `ADMIN` or `TICKET_ASSIGNER` and use the authenticated
user ID from the session; they do not accept a user ID from the browser.
Apply `database/migrations/V21__create_user_profile_change.sql` before using
profile edits. Each changed name, email, or phone number is written to
`user_profile_change` in the same transaction as the profile update. Password
values are never written to the change log.

| Method and route | Parameters | Access and use |
|---|---|---|
| `GET /profile` | — | Shows the current user's name, email, and phone number, with separate forms to update each value. |
| `POST /profile/name` | Form: `value` | Updates the signed-in user's name and records the old and new values. |
| `POST /profile/email` | Form: `value` | Updates the signed-in user's email if it is not already in use and records the old and new values. |
| `POST /profile/phone` | Form: `value` (10 digits) | Updates the signed-in user's phone number and records the old and new values. |
| `GET /profile/password` | — | Shows the local-password change form. |
| `POST /profile/password` | Form: `currentPassword`, `newPassword`, `confirmPassword` | Verifies the current password before replacing the bcrypt hash. Accounts without a local password cannot use this form. |

## Password reset and authenticator recovery

These public routes use form fields unless stated otherwise. Verification codes
are delivered by the selected channel; unknown/ineligible accounts get a
success-shaped response to avoid exposing account existence. The configured
limits include a 60-second resend cooldown and at most three OTP sends per day.

| Method and route | Parameters | Use |
|---|---|---|
| `POST /forgot-password/request` | Form: `email`, `channel` (`EMAIL` or `SMS`) | Starts password-reset verification. `EMAIL` sends through the connected Gmail account. The code's `SMS` branch currently sends a WhatsApp template to the saved phone number. |
| `GET /forgot-password/authenticator` | — | Shows the public authenticator-recovery request form. |
| `POST /forgot-password/authenticator/request` | Form: `email`, `channel` (`EMAIL` or `SMS`) | Starts recovery to replace a lost authenticator and recovery codes; only eligible accounts are acted on. The `SMS` branch also delivers through WhatsApp. |
| `POST /forgot-password/resend` | Form: `requestId` (UUID) | Resends the password-reset OTP when the request is valid and within rate limits. |
| `POST /forgot-password/authenticator/resend` | Form: `requestId` (UUID) | Resends the authenticator-recovery OTP under the same request/rate limits. |
| `POST /forgot-password/verify` | Form: `requestId` (UUID), `otp` (six digits) | Verifies a password-reset code and returns the short-lived password-reset form/token. |
| `POST /forgot-password/authenticator/verify` | Form: `requestId` (UUID), `otp` (six digits) | Verifies recovery and returns a short-lived authenticator replacement action token. |
| `POST /forgot-password/reset` | Form: `resetToken`, `newPassword`, `confirmPassword` | Changes the local password after a valid OTP; rejects a password matching the current password and sends a change notice. |
| `POST /forgot-password/authenticator/setup` | Form: `actionToken` | Begins enrollment of a replacement authenticator after recovery verification; displays the QR code/setup key. |
| `POST /forgot-password/authenticator/confirm` | Form: `actionToken`, `code` | Confirms the replacement TOTP authenticator, replaces recovery codes, and completes recovery. |

OTP lifetime is five minutes for the `SMS` channel and ten minutes for email.
Reset/action tokens expire after ten minutes. `requestId` and action tokens are
opaque values returned in the HTML forms, not user/account identifiers.

## Signed-in two-factor authentication settings

All routes below require a valid `AUTH_TOKEN` cookie. They return HTML.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /auth/totp` | Cookie: `AUTH_TOKEN` | Shows current 2FA status and available actions. |
| `POST /auth/totp/setup` | Cookie: `AUTH_TOKEN` | Starts TOTP enrollment and displays a QR code and manual secret. |
| `POST /auth/totp/enable` | Form: `code`; Cookie: `AUTH_TOKEN` | Confirms enrollment with the current authenticator code and displays one-time recovery codes. |
| `POST /auth/totp/recovery-codes` | Form: `code`; Cookie: `AUTH_TOKEN` | Verifies a current authenticator/recovery code and rotates recovery codes. |
| `POST /auth/totp/disable` | Form: `code`; Cookie: `AUTH_TOKEN` | Verifies a current authenticator/recovery code, then disables 2FA. |

## Role-specific page routes

| Method and route | Parameters | Access and use |
|---|---|---|
| `GET /pages/admin` | — | `ADMIN`; serves the admin dashboard HTML. |
| `GET /pages/user` | — | `USER`; serves the user page HTML. |
| `GET /pages/ticket-assigner` | — | `TICKET_ASSIGNER`; serves the ticket-assigner page HTML. |

The page files substitute `{{BASE_URL}}` with the configured application base
URL. These routes do not take query/form data.

## Admin dashboard and parking configuration

All routes in this section require `ADMIN` and return HTML. Form submissions
are URL-encoded. List and form-display GETs have no input unless shown.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin` | — | Admin dashboard links to management and setup features. |
| `GET /admin/floors` | — | Lists floors, vehicle types, status, and active floor-block state. |
| `GET /admin/floors/create` | — | Displays the floor-creation form. |
| `POST /admin/floors/create` | Form: `floorName`, `vehicleTypeId` | Creates an active floor and records the creating admin. |
| `GET /admin/floors/{floorId}/edit` | Path: `floorId` | Loads an existing floor into the edit form. |
| `POST /admin/floors/{floorId}/edit` | Path: `floorId`; Form: `floorName`, `vehicleTypeId` | Updates the floor name and vehicle type. |
| `GET /admin/slots` | — | Lists slots and their effective state, including blocks inherited from their floor. |
| `GET /admin/slots/create` | — | Displays the slot-creation form. |
| `POST /admin/slots/create` | Form: `slotName`, `floorId` | Creates an `AVAILABLE` slot on the selected floor. |
| `GET /admin/slots/{slotId}/edit` | Path: `slotId` | Loads a slot into the edit form. |
| `POST /admin/slots/{slotId}/edit` | Path: `slotId`; Form: `slotName` | Updates the slot name. Its floor is not changed by this route. |
| `GET /admin/users` | — | Lists users and their roles. |
| `GET /admin/users/{userId}/edit` | Path: `userId` | Loads the user's role editor. |
| `POST /admin/users/{userId}/edit` | Path: `userId`; Form: `roleId` | Assigns one of the supported roles: `ADMIN`, `USER`, or `TICKET_ASSIGNER`. |
| `GET /admin/vehicle-types` | — | Lists vehicle types. |
| `GET /admin/vehicle-types/create` | — | Displays the vehicle-type creation form. |
| `POST /admin/vehicle-types/create` | Form: `typeName` | Creates a vehicle type. |
| `GET /admin/vehicle-types/{vehicleTypeId}/edit` | Path: `vehicleTypeId` | Loads a vehicle type into the edit form. |
| `POST /admin/vehicle-types/{vehicleTypeId}/edit` | Path: `vehicleTypeId`; Form: `typeName` | Updates a vehicle type name. |
| `GET /admin/vehicle-rates` | — | Lists rate tiers by vehicle type and duration limit. |
| `GET /admin/vehicle-rates/create` | — | Displays the rate-tier creation form. |
| `POST /admin/vehicle-rates/create` | Form: `vehicleTypeId`, `durationLimit` (minutes), `ratePerMinute` | Adds a price tier used to calculate checkout totals. |
| `GET /admin/vehicle-rates/{rateId}/edit` | Path: `rateId` | Loads a rate tier into the edit form. |
| `POST /admin/vehicle-rates/{rateId}/edit` | Path: `rateId`; Form: `vehicleTypeId`, `durationLimit`, `ratePerMinute` | Updates a rate tier. |
| `GET /admin/floor-blocks/create` | — | Displays the floor-block form. |
| `POST /admin/floor-blocks/create` | Form: `floorId`, `reason` | Starts a floor block immediately; rejects an already-active block. |
| `GET /admin/floor-blocks/current` | — | Lists active floor blocks and provides unblock actions. |
| `GET /admin/floor-blocks/history` | — | Lists completed floor blocks. |
| `POST /admin/floor-blocks/{blockId}/unblock` | Path: `blockId` | Ends an active floor block at the current time. |
| `GET /admin/slot-blocks/create` | — | Displays the slot-block form. |
| `POST /admin/slot-blocks/create` | Form: `slotId`, `reason` | Starts a slot block immediately; rejects an already-blocked slot or a slot on a currently blocked floor. |
| `GET /admin/slot-blocks/current` | — | Lists active slot blocks and provides unblock actions. |
| `GET /admin/slot-blocks/history` | — | Lists completed slot blocks. |
| `POST /admin/slot-blocks/{blockId}/unblock` | Path: `blockId` | Ends an active slot block at the current time. |

## Booking, checkout, and booking reports

Booking routes require `ADMIN` or `TICKET_ASSIGNER` unless a stricter
`TICKET_ASSIGNER` requirement is specified. HTML responses are used.

| Method and route | Parameters | Access and use |
|---|---|---|
| `GET /booking` | — | Booking dashboard. Admin sees reporting/management links; ticket assigners also see booking and checkout links. |
| `GET /booking/create` | — | `TICKET_ASSIGNER`; displays vehicle booking form. |
| `POST /booking/create` | Form: `vehicleTypeId`, `vehicleNumber`, `phoneNumber`, `color` | `TICKET_ASSIGNER`; validates the vehicle number, finds an available matching slot, starts booking, stores vehicle details, and queues WhatsApp confirmation. Vehicle format must be `AB01A1234` or `AB01CD1234`; invalid input is rejected with a correction message. |
| `GET /booking/checkout` | Query: optional `vehicleNumber` | `TICKET_ASSIGNER`; with no number shows search form; with a number loads active booking, elapsed minutes, and amount. |
| `POST /booking/checkout/{bookingId}/pay` | Path: `bookingId`; Form: `paymentMethod` (`CASH` or `ONLINE`) | `TICKET_ASSIGNER`; cash closes booking and records payment; online starts a Razorpay payment request and redirects to its checkout page. |
| `GET /booking/current` | — | Lists active bookings. |
| `GET /booking/bookings` | — | Lists all bookings and active/completed status. |
| `GET /booking/duration` | — | Lists start/end times, duration, and amount. |
| `GET /booking/payment-history` | — | Lists payment amount, gateway/method, status, and verifier. |

`vehicleNumber` is normalized by removing spaces/hyphens and uppercasing before
format validation and storage. Duration pricing uses the configured vehicle
rate tiers.

## Facebook / Meta and WhatsApp account setup

All `/admin/facebook-setup` routes require `ADMIN`; setup pages return HTML.
Meta OAuth state is validated against the `META_FACEBOOK_SETUP_STATE` cookie;
the temporary encrypted OAuth flow is kept in `META_FACEBOOK_SETUP_TEMP`.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin/facebook-setup` | — | Shows Meta app/account setup status and links to templates when connected. |
| `POST /admin/facebook-setup/credentials` | Form: `appId`, `appSecret` | Validates the Meta App ID/secret against Graph API and stores the secret encrypted. |
| `GET /admin/facebook-setup/authorize` | — | Starts Meta OAuth and redirects to Facebook/Meta. |
| `GET /admin/facebook-setup/callback` | Query: `code`, `state`; optional `error`, `error_description`; Cookie: `META_FACEBOOK_SETUP_STATE` | Validates OAuth response, exchanges code, and displays available Business Portfolios. |
| `POST /admin/facebook-setup/business` | Form: `businessId`; Cookie: `META_FACEBOOK_SETUP_TEMP` | Selects a Business Portfolio and displays its WhatsApp Business Accounts (WABAs). |
| `POST /admin/facebook-setup/complete` | Repeated Form values: `wabaId`; Cookie: `META_FACEBOOK_SETUP_TEMP` | Selects one or more WABAs, checks phone numbers, provisions Meta system-user access/tokens, and saves setup. |

### WhatsApp template management (same admin role)

Base route: `/admin/facebook-setup/templates`.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin/facebook-setup/templates` | Query: optional `message` | Lists Meta templates, previews components, and shows notification-template assignments. |
| `POST /admin/facebook-setup/templates/assign` | Form: `messageKey`, `templateId` (blank removes assignment) | Assigns or removes an approved compatible WhatsApp template for an application message. |
| `GET /admin/facebook-setup/templates/new` | — | Displays the new-template editor with default component JSON. |
| `POST /admin/facebook-setup/templates/create` | Form: `name`, `language`, optional `parameterFormat`, `components` (JSON) | Submits a Utility-category WhatsApp template to Meta for review; category is fixed by the server. Parameter format: `POSITIONAL` or `NAMED`. |
| `GET /admin/facebook-setup/templates/edit/{templateId}` | Path: `templateId` | Loads template details/components for editing. |
| `POST /admin/facebook-setup/templates/edit` | Form: `templateId`, `category`, `parameterFormat`, optional `allowCategoryChange` (`true`), `components` (JSON) | Submits supported changes to an approved/rejected template for Meta review. |
| `POST /admin/facebook-setup/templates/delete` | Form: `templateId`, `name` | Deletes that template language from Meta, unless currently assigned. |

Assignment eligibility is checked against approval status and required body
variable counts, and disallows header/button variables. OTP template selection
is currently disabled in the assignment UI.

## Gmail sender and email setup

All `/admin/email-setup` routes require `ADMIN`. Gmail OAuth state is bound to
the `GMAIL_EMAIL_SETUP_STATE` cookie.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin/email-setup` | — | Shows Google OAuth client, connected sender, and Reply-To configuration. |
| `GET /admin/email-setup/authorize` | — | Starts Gmail authorization and redirects to Google. |
| `POST /admin/email-setup/google-client` | Form: `clientId`, `clientSecret` | Saves the Google OAuth web-client credentials encrypted for Google sign-in and Gmail sender authorization. |
| `GET /admin/email-setup/callback` | Query: `code`, `state`; optional `error`; Cookie: `GMAIL_EMAIL_SETUP_STATE` | Exchanges authorization code, verifies Gmail permissions/sender, and saves sender tokens encrypted. |
| `POST /admin/email-setup/disconnect` | — | Removes the system Gmail sender configuration. |
| `POST /admin/email-setup/reply-to` | Form: optional/blank `replyToEmail` | Saves or clears the Reply-To address. |

The Gmail sender is global to the application. It is used for password-reset
emails and (when the listener is running) automatic mailbox replies.

## Admin booking alerts

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin/alerts` | — | Opens the alert page after a fresh password check for the signed-in administrator; verification expires after 10 minutes. |
| `POST /admin/alerts/verify` | Form: `password` | Re-verifies the signed-in administrator before granting temporary access to the alert form. |
| `POST /admin/alerts/send` | Form: `target` (`ALL`, `FLOOR`, or `SLOT`), `floorName`, `slotName`, `message` | Sends the message to each distinct phone number with an active booking in the selected scope, using the assigned booking confirmation WhatsApp template. |

## Zoho Cliq OAuth setup

All `/admin/zoho-setup` routes require `ADMIN`. OAuth uses the Zoho India
accounts server. OAuth state is bound to the
`ZOHO_CLIQ_SETUP_STATE` cookie. The Zoho client secret and access/refresh
tokens are encrypted at rest.

| Method and route | Parameters | Use |
|---|---|---|
| `GET /admin/zoho-setup` | — | Shows the callback URL, client configuration, and connection status. |
| `POST /admin/zoho-setup/client` | Form: `clientId`, `clientSecret` | Saves the Zoho OAuth client configuration; changing it removes the existing connection. |
| `GET /admin/zoho-setup/authorize` | — | Starts Zoho India OAuth with `ZohoCliq.Bots.CREATE`, `ZohoCliq.Bots.READ`, `ZohoCliq.Bots.UPDATE`, and `ZohoCliq.Bots.DELETE`. |
| `GET /admin/zoho-setup/callback` | Query: `code`, `state`; optional `error`; Cookie: `ZOHO_CLIQ_SETUP_STATE` | Exchanges the code, verifies the bot read, create, update, and delete scopes, and saves encrypted tokens and granted scopes. |
| `POST /admin/zoho-setup/bot` | Form: `name`, `description` | Creates an organization-scoped webhook bot with the Cliq v3 Bots API and adds its `message_handler`; its execution URL is the app's `/api/cliq/webhook`. |
| `POST /admin/zoho-setup/bot/handler` | — | Retries creation of the existing bot's message handler without creating another bot. |
| `POST /admin/zoho-setup/bot/details` | — | Fetches `GET /api/v3/bots/{BOT_ID}` with the saved Zoho token and renders Zoho's JSON response in the setup page. |
| `POST /admin/zoho-setup/bot/delete` | — | Deletes the configured bot and its handlers using `DELETE /api/v3/bots/{BOT_ID}`, then removes the local bot configuration only after Zoho confirms success. |
| `POST /admin/zoho-setup/disconnect` | — | Removes the Zoho authorization grant while retaining the OAuth client configuration. |
| `POST /cliq/webhook` | JSON Cliq message event containing `params.message` and `response_url` | Public Zoho callback; parses booking ID and vehicle number, validates both against a booking, and posts a booking summary or format prompt to the validated response URL. |

Set `ZOHO_REDIRECT_URI` to the exact public callback URL registered in the Zoho
API Console. If it is omitted, `APP_BASE_URL` is used to construct that URL.
`APP_BASE_URL` also determines the public webhook URL. The OAuth client
configuration, connection, and locally configured bot are each limited to one
row per database. Bot creation requires a name (maximum 20 characters) and
description (maximum 300 characters); the application always sets its scope to
`organization`. Bot creation uses
`POST /api/v3/bots`; the handler uses `POST /api/v3/bots/{BOT_ID}/handlers`
with message, user, and chat permissions. The handler forwards inbound data to
the existing webhook. Send `BookingID:19, Vehicle_Number:TN28MA1829` to look up a
booking; labels are case-insensitive, spaces around fields are ignored, and
vehicle-number matching ignores case, whitespace, and hyphens. Extra text after
the vehicle number is ignored. Ticket assigners must enter vehicle numbers in
the format `AB01A1234` or `AB01CD1234`; spaces and hyphens are ignored during
normalization. Cliq performs no vehicle-number format validation. Active
bookings show the fee accrued using the configured rate tiers up to the current
time; completed bookings show their stored final fee. The response is posted to
the event's `response_url` so it appears in the same conversation. Sending
`Fee details` or asking about fees, rates, or charges returns the configured
per-minute tiers for each vehicle type from `vehicle_rate`. See Zoho's official
[bot creation](https://www.zoho.com/cliq/help/restapi/v3/bots/#create-a-bot)
and [handler creation](https://www.zoho.com/cliq/help/restapi/v3/bots/#create-a-handler-for-a-specific-bot)
documentation.
The bot-details action requires the `ZohoCliq.Bots.READ` OAuth scope and uses
the locally stored Zoho bot ID to request its details. Bot deletion requires
`ZohoCliq.Bots.DELETE`; reconnect Zoho to grant the additional scope to an
existing connection. A failed remote delete preserves the local bot record.

## Razorpay administration and reconciliation

| Method and route | Parameters | Access and use |
|---|---|---|
| `GET /admin/razorpay-setup` | — | `ADMIN`; shows key connection mode and the webhook URL to configure in Razorpay. |
| `POST /admin/razorpay-setup/keys` | Form: `keyId`, `keySecret` | `ADMIN`; tests the keys against Razorpay, then stores them encrypted. |
| `GET /admin/payment-reconciliation` | — | `ADMIN`; lists the latest 100 payment requests, statuses and any attempts needing review. |
| `POST /admin/payment-reconciliation/{id}` | Path: `id` (payment request UUID) | `ADMIN`; queries/reconciles with Razorpay and may recover a verified captured payment. |

## Razorpay checkout, customer payment link, and webhook routes

The customer payment-link routes are public but require the unguessable `token`
query value except for the provider callback. Staff checkout routes are
role-protected. The status/verification/event routes return JSON.

| Method and route | Parameters | Access and use |
|---|---|---|
| `GET /payments/razorpay/checkout/{id}` | Path: `id` (payment request UUID) | `TICKET_ASSIGNER`; shows staff-side checkout/payment status for a request. |
| `GET /payments/razorpay/customer/{id}` | Path: `id`; Query: `token` | Public; authorizes a customer payment URL and renders the Razorpay payment page. |
| `GET /payments/razorpay/payment-link/{id}/callback` | Path: `id`; Query: `razorpay_payment_link_id`, `razorpay_payment_link_reference_id`, `razorpay_payment_link_status`, `razorpay_payment_id`, `razorpay_signature` | Public Razorpay redirect. Verifies signature and reconciles with Razorpay; the redirect alone does not mark payment complete. |
| `GET /payments/razorpay/checkout/{id}/status` | Path: `id` | `TICKET_ASSIGNER` or `ADMIN`; reconciles/checks payment status; JSON `{ "status": "..." }`. |
| `POST /payments/razorpay/checkout/{id}/event` | Path: `id`; Form: `event` | `TICKET_ASSIGNER`; records a browser checkout event such as close/client error; JSON `{ "received": true }`. |
| `GET /payments/razorpay/customer/{id}/status` | Path: `id`; Query: `token` | Public with valid token; checks customer payment status; JSON response. |
| `POST /payments/razorpay/customer/{id}/event` | Path: `id`; Query: `token`; Form: `event` | Public with valid token; records browser checkout event; JSON response. |
| `POST /payments/razorpay/webhook/{configId}` | Path: `configId`; JSON body (provider event); Headers: `X-Razorpay-Signature`, optional `X-Razorpay-Event-Id` | Public provider webhook. Verifies the raw-body HMAC and handles captured/failed/order-paid and payment-link events. Body limit is 256 KiB. |

## Outbound provider APIs used by the project

These are server-to-server or browser-to-provider calls made by the application;
they are not additional `/api/...` routes on this server.

### Google sign-in and Gmail

Google sign-in is a separate use of the OAuth client configured in Email Setup.
It redirects to `https://accounts.google.com/o/oauth2/v2/auth` with
`client_id`, `redirect_uri`, `response_type=code`, `scope=openid email profile`,
signed `state`, and `prompt=select_account`. Its callback exchanges `code` at
Google's OAuth token endpoint and verifies the returned ID token (including
audience). Login/signup mode is carried inside signed state, not accepted as an
identity claim.

The callback exchanges the OAuth code with Google's token endpoint using the
configured client ID, secret and redirect URI, then verifies the ID token
against the same client ID.

Gmail sender authorization uses the same Google authorization endpoint with
`scope=openid email https://www.googleapis.com/auth/gmail.send
https://www.googleapis.com/auth/gmail.metadata`, `access_type=offline`,
`prompt=consent select_account`, `include_granted_scopes=true`, and signed
`state`.

| Outbound request | Parameters/body | Use |
|---|---|---|
| `POST https://oauth2.googleapis.com/token` (authorization-code exchange) | Form: `code`, `client_id`, `client_secret`, `redirect_uri`, `grant_type=authorization_code` | Exchanges admin's Gmail authorization and obtains access/refresh tokens. |
| `POST https://oauth2.googleapis.com/token` (refresh) | Form: `client_id`, `client_secret`, `refresh_token`, `grant_type=refresh_token` | Refreshes the stored Gmail access token. |
| `GET https://openidconnect.googleapis.com/v1/userinfo` | Bearer access-token header | Checks sender email and that Google verified it. |
| `POST https://gmail.googleapis.com/gmail/v1/users/me/messages/send` | Bearer access-token header; JSON: `raw` containing a base64url RFC 822 message | Sends a password-reset email, test email, or automatic reply. |
| `GET https://gmail.googleapis.com/gmail/v1/users/me/{path}` | Bearer access-token header; path is selected by the Gmail auto-reply listener | Reads mailbox metadata for the automatic-reply feature. |

For automatic mailbox replies, Gmail metadata requests are made to
`GET /gmail/v1/users/me/messages?labelIds=INBOX&maxResults=50` (with optional
`pageToken`), then to
`GET /gmail/v1/users/me/messages/{messageId}?format=metadata`. Gmail calls use
an OAuth Authorization header.

### Meta Graph API and WhatsApp Cloud API

OAuth redirects to `https://www.facebook.com/{version}/dialog/oauth` with
`client_id`, `redirect_uri`, `response_type=code`, the scopes
`business_management`, `whatsapp_business_management`,
`whatsapp_business_messaging`, `whatsapp_business_manage_events`, and signed
`state`. Graph version is configured in the application as `v23.0`. Graph
requests use an OAuth Bearer token unless noted.

| Graph request | Parameters/body | Use |
|---|---|---|
| `GET /{appId}` | Query: `fields=id,name`; app access token made from app ID and secret | Validates saved Meta App credentials. |
| `GET /oauth/access_token` | Query: `client_id`, `client_secret`, `redirect_uri`, `code` | Exchanges Meta setup authorization code for a user token. |
| `GET /debug_token` | Query: `input_token`; app access token | Checks token validity, app ownership and granted scopes. |
| `GET /me/businesses` | Query: `fields=id,name` | Lists Business Portfolios available to the signed-in Meta user. |
| `GET /{businessId}/owned_whatsapp_business_accounts` and `GET /{businessId}/client_whatsapp_business_accounts` | Query: `fields=id,name` | Finds owned and client WABAs for the selected portfolio. |
| `GET /{wabaId}/phone_numbers` | Query: `fields=id,display_phone_number,verified_name` | Lists phone-number assets attached to a selected WABA. |
| `GET /{businessId}/system_users` | Query: `fields=id,name,role` | Finds existing Meta business system users. |
| `POST /{businessId}/system_users` | Form body: `name=Parking System Admin`, `role=ADMIN` | Creates the required admin system user if absent. |
| `GET /{wabaId}/assigned_users` | Query: `business`, `fields=id,tasks` | Checks which system users have `MANAGE` or `DEVELOP` access to each WABA. |
| `POST /{systemUserId}/access_tokens` | Form body: `business_app`, `scope`, `appsecret_proof` | Generates and validates admin/employee system-user tokens with the required permissions. |
| `GET /{wabaId}/message_templates` | Query: fields for id, name, category, language, status, components, quality/rejection metadata, parameter format and update metadata; `limit=100` (Graph pagination is followed) | Lists templates and status/details for the connected WABA. |
| `POST /{wabaId}/message_templates` | JSON: `name`, `category`, `language`, `components`; optional `parameter_format` | Creates a template for Meta review. |
| `POST /{templateId}` | JSON: `components`; optional `parameter_format`, `category`, `allow_category_change` | Updates an eligible template. |
| `DELETE /{wabaId}/message_templates` | Query: `name`, `hsm_id` | Deletes a specific template language after server-side assignment checks. |
| `POST /{appId}/uploads` | Query: `file_length`, `file_type`; Bearer token | Starts a resumable upload for an image header example. |
| `POST /{uploadId}` | Binary body; `file_offset=0`, `Content-Type=application/octet-stream`; Bearer token | Uploads the image bytes and returns the template media handle. |
| `POST /{phoneNumberId}/messages` | JSON: `messaging_product=whatsapp`, `recipient_type=individual`, `to`, `type=template`, `template` name/language and body parameters | Sends assigned booking, checkout, payment-status, or OTP template messages. |

The server chooses an active employee system-user token and phone number with
the required asset permissions. `to` is a saved 10-digit Indian phone number
normalized to `+91` plus those digits.
Meta Graph calls use an OAuth Authorization header, including media uploads.

### Razorpay

Requests use `https://api.razorpay.com/v1/` and HTTP Basic authentication from
the configured Key ID/secret.

| Razorpay request | Parameters/body | Use |
|---|---|---|
| `GET /payment_links?count=1` | Basic auth; `count=1` | Confirms the admin-supplied keys can access the current payment-link API. |
| `POST /payment_links` | JSON: `amount` (paise), `currency=INR`, `accept_partial=false`, `reference_id` (payment request UUID), `description`, `expire_by`, `notify` (`sms=true`, `email=false`), `reminder_enable=false`, optional `customer.contact`; callback URL and `callback_method=get` | Creates the customer payment link and freezes the booking amount until expiry. |
| `GET /payment_links?reference_id={id}` | `reference_id` is the request UUID | Recovers a link if the create request timed out before its response was stored. |
| `GET /payment_links/{id}` | Payment-link ID | Retrieves authoritative payment-link state and attached payments for reconciliation. |
| `GET /payments/{id}` | Payment ID from the link response | Retrieves payment status and details for reconciliation. |
| `GET /payments/{paymentId}` | Razorpay payment ID | Retrieves payment details before verification/reconciliation. |
| `POST /payment_links/{id}/notify_by/sms` | Empty JSON body; Basic auth | Requests Razorpay to send an SMS payment-link notification. |

The hosted checkout UI also loads
`https://checkout.razorpay.com/v1/checkout.js`. Payment-link callback values
are signed and verified by the inbound callback route; webhooks use the inbound
route and the encrypted `RAZORPAY_WEBHOOK_SECRET` value from `app_settings`.

### Notification dispatch

Payment-link creation requests Razorpay SMS notification and queues WhatsApp
messages through the application's notification outbox. Payment status
notifications use the assigned WhatsApp templates. Password-reset email goes
through the Gmail send API. The password-reset channel named `SMS` is
implemented through the WhatsApp template sender in this code, not a separate
SMS gateway.
