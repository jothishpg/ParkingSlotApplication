# Razorpay merchant payments

## Business isolation

This implementation follows the application's existing model: one business and one merchant configuration per PostgreSQL database. Any ADMIN in that business can connect the merchant. It must not be used with multiple businesses sharing these tables without tenant-scoped authentication, configuration, booking access and webhook routing. Merchant funds go to the connected Razorpay merchant account.

## Database query

Apply database migrations V5 through V8 in order and apply V20 before deployment, skipping versions already applied. V7 stores notification settings and the message queue; V8 enables native-link background reconciliation; V20 creates `app_settings`. Migrations are not executed automatically and have not been applied by this change. At startup, the application creates any missing secret settings; existing settings are not overwritten. See [payment messaging](payment-messaging.md) for SMS, WhatsApp, expiry and retry behavior.

If V5 was already applied, execute `database/migrations/V6__remove_razorpay_webhook_secrets.sql` before deploying the updated application. It removes stored webhook secrets from both `razorpay_config` and `razorpay_oauth_state`. Updated V5 does not create those columns; V6 is also safe after updated V5. Preserve any existing `RAZORPAY_WEBHOOK_SECRET` by storing it in `app_settings` before startup and configure the same value on all existing Razorpay webhook subscriptions before switching over. Otherwise, startup generates a new value and existing webhook subscriptions must be updated to match. These SQL scripts are manual and have not been executed by this change.

Existing CASH rows remain unchanged. Online payments leave the legacy `payment_method` null and store the actual Razorpay method in `gateway_method`, avoiding assumptions about your existing payment-method enum. Payment history displays the gateway method where present. Amounts continue to use BigDecimal and are converted exactly to integer paise: INR 34.00 is 3400 paise. No rate rounding policy was changed.

## Server environment

| Variable | Value |
| --- | --- |
| `APP_BASE_URL` | Public HTTPS application URL including any context path, with no trailing slash. |
| `RAZORPAY_MODE` | `test` initially; `live` after account/app activation and testing. Defaults to `test`. |
| `RAZORPAY_CLIENT_ID` | Razorpay Partner application's development or production Client ID. Required for OAuth. |
| `RAZORPAY_CLIENT_SECRET` | Matching Partner Client Secret, stored only in server secrets. Required for OAuth. |
| `RAZORPAY_REDIRECT_URI` | `<APP_BASE_URL>/api/admin/razorpay-setup/callback`, exactly whitelisted in the Partner app. Required for OAuth. |

The application generates `RAZORPAY_TOKEN_ENCRYPTION_KEY` and `RAZORPAY_WEBHOOK_SECRET` at startup if they are missing. To preserve prior values, seed them in `app_settings` before deploying. Otherwise, after the first startup, copy the webhook signing secret displayed on the admin-only Razorpay Setup page into Razorpay's webhook configuration. Webhook verification will fail until the configured secrets match. To generate or replace the token-encryption key manually, use `EncryptedSettingWriter` and PowerShell:

```powershell
$paymentKeyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($paymentKeyBytes)
[Convert]::ToBase64String($paymentKeyBytes)
```

Use `GLOBAL_ENCRYPTION_KEY` in the environment to encrypt/decrypt values in `app_settings`. Keep it separate from the database settings and back it up. Use the development client and test mode while production OAuth approval is pending. Approval and merchant activation are controlled by Razorpay; a 4–8 business-hour estimate is not guaranteed. Production clients require HTTPS and support live mode only. Forms use Secure cookies, so use HTTPS for application testing as well.

## Connect a merchant

1. Sign in as ADMIN and open **Razorpay Setup** from the dashboard.
2. Copy the server-generated `RAZORPAY_WEBHOOK_SECRET` shown on the Razorpay Setup page into each relevant Razorpay webhook subscription. Vendors do not enter a webhook secret in the setup form. This is separate from the OAuth Client Secret/API Key Secret.
3. Click **Connect your Razorpay account**. The merchant authorizes `read_write` access. OAuth state is single-use, expires in ten minutes, and is bound to the initiating admin and browser cookie.
4. The server stores encrypted access/refresh tokens and checkout signature secrets. Webhook signing uses only `RAZORPAY_WEBHOOK_SECRET`. Access tokens refresh on use near expiry, including refresh-token rotation. Only the public token is exposed to Checkout.
5. While Partner OAuth is unavailable, the optional merchant API-key form can connect that business's test keys. It checks the keys with Razorpay before saving. Test keys cannot collect real money.
6. Enable automatic payment capture in the merchant Razorpay settings. Authorised payments alone never complete parking checkout.

Changing connections creates a new configuration version. Existing requests keep the old credentials and webhook URL for reconciliation. All configuration versions use the current `RAZORPAY_WEBHOOK_SECRET` database setting; when rotating it, update all retained subscriptions as well. Keep old webhook subscriptions until their pending payments are resolved. API-key setup needs the encryption key setting, webhook secret setting, payment mode and application URL; Partner OAuth variables are unnecessary for that setup path.

