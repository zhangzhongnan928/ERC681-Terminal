# Google Play closed-test handoff

Checked against official Google Play Help on 26 July 2026. This is an
owner-operated handoff only: it does not invite testers, publish a release, apply for production
access, or operate Play Console.

## Production-access gate

For a newly created personal developer account, Google requires all of the following before the
account can apply for production access:

- Run the app in a **closed test** with at least **12 testers**.
- Keep each of those 12 testers **continuously opted in for at least 14 consecutive days**.
- At the time of application, at least 12 testers must still be opted in and must have been
  continuously opted in for the preceding 14 days.

An internal test does not satisfy this gate. If a tester opts out before completing 14 consecutive
days, that tester does not qualify; separate shorter periods do not add together. Meeting the
12-tester/14-day threshold enables the account to **apply** for production access—it is not
automatic approval. The application asks about the closed test, the app, feedback received, and
production readiness.

Use Play Console's Dashboard as the source of truth for progress and eligibility. The earliest
application point is after the twelfth qualifying tester has completed the full continuous period.

The exact live build is OPK Terminal `0.1.12` / version code `13`, targeting API 35. Its AAB was
already uploaded and became active on the internal-testing track on 26 July 2026 at 7:27 PM
(Australia/Sydney). Do not upload a replacement build now: the owner has explicitly directed that
the existing build 13 be used.

API 35 remains eligible until Google's 31 August 2026 target-level deadline. The safest paths are
either to complete public production release by 30 August 2026 or, if that cannot be achieved, to
prepare and upload a later API-36 build with separate owner approval. This handoff does not
authorize that later upload.

## Candidate roster

Every address must be registered to a Google Account; this can be a Gmail, Google Workspace, or
other email address used for a Google Account. A candidate counts only after being added to this
closed track and individually opting in. Do not invent or substitute addresses.

| Slot | Google-account email | Status |
| --- | --- | --- |
| 1 | `zhangzhongnan928@gmail.com` | Internal-tester list; invitation not yet accepted; must leave internal before joining closed |
| 2 | `[GOOGLE_ACCOUNT_EMAIL_02]` | Placeholder—owner to supply |
| 3 | `[GOOGLE_ACCOUNT_EMAIL_03]` | Placeholder—owner to supply |
| 4 | `[GOOGLE_ACCOUNT_EMAIL_04]` | Placeholder—owner to supply |
| 5 | `[GOOGLE_ACCOUNT_EMAIL_05]` | Placeholder—owner to supply |
| 6 | `[GOOGLE_ACCOUNT_EMAIL_06]` | Placeholder—owner to supply |
| 7 | `[GOOGLE_ACCOUNT_EMAIL_07]` | Placeholder—owner to supply |
| 8 | `[GOOGLE_ACCOUNT_EMAIL_08]` | Placeholder—owner to supply |
| 9 | `[GOOGLE_ACCOUNT_EMAIL_09]` | Placeholder—owner to supply |
| 10 | `[GOOGLE_ACCOUNT_EMAIL_10]` | Placeholder—owner to supply |
| 11 | `[GOOGLE_ACCOUNT_EMAIL_11]` | Placeholder—owner to supply |
| 12 | `[GOOGLE_ACCOUNT_EMAIL_12]` | Placeholder—owner to supply |

The qualifying closed-test count is currently **0 of 12**. Eleven more Google-account emails are
still needed, and the first account must be moved out of internal testing before it can join the
closed test. Recruit a small reserve beyond 12 where possible, because anyone who opts out early
stops counting. Testers should be representative of intended merchant users and Android devices.

The current internal-test opt-in URL is:
<https://play.google.com/apps/internaltest/4701183593011427876>. It is not the future closed-test
opt-in URL, and accepting this internal invitation would not start the qualifying 14-day period.

## Owner-controlled setup and onboarding

1. Finish the app setup tasks required by Play Console without uploading another binary.
2. Reuse the exact uploaded `0.1.12` / version-code-13 / API-35 AAB that is active on internal
   testing. Build 13 has no offline product tour, so do not use the former build-14 demo steps or
   claims as evidence.
3. Before adding `zhangzhongnan928@gmail.com` to closed testing, remove that account from the
   internal tester list or have it opt out if it has accepted the internal invitation. One account
   cannot be eligible for both tracks at the same time.
