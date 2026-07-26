# Google Play phone screenshots

These five screenshots were captured on 24 July 2026 from OPK Terminal `0.1.12`
(`versionCode 13`) running on the dedicated iMin L2s Pro test terminal.

- Build source: the then-current `0.1.12` / version-code-13 sources, installed as a debug APK with
  `adb install -r`.
- Existing app data was preserved; the device's first-install timestamp did not change.
- Capture format: 720 × 1440, 8-bit RGB PNG, no alpha.
- Store status: satisfies Google Play's mandatory 320–3840 px and 2:1 rules. It does not meet the
  optional 1080 px guidance for recommendation placements.
- Content: Base Sepolia testnet only. Images show public/truncated testnet identifiers and test-token
  activity, with no personal or production data.
- The temporary `12.34 AUD` screenshot invoice auto-expired after capture.
- Limitation: these were captured from a debug APK, not from the signed build-13 AAB or the
  build-14 release candidate. They predate the build-14 cold-launch choice. Preserve this
  provenance and recheck or recapture the screens against the final signed build-14 AAB before
  console submission.

## SHA-256

```text
b4388530cedd9d352de88c2b886ca2d371f94e6dd0ce51c4b01c592e816ebab0  01-terminal-ready.png
78ee907b689c261dac25bf6474d135b282790cfe060dcafcafc117a685dffd39  02-create-test-payment.png
215504741eb82a7f9ec5d205d477735764b6abb6794d871a41825720e14ef618  03-erc681-payment-qr.png
04f623aa263e0c19b42fec4ee61236d73e1788f68022cb60d66a4e0cbebc76dc  04-confirmed-test-payment.png
65054cfea05be1d80512af6b2512718debb5343054700e2859d70464018c9f98  05-constrained-settlement.png
```
