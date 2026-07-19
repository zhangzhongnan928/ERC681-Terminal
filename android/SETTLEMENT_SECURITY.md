# Android settlement security notes

The Android app persists every accepted `Swept` log as immutable evidence keyed by chain ID,
transaction hash, and log index. Cumulative amounts are scoped to chain, vault, invoice ID, and
token, so later proof cannot be double-counted or mixed across deployments.

A single positive event below the invoice's original expected amount is
`PARTIALLY_SETTLED`, never `SETTLED`. The tagged v0.1 vault has no settled flag: a later call uses
the same receiver, reads its current balance, and can sweep newly arrived funds. The app therefore
allows a partial invoice to be reviewed again only when its pending receiver balance is positive.
Each retry still submits the immutable original expected amount. It reaches `SETTLED` only when
unique, confirmed evidence cumulatively totals at least that original amount.

Legacy `SETTLED` database rows predate durable receipt evidence. Migration marks them
`SETTLEMENT_REVIEW_REQUIRED`; a historical transaction hash alone is not settlement proof.
