# Customer payment links and notifications

Webhook signature verification uses the encrypted `RAZORPAY_WEBHOOK_SECRET` value in `app_settings`. The application creates this setting at startup when missing and displays it on the admin-only Razorpay Setup page. Configure the same secret in Razorpay's webhook settings; the application decrypts it using `GLOBAL_ENCRYPTION_KEY`.

Online checkout creates native Razorpay Payment Links with a 15-minute window plus a 30-second request-transit allowance. Razorpay validates its minimum expiry when the request arrives, so exactly 900 seconds from the application clock may be rejected. The returned provider deadline is saved and displayed; resends never extend it. Razorpay sends link SMS; the existing Meta integration sends WhatsApp links and status updates. No Twilio or additional SMS-provider credentials are used.

Apply database migrations V7__payment_customer_notifications.sql and V8__payment_link_reconciliation.sql, in order, after V5/V6. Skip migrations already applied. No database migration has been executed by this change. Existing requests retain their saved deadlines; new links use 15 minutes.

## Automatic delivery

New online checkouts automatically enable SMS links and WhatsApp links/status messages. There are no consent checkboxes. The customer phone snapshot and notification settings are stored with the request. For compatibility, the existing sms_consent and whatsapp_consent columns act as channel settings; new automatic requests do not record fictitious consent timestamps or consent administrators. Existing requests retain their original settings. Repeated Pay clicks reuse the existing request and link. This change needs no additional database migration.

The server freezes the charge/duration and creates a native link with partial payments disabled. The request UUID is the unique reference_id. Creation uses POST /v1/payment_links with Razorpay SMS notification enabled, so Razorpay sends its built-in SMS to the saved customer contact as the link is created. The returned provider deadline is saved. WhatsApp uses the same native short_url through the message worker. Provider-confirmed failed payment attempts can resend the still-valid link through POST /v1/payment_links/{id}/notify_by/sms. Treat payment links as confidential. Older application-hosted links remain supported for existing requests.

Razorpay's built-in notification sends payment-link SMS; it is not a general sender for our custom success/failure/error text. Those updates use WhatsApp. The outbox does not send a second initial SMS after creation. Provider account/test-mode capabilities govern actual SMS delivery. Razorpay's documentation currently limits test mode to 30 native links per business.

## Payment status and recovery

Only a matching captured payment completes checkout and releases the slot. Signed native link events bind the link and reference to the saved request. Reconciliation fetches the saved or recovered payment link and verifies its attached payment IDs through Razorpay. The native callback verifies link_id|reference_id|status|payment_id with the merchant signature secret and then checks captured payments through Razorpay. Clicks, redirects and message delivery alone never count as success.

WhatsApp messages cover the link, success, confirmed failure, payment review, expiry and service errors. Provider-confirmed network/timeout or server failures receive corresponding text. When a network/server error leaves payment uncertain, the message says it is unconfirmed and asks the customer not to pay again until checked. Closing or losing connectivity on Razorpay's external hosted page is not directly observable; webhooks and reconciliation establish status afterward.

Each provider-confirmed `payment.failed` event is associated with its saved payment-link request using the Razorpay order/link identifiers, then recorded in `payment_attempt`. It queues a resend of the same still-valid link by SMS and a failure explanation by WhatsApp, subject to the request's enabled channels. The WhatsApp error message explains the resend and unchanged expiry. Repeated events for the same payment do not create duplicate queue entries. Resends stop after payment, review or expiry. If a failure arrives after the link deadline while the request is still pending reconciliation, the WhatsApp message asks staff for a fresh link instead. Uncertain browser/network/server errors never trigger an automatic link resend.

Pending requests are reconciled about every 30 seconds, subject to backlog and provider latency. Creation timeouts are recovered by native reference_id rather than blindly creating another link. Definitive HTTP 400/401/403/422 creation rejections close that unpaid request so it does not block cash/retry. Ambiguous timeouts and server errors stay unresolved until reconciliation. An application/database outage cannot send a message while the service is down; processing resumes after recovery.

The message worker claims rows with a lease and contacts providers outside database transactions. It retries failed submissions up to five times and skips stale link/pending messages after success, review or expiry. SUBMITTED means provider acceptance, not delivery to a handset. A crash after provider acceptance but before recording it can cause a duplicate message on retry; exactly-once delivery is not guaranteed. Inspect payment_message_outbox for failed submissions. Cash, existing booking notifications and password reset remain on their existing flows.

## WhatsApp template

Payment links and unsuccessful/pending status messages use the existing BOOKING_CONFIRMATION assignment and its five-variable structure, with payment information appended to the final start-time value. Successful payments use CHECKOUT_CONFIRMATION with vehicle/name, slot, committed duration and committed amount, matching the existing checkout notification. Ensure both assignments point to approved, compatible Meta templates. Cash and ordinary booking notifications retain their existing paths.

## Payment-link fields and provider receipts

API-created links use the calculated amount in integer paise, INR, a parking-booking description, the saved customer phone and a unique request UUID reference. Email is omitted and email notification is disabled. Reminders and partial payments are disabled; optional notes are omitted. Razorpay SMS is enabled on link creation; WhatsApp delivery is queued after the link is saved.

Razorpay receipts are separate from the application's checkout notification. No undocumented receipt option is sent to the standard Payment Links API. A receipt setting enabled while creating a dashboard link must not be assumed to apply to all API-created links; confirm its merchant-wide scope and test a receipt for an API-created link. Without an email address, email receipt delivery is unavailable; provider WhatsApp receipts depend on the merchant's enabled receipt feature. The application still sends its own checkout WhatsApp confirmation after verified successful payment.

## Webhooks

Keep the existing webhook URL and `RAZORPAY_WEBHOOK_SECRET` database setting. Subscribe to `payment.failed`, `payment_link.paid`, `payment_link.expired` and `payment_link.cancelled`. Partial payments are disabled for application-created links. Callbacks use public HTTPS `APP_BASE_URL`.

Inspect queue state without exposing customer links:

```sql
SELECT message_id, request_id, event_key, channel, status, attempts,
       created_at, submitted_at, last_error
FROM payment_message_outbox
ORDER BY created_at DESC;
```

Tests cover native link/reference matching, callback payload order, 15-minute boundaries, customer capabilities for older links, network/server-failure classification and the booking template's five values. Real database, SMS and WhatsApp delivery require configured credentials and deployment tests.
