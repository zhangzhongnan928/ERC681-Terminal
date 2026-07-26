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

The current closed-test candidate is OPK Terminal `0.1.12` / version code `14`, targets API 36,
and is staged locally as
`artifacts/android/v0.1.12/OPK-Terminal-v0.1.12-build14-play-signed.aab` with SHA-256
`d6ed150884e21f1491ed9dba68bf249af77be9cd95989b265eb8ef7c6e896b76`. This meets Google's
31 August 2026 Android 16 target requirement; do not upload the retired target-35 build.

## Candidate roster

Every address must be registered to a Google Account; this can be a Gmail, Google Workspace, or
other email address used for a Google Account. A candidate counts only after being added to this
closed track and individually opting in. Do not invent or substitute addresses.

| Slot | Google-account email | Status |
| --- | --- | --- |
| 1 | `zhangzhongnan928@gmail.com` | Supplied candidate; closed-track opt-in still required |
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

Recruit a small reserve beyond 12 where possible, because anyone who opts out early stops counting.
Testers should be representative of intended merchant users and Android devices.

## Owner-controlled setup and onboarding

1. Finish the app setup tasks required by Play Console.
2. Re-verify and upload the intended signed build-14/API-36 AAB above. The saved build-13
   internal-test draft predates the isolated offline product tour and must not be used as build-14
   evidence.
3. In **Testing > Closed testing**, create or manage the closed track. On its **Testers** tab,
   create/select an email list and add the 12 confirmed Google-account addresses. A CSV import
   replaces the list's existing contents, so review it before saving.
4. Provide a monitored private-feedback email address or URL on the Testers tab.
5. Create the closed-test release, review the exact artifact and release notes, then explicitly
   roll it out. This publication is an owner action.
6. Wait until the closed-test release is shown as **Published**. Play does not expose a usable
   opt-in link while the release is Draft or Pending publication.
7. Copy the closed-test opt-in link and send it only to the intended testers. Each tester must:

   - sign in with the same Google Account that appears in the tester list;
   - join the Google Group first, if a Google Group was used instead of an email list;
   - open the opt-in link, choose to become a tester, and install from Google Play; and
   - remain opted in continuously for at least 14 days and not use the opt-out link.

8. If someone is already opted into the app's internal test, have them opt out of the internal
   test before opting into the closed test; one account cannot be eligible for both tracks at the
   same time.
9. Record each tester's opt-in date, device, sessions, feedback, fixes, and rechecks. Encourage
   periodic real use, but do not describe daily launches as Google's hard gate—the continuous
   opt-in period is the measured requirement.
10. When Play Console confirms eligibility, open **Dashboard > Apply for production**, answer the
    production-access questions accurately, and submit only with owner approval.

## Safe offline-demo test

No OpenPasskey provisioning, private key, PIN, payment funds, wallet connection, or live
transaction is needed. Testers must not send private keys, PINs, provisioning QRs, or other
credentials in feedback.

After installation:

1. Cold-launch OPK Terminal and tap **Explore offline product tour**.
2. Confirm the banner remains visible:
   `OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS`.
3. On Checkout, confirm the sample ERC-681 QR renders and tap **Simulate payment received**.
4. Confirm the simulated payment appears in History.
5. Confirm Settlement is visibly disabled.
6. Close the demo with the back arrow, reopen it, and confirm it returns to the Waiting state.
7. Repeat useful checks after rotation, background/foreground, and a normal app restart.
8. Optionally, after Play has installed the app, disable connectivity and confirm the demo still
   works offline.

## Feedback checklist

Ask each tester to report:

- tester slot number, device manufacturer/model, and Android version;
- installed app version/build (expected `0.1.12` / `14`);
- whether Play installation and any test update completed successfully;
- whether the cold-launch live/demo choice was clear;
- whether the offline-demo banner remained visible on every demo screen;
- QR rendering, simulated confirmation, History entry, disabled Settlement, and reset behaviour;
- rotation, Back navigation, background/foreground, restart, text legibility, and accessibility
  issues;
- any crash, hang, confusing wording, or unexpected network/funds prompt; and
- concise reproduction steps and a screenshot only when it contains no private information.

Test-track users cannot leave a public Play review for the test version. Collect their private
feedback through Play or the monitored route configured for the track.

## Test evidence log

| Item | Record |
| --- | --- |
| Closed-test release/build | `[RELEASE_NAME]` / `14` |
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
