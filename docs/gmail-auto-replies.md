# Application-managed Gmail no-reply messages

The servlet worker polls the connected Gmail INBOX every 30 seconds while the application is running. It scans one page of up to 50 messages per pass, continuing through the inbox and starting another scan afterward. Large inboxes and provider latency increase response time. It reads only Gmail metadata (headers, labels and received timestamp), never message bodies or attachments. Existing OTP sending and token refresh remain in place.

## Configuration

1. Apply `database/migrations/V20__create_app_settings.sql` and run the manual SQL below before deployment. This adds the encrypted settings table, activation timestamp, scan cursor and per-message response tracking. No auto-reply migration file is created and no SQL has been applied automatically.
2. In Google Cloud's OAuth consent configuration, include `https://www.googleapis.com/auth/gmail.send` and `https://www.googleapis.com/auth/gmail.metadata`. The application now requests both. `gmail.settings.basic` is unnecessary because no Gmail settings or filters are changed. Metadata permission can introduce additional Google verification requirements for production; follow the consent-screen requirements shown for your project.
3. Set `APP_BASE_URL` to the public application base URL. The application generates the Google sign-in callback (`/api/auth/google/callback`) and Gmail setup callback (`/api/admin/email-setup/callback`) from it; register both exact URLs with the Google OAuth client. No `GOOGLE_REDIRECT_URI` or `GMAIL_REDIRECT_URI` environment variables are needed. Keep the `GMAIL_TOKEN_ENCRYPTION_KEY` database setting and `GLOBAL_ENCRYPTION_KEY` environment value stable. Google OAuth client credentials are entered in the admin Email Setup page and stored in the customer database; they are not read from `GOOGLE_CLIENT_ID` or `GOOGLE_CLIENT_SECRET` environment variables. No webhook or Pub/Sub setup is required for polling.
4. Deploy, then reconnect Gmail through Admin → Email Setup once and grant the new permission. Old send-only connections continue sending OTPs, but automatic responses remain inactive until metadata access is granted.
5. Leave Reply-To blank or use the same connected mailbox. Replies delivered to another support mailbox cannot be read by this connection.
6. `GMAIL_AUTO_REPLY_ENABLED` defaults to enabled. Set it to `false` and restart to disable only automatic responses; OTP sending continues.

The response says: “This is an automated message. This mailbox is not monitored. Please do not reply to this email. For assistance, contact your parking administrator. Never share your OTP with anyone.” It is sent from the connected sender to the incoming From address, with reply headers and `Auto-Submitted: auto-replied`. It does not quote incoming content and does not include OTPs. All eligible new human messages arriving in INBOX are handled, whether or not they are replies to an OTP.

## Tracking and limits

Run this manually, skipping objects you already created with the same schema:

```sql
BEGIN;
ALTER TABLE email_sender_config
    ADD COLUMN IF NOT EXISTS auto_reply_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE email_sender_config
    ADD COLUMN IF NOT EXISTS auto_reply_page_token TEXT;
CREATE TABLE IF NOT EXISTS gmail_auto_reply (
    sender_email TEXT NOT NULL,
    gmail_message_id TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    last_error TEXT,
    PRIMARY KEY (sender_email, gmail_message_id)
);
COMMIT;
```

Messages received before migration activation (or before first connection for a new mailbox) are skipped. Self mail, sent/draft/spam/trash, mailing-list traffic, delivery bounces, automated responses and no-reply senders are skipped. Unique mailbox/message records and a database advisory lock prevent simultaneous workers from submitting the same response. Inbox reading does not mark messages read, archive them or change labels.

Each incoming message is claimed before submission. SUBMITTED means Gmail accepted it, not recipient delivery. Network/server errors are marked UNCERTAIN and are not automatically resent, because Gmail may already have accepted the response. A process crash after the claim can leave SENDING; inspect these records before manually retrying. Gmail API/mail sending quotas still apply. Responses resume when the application is back online; messages moved out of INBOX before scanning are not processed.

```sql
SELECT sender_email, gmail_message_id, status, created_at, submitted_at, last_error
FROM gmail_auto_reply
ORDER BY created_at DESC;
```

Test after deployment by replying from another mailbox, confirming one notice, and verifying the row is SUBMITTED. Send an automated response with Auto-Submitted set to auto-replied to verify that it is ignored. Tests in the repository check recipient handling, reply headers, malformed headers and automatic/list/bounce/self-message suppression. Actual Gmail delivery and migration execution require your configured environment.