## Webhook URL

The exact URL is displayed in Razorpay Setup after connecting:

```text
<APP_BASE_URL>/api/payments/razorpay/webhook/<config_id>
```

For example, configuration 1 on `https://parking.example.com` uses:

```text
https://parking.example.com/api/payments/razorpay/webhook/1
```

You can retrieve the configuration number without exposing secrets:

```sql
SELECT config_id, auth_type, mode, account_id
FROM razorpay_config
WHERE active = TRUE;
```

Subscribe to `payment_link.paid`, `payment_link.expired`, and `payment_link.cancelled`, using exactly the server's `RAZORPAY_WEBHOOK_SECRET` value. For OAuth also subscribe to `account.app.authorization_revoked` through the Partner application's webhook configuration. That event disables new payments for the matching merchant.

Each business endpoint processes its own database/configuration. A shared Partner webhook endpoint serving many business deployments needs a merchant-to-deployment router; this application does not contain such a router. Configure webhook delivery to the relevant business endpoint and test that merchant's payment events reach it before going live.

Webhook signatures are checked against the raw request bytes. Signed events can complete checkout without an active browser. Unique successful-payment references and event/message keys prevent repeated checkout or queue inserts. Automatic status messages are submitted through WhatsApp by the worker after commit; SUBMITTED means provider acceptance, not guaranteed delivery.

## Checkout and expiry rules

* Cash uses the existing immediate successful-checkout transaction.
* Online Pay calculates the charge on the server and freezes the charge/duration until the returned provider deadline. Repeated clicks reuse the open request.
* Razorpay displays the merchant's enabled payment methods on the assigner's device. The application never handles customer payment credentials.
* New payments use native Razorpay links with a 15-minute expiry plus a 30-second request-transit allowance to meet the provider minimum at receipt.
* A captured payment whose provider `created_at` falls inside the request window can complete later through a delayed callback/webhook. This preserves a legitimate payment started before expiry. Provider creation timestamps have second precision.
* A captured payment created outside that window, with mismatched details, refunded funds, or after another checkout is recorded as `REVIEW`. It does not independently complete checkout or free the slot. Refunds/review resolution require the administrator's investigation in Razorpay; automatic refunds are not implemented.
* Only a matching, captured, unrefunded payment can create the final `payment` and `duration_amount` records and release the slot. These changes commit together under a booking lock.
* Cash/new payments are blocked while an online request is CREATING, PENDING or REVIEW. Reconciling an expired unpaid link makes it EXPIRED so a new charge or cash collection can proceed. A network error during link creation is retained for admin recovery rather than retried blindly.
* **Payment Reconciliation**, background polling and signed webhooks check native links and their payments. Creation timeouts are recovered by link reference. If no matching entity is found, check Razorpay before manual resolution. The current reconciler does not recover legacy order-based requests; resolve any such outstanding request manually before collecting again.

Review captured attempts, including extra payments on already completed requests:

```sql
SELECT pr.booking_id, pr.request_id, pr.status, pa.gateway_payment_id,
       pa.amount_paise, pa.currency, pa.provider_method, pa.disposition
FROM payment_attempt pa
JOIN payment_request pr ON pr.request_id = pa.request_id
WHERE pa.disposition = 'REVIEW'
ORDER BY pa.updated_at DESC;
```

## Future message payments

Native Payment Links and automatic Razorpay link SMS/WhatsApp messaging are enabled. Link callbacks verify signatures and captured payments; link clicks and message delivery are not payment success. Email can be added to the same request model later.

Payment links and unsuccessful/pending statuses reuse the assigned booking template and append payment information to its fifth body value. Successful payment messages use the existing CHECKOUT_CONFIRMATION assignment with the saved checkout duration and amount. No new template or URL button is created. Email would additionally need a customer address.

## Validation before live use

`mvn test` covers an independent HMAC signature vector/tampering, payment status, native-link payment amount/currency matching, refunded payments, 15-minute boundaries, delayed events and payments after a completed booking. Database and real Razorpay tests require deployment credentials and were not run locally.

In test mode verify success, payment failure/cancellation, double clicks, duplicate webhooks, browser closure after payment, expiry/retry, cash after an unpaid request is reconciled, OAuth reconnect/revocation and WhatsApp confirmation. Check that unsuccessful payments keep the slot occupied and never create a successful checkout record. Move to live mode only after the database migration, merchant capture settings and actual webhook delivery are verified.

References: [Razorpay Partner OAuth](https://razorpay.com/docs/partners/technology-partners/onboard-businesses/integrate-oauth/integration-steps/), [application webhooks](https://razorpay.com/docs/partners/technology-partners/onboard-businesses/integrate-oauth/subscribe-to-webhooks/), [Standard Checkout](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/).
