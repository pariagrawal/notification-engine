# Sending real email through Brevo SMTP

By default the engine ships with a **simulated** email provider — it logs
"delivered" but sends nothing. This guide covers wiring it to **Brevo**
(`smtp-relay.brevo.com`) so notifications land in a real inbox, and the
account-side gotchas that block a first send.

The application talks to Brevo through the standard `SmtpEmailProvider`
(activated by `notification.providers.email.mode=smtp`); nothing in the code
changes — only configuration and environment variables.

## 1. Brevo account setup (one time)

1. Create a free Brevo account (the free plan includes SMTP / transactional
   email, ~300 emails/day).
2. **Verify a sender address** — Senders → add the "from" address (e.g. your
   Gmail) → click the confirmation link Brevo emails you. Sending is blocked
   until the sender shows verified.
3. **Get the SMTP credentials** — Settings → *SMTP & API* → **SMTP** tab:
   - `SMTP Server` → `smtp-relay.brevo.com`
   - `Port` → `587`
   - **`Login`** → a value like `bb494b001@smtp-brevo.com`
     — **this is NOT your account email.** Using your Gmail here fails with
     `535 Authentication failed`.
   - Generate an **SMTP key** (`xsmtpsib-…`) — this is the password.
4. **Authorize your public IP** — Security → *Authorized IPs*. Brevo only
   accepts SMTP from whitelisted IPs; otherwise you get `525 Unauthorized IP
   address`. Add your current public IP (find it with
   `curl https://api.ipify.org`). Home IPs are dynamic, so re-add if it changes.

## 2. Run the app against Brevo

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # project needs JDK 17
export PATH=$JAVA_HOME/bin:$PATH

export SPRING_PROFILES_ACTIVE=gmail               # reuses the SMTP profile
export SPRING_MAIL_HOST=smtp-relay.brevo.com      # overrides the profile's gmail host
export SPRING_MAIL_PORT=587
export MAIL_USERNAME='bb494b001@smtp-brevo.com'   # Brevo SMTP *Login*, not your email
export MAIL_PASSWORD='<your Brevo SMTP key>'      # the xsmtpsib-… key
export MAIL_FROM='you@example.com'                # a verified Brevo sender

docker compose up -d      # Postgres, Ignite, Kafka
./mvnw spring-boot:run
```

## 3. Send a test notification

```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: brevo-test-001" \
  -d '{"userId":"demo","channel":"EMAIL","templateCode":"welcome",
       "recipient":"someone@example.com",
       "data":{"firstName":"Demo","product":"Notification Engine"}}'
```

Poll it until `status` is `SENT`:

```bash
curl http://localhost:8080/api/v1/notifications/<id>
```

A successful send logs:

```
[smtp] sent notification <id> to someone@example.com in ~1400ms
notification <id> delivered via smtp
```

First emails from a new Brevo sender often land in **Spam/Promotions** — check
there before assuming a failure.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `535 Authentication failed` | Used account email as SMTP login | Use the Brevo **Login** (`…@smtp-brevo.com`) |
| `525 Unauthorized IP address` | Sending IP not whitelisted | Add your public IP under Security → Authorized IPs |
| Dead-lettered with `PermanentDeliveryException` | Above errors are classified permanent (no retry) | Fix the credential/IP, then resend |
| App won't start / version error | Running on JDK 11 | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` |

The failures above are surfaced by the engine's own delivery pipeline: a
rejected send is classified permanent and routed straight to the
`notifications.email.DLT` topic with the provider's reason recorded, rather than
retried — so a bad credential shows up immediately instead of silently looping.
