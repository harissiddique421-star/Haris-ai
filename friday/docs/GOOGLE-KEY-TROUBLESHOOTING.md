# Google key diagnostics — PC 2.0.0-rc.2

Google's AQ. authorization-key format is supported. It is tied to a service
account and a Cloud project; a personal Google login is not itself the API key.
Official references: https://aistudio.google.com/docs/api-key and
https://discuss.ai.google.dev/t/api-key-errors-with-keys-starting-with-aq/170436.

The observed local failures were ACCOUNT_STATE_INVALID (linked account disabled
or deleted) and CONSUMER_SUSPENDED (API-key consumer suspended). The owner must
check key/project/service-account status in Google AI Studio or Google Cloud.
Creating more keys in a suspended project will not resolve the project suspension.
An exposed key should be replaced and disabled, with the replacement entered
only into MYRAA's protected key field. No credential is stored in this document.

RC2 shows actionable state-specific messages in onboarding, text and voice.
AQ and older AIza keys are redacted from new logs, crash reports, model errors
and log-viewer responses, including historical log content. Existing log files
are preserved. A rejected replacement never overwrites the existing protected
key. Network/quota soft saves are labelled unverified rather than verified.
The free model-discovery check is bounded to 12 seconds and one result per page.

Validation: full source suite 146 passed / one skipped; four new auth regression
cases and six backend-route fixture checks. Fixtures use fake credentials and
intercept Google transport, exercising header authentication, precise errors,
key preservation, log redaction and quota handling without a live provider call.

The private Windows update is under `release/auth-fix/`. The currently running
owner app was not closed or modified. Quit the old app from the tray before
launching the new version so its single-instance guard does not focus the old one.
This update improves diagnostics; it cannot reactivate a Google resource.
