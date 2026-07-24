# App Store screenshots

These screenshots were captured on 24 July 2026 from the OPK Terminal `0.1.12`
(`CURRENT_PROJECT_VERSION 13`) simulator product generated from the current source.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 16 Pro Max simulator.
- iPad set: 4 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M4), iOS 18.4.
- Format: 8-bit RGB PNG, no alpha.
- Content: genuine first-run Checkout, History, Settlement, and Terminal Setup screens. No
  production data, personal data, fabricated provisioning, or live payment QR is shown.
- The iPhone screenshot UI test passed. On the iOS 18.4 iPad runtime, the XCTest runner failed to
  launch with `NSPOSIXErrorDomain code 1`; the already-built app product was installed directly,
  launched successfully, and captured through Simulator instead.
- Recheck every screen against the archived App Store build before console submission.

## SHA-256

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
