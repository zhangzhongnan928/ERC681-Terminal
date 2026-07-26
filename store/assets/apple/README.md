# App Store screenshots

## Build-14 simulator candidates

The candidate sets under `build-14/` were captured on 26 July 2026 from the OPK Terminal
`0.1.12` (`CURRENT_PROJECT_VERSION 14`) simulator app built from source revision
`1b866fef4cf965df836c17addfc91612b06d9fb2`.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 17 Pro Max simulator,
  iOS 26.5.
- iPad set: 5 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M5) simulator,
  iOS 26.5.
- Format: 8-bit RGB PNG, no alpha.
- The dedicated `testCaptureOfflineReviewerDemoStoreScreenshots` UI test passed independently on
  both devices. The iPad set's five attachments all came from one passing run.
- The test forbids live bootstrap. Screen 1 shows the cold-launch choice; screens 2–5 show the
  isolated, in-memory reviewer demo with the persistent
  `OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS` banner.
- Sequence: cold-launch choice, simulated checkout waiting, simulated checkout paid, in-memory
  demo history, and disabled settlement preview.
- The images were visually inspected at capture time. They contain fixed dummy demo data and no
  live RPC, signing, broadcast, production data, or personal data.
- These are truthful build-14 simulator candidates, not signed-archive evidence. Preserve the
  uploaded build-13 assets below and compare these screens with the eventual signed App Store
  archive before replacing anything in App Store Connect.

### Build-14 SHA-256

```text
ece385e11cd737d1732dc70b57199eb65edf5d301534b649fc1f80a1d7214d6c  build-14/iphone-6.9/01-cold-launch-choice.png
61306a39d4903d7b1c41dd10a2245430900f422d5cbb32507452ab9be4f96a3c  build-14/iphone-6.9/02-offline-demo-waiting.png
0afce8f238726a7723dab95b048f88fcb82494af365a6fabe65c0dad0891ecba  build-14/iphone-6.9/03-offline-demo-paid.png
2de6e97c69003579ee774fd37047b3a115198259ab75346380e2c014452d3e79  build-14/iphone-6.9/04-offline-demo-history.png
9bc609dcb478f15ae435de9d522e2d3c946f3651d235b4cafc6dd8b4e2292859  build-14/iphone-6.9/05-offline-demo-settlement.png
ef4ef218e2330b7580f928932ee33d7be4b59db372b0fd784c89d0549d65f2e9  build-14/ipad-13/01-cold-launch-choice.png
697f92e2637f2b072d26e1cdccbcd0983ce1fcb0df91bf9f8636832d53a7c199  build-14/ipad-13/02-offline-demo-waiting.png
b1863c1c9db240416548404be11230e53b28ff469987931fcd9f3c9d2a713f11  build-14/ipad-13/03-offline-demo-paid.png
8919737682ae8058f3f8234222cf4d570725dbcb6f1a9763b409cf8910438f19  build-14/ipad-13/04-offline-demo-history.png
785a697ba98c3a6bd5f14c24ae7cd17245fc075a7d2ebef66400a3cc5b8c5ccc  build-14/ipad-13/05-offline-demo-settlement.png
```

## Uploaded build-13 historical sets

These screenshots were captured on 24 July 2026 from the OPK Terminal `0.1.12`
(`CURRENT_PROJECT_VERSION 13`) simulator product generated from the then-current source snapshot.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 16 Pro Max simulator.
- iPad set: 4 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M4), iOS 18.4.
- Format: 8-bit RGB PNG, no alpha.
- Content: genuine first-run Checkout, History, Settlement, and Terminal Setup screens. No
  production data, personal data, fabricated provisioning, or live payment QR is shown.
- The iPhone screenshot UI test passed. On the iOS 18.4 iPad runtime, the XCTest runner failed to
  launch with `NSPOSIXErrorDomain code 1`; the already-built app product was installed directly,
  launched successfully, and captured through Simulator instead.
- These captures predate the build-14 cold-launch choice. Preserve their build-13 provenance; they
  may depict live-terminal feature screens but must not be described as build-14 first-run
  captures.
- Recheck or recapture every screen against the archived build-14 App Store candidate before
  console submission.

### Historical build-13 SHA-256

```text
cc268cb910482565c760a6c7f8a512884de49207f28e3b25d157cb42656eac20  iphone-6.9/01-first-run-checkout.png
ad1d5f38125b5ab761a0a05e2e4190ee7afbb59e9531dfe68f329ee59c6b5550  iphone-6.9/02-history.png
8ca679981b58ba00e5d6eee5c95f528064606689dd3ed5b09e4df17ad1623c56  iphone-6.9/03-settlement.png
93d2268a63897a4a7376c25c8041cd66acbbb23e4ae182716352fe29c6e49734  iphone-6.9/04-terminal-setup.png
42c78f3ece6bdfe70f5ac877dee778089fd16280a03f71e0d82fc38e3b9883d4  iphone-6.9/05-privacy-support.png
14a87342bb5409bddaf7223a132975b03017a987f0f73cf10e178a22f1c7e6cf  ipad-13/01-first-run-checkout.png
632fcfccee0be8a1741e98ec17836f5481c18dea12be51fc48f87797adfde23c  ipad-13/02-empty-history.png
ae341115546f2db2d7c12c448a64ee79d626f014143304463014ba2282f63286  ipad-13/03-empty-settlement.png
00072287aab5b308eb9ee3a9ec186d012a6f6e2671f7cb02c7e6e1251c9b5b79  ipad-13/04-terminal-setup.png
```