4. In **Testing > Closed testing**, create or manage the closed track. On its **Testers** tab,
   create/select an email list and add the 12 confirmed Google-account addresses. A CSV import
   replaces the list's existing contents, so review it before saving.
5. Provide a monitored private-feedback email address or URL on the Testers tab.
6. Create the closed-test release from the existing build-13 artifact, review the exact artifact
   and release notes, then explicitly roll it out. This publication is an owner action.
7. Wait until the closed-test release is shown as **Published**. Play does not expose a usable
   opt-in link while the release is Draft or Pending publication.
8. Copy the closed-test opt-in link and send it only to the intended testers. Each tester must:

   - sign in with the same Google Account that appears in the tester list;
   - join the Google Group first, if a Google Group was used instead of an email list;
   - open the opt-in link, choose to become a tester, and install from Google Play; and
   - remain opted in continuously for at least 14 days and not use the opt-out link.

9. If someone is already opted into the app's internal test, have them opt out of the internal
   test before opting into the closed test; one account cannot be eligible for both tracks at the
   same time.
10. Record each tester's opt-in date, device, sessions, feedback, fixes, and rechecks. Encourage
   periodic real use, but do not describe daily launches as Google's hard gate—the continuous
   opt-in period is the measured requirement.
11. When Play Console confirms eligibility, open **Dashboard > Apply for production**, answer the
    production-access questions accurately, and submit only with owner approval. Final production
    rollout also remains owner-controlled.

## Safe build-13 test

Build 13 has no offline demo. Its ordinary first launch and navigation can be tested safely without
OpenPasskey provisioning, a private key, PIN, payment funds, wallet connection, or live
transaction. Testers must not send private keys, PINs, provisioning QRs, or other credentials in
feedback.

After installation:

1. Install build 13 from Google Play and cold-launch OPK Terminal.
2. Confirm the app opens without an account or sign-in prompt.
3. Confirm **Checkout**, **History**, **Settle**, and **Settings** are directly reachable from the
   tab bar.
4. Move between all four tabs and confirm navigation, labels, and content render without a crash,
   hang, or unintended external-app launch.
5. On an unprovisioned device, record the exact state shown by Checkout and Settle. Do not attempt
   a live payment, create a live payment QR, settle funds, or invent provisioning credentials;
   those flows require merchant provisioning outside this test.
6. Check normal Android Back behaviour, background/foreground recovery, rotation where supported,
   and a normal app restart.
7. Confirm History and Settings remain usable to the extent presented on the unprovisioned device,
   without entering secrets or connecting a wallet.

## Feedback checklist

Ask each tester to report:

- tester slot number, device manufacturer/model, and Android version;
- installed app version/build (expected `0.1.12` / `13`);
- whether Play installation and any test update completed successfully;
- whether first launch completed without an account or sign-in prompt;
- whether Checkout, History, Settle, and Settings were directly reachable;
- the exact unprovisioned state shown on Checkout and Settle, without attempting a live payment;
- rotation, Back navigation, background/foreground, restart, text legibility, and accessibility
  issues;
- any crash, hang, confusing wording, unintended external-app launch, or unexpected request for
  credentials, a wallet, or funds; and
- concise reproduction steps and a screenshot only when it contains no private information.

Test-track users cannot leave a public Play review for the test version. Collect their private
feedback through Play or the monitored route configured for the track.

## Test evidence log

| Item | Record |
| --- | --- |
| Closed-test release/build | `[RELEASE_NAME]` / `13` |
| Release status became Published | `[DATE_TIME_AND_TIME_ZONE]` |
| Twelfth qualifying opt-in began | `[DATE_TIME_AND_TIME_ZONE]` |
| Dashboard eligibility shown | `[DATE_TIME_AND_TIME_ZONE]` |
| Feedback location | `[OWNER_CONTROLLED_LOCATION]` |
| Main issues found | `[SUMMARY]` |
| Changes made and retested | `[SUMMARY]` |

Do not infer the eligibility date from this worksheet alone; verify it in Play Console before
applying.

## Separate new-account task

Google may also require the personal-account owner to verify access to a physical, non-rooted
Android 10+ device through the Play Console mobile app. That verification is separate from the
closed-test gate and must be completed by the account owner if it appears on the Dashboard.

## Official sources

- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [Set up an open, closed, or internal test](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-EN)
- [Device verification requirements for new developer accounts](https://support.google.com/googleplay/android-developer/answer/14316361?hl=en)
- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
