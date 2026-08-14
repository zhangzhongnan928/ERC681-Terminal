# Contributing

Contributions are welcome — particularly from wallet developers
implementing ERC-681 payment QR support, and from anyone reviewing the
safety boundary.

**Security vulnerabilities do not belong in a pull request or a public
issue.** See [SECURITY.md](./SECURITY.md) for private reporting.

## Licensing of contributions

This project is licensed under the Apache License, Version 2.0. Inbound
contributions are under the same license as outbound: by submitting a
contribution you agree it is licensed under Apache-2.0, per section 5 of
the License.

There is no CLA. There is a DCO sign-off requirement.

## Developer Certificate of Origin

Every commit must be signed off. Add `-s` when you commit:

```bash
git commit -s -m "your message"
```

That appends a line to your commit message:

```text
Signed-off-by: Your Name <your.email@example.com>
```

Use your real name and a working email address. The sign-off certifies
that you have the right to submit the work under the project's license —
the full text is the [Developer Certificate of Origin
1.1](https://developercertificate.org/).

If you used an AI coding assistant, you are still the contributor: you are
certifying you have the right to submit the result and that you have
reviewed it. Sign off in your own name.

## Before you open a pull request

Run the full verification suite:

```bash
./scripts/verify-mobile.sh
```

It enforces the payment-QR, Settings-only camera, read-only SDK, and
constrained-signer boundaries; runs Android and Swift tests, lint, Maven
publication, and both debug and release assembly; checks the shared
conformance vectors; proves the generated Xcode project is current; and
compiles the iOS app.

Requirements: JDK 17, Android SDK platform 36, Swift 6.1+, XcodeGen,
ripgrep, and a full Xcode install with an iOS Simulator SDK.

## What is likely to be accepted

- Wallet interoperability fixes, and additions to the conformance vectors
  in `conformance/`.
- Bug fixes with a test that fails before the fix and passes after.
- Documentation corrections — including corrections to the safety-boundary
  descriptions if you find them inaccurate.
- Accessibility, localisation, and UI fixes.

## What needs discussion first

Open an issue before writing code if your change would:

- widen the signing surface, or alter what the operator module will sign;
- enable an additional network, or change any pinned deployment in
  `conformance/`;
- change the ERC-681 encoding, the CREATE2 derivation, or the
  `opk-terminal:provision` grammar — these are wire formats other people
  depend on;
- add a dependency, especially one that touches keys, cryptography, or
  the network;
- relax a check that currently fails closed.

The constrained-signer design is deliberate, and much of the code exists
to make specific actions impossible. A change that makes the code more
capable is usually a change that makes it less safe. That does not mean
no — it means explain the reasoning in an issue first, so the review is
about the design rather than the diff.

## Style

Match the surrounding code. Kotlin and Swift sources in the SDK
directories carry SPDX headers; keep them on new files:

```kotlin
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang
```

Note that the project name, logo, and icons are reserved and not covered
by the code license — see [TRADEMARK.md](./TRADEMARK.md) if you plan to
distribute a fork.
