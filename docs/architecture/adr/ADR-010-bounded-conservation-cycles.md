# Finish bounded conservation cycles without asserting a moving balance

Restarting a paged inventory scan whenever its writer revision changes can repeatedly inspect the same prefix under sustained reservations or releases. Each cycle now captures the current Stream tail and advances to that fixed boundary. A changed inventory fence invalidates the cycle's balance conclusion without resetting its cursor. New deliveries are included in a later cycle.

Only a complete cycle with an unchanged fence may compare effective reservation quantity with inventory. A cycle affected by writes ends as `INCONCLUSIVE`, not as a conservation violation or a successful audit. The next cycle starts from the beginning with a new boundary. This provides finite scan coverage, not a concurrent inventory snapshot; proving conservation while writes continue requires a separate snapshot or change-journal protocol.

Deduplication still validates every delivery and counts each reservation once across pages. Each activity's seen table has a configurable maximum, defaulting to 10,000 effective reservations plus one initialization marker. Exceeding it ends the cycle as inconclusive and detaches the table with `UNLINK`. Inactive tables expire after 24 hours. Missing companion state and legacy checkpoints restart rather than reuse an unverifiable partial sum.

The service records incomplete checks as warning findings and persists their reason in its existing checkpoint. It does not change reservation writers, business inventory, order state, or raw Stream retention. Existing issue lifecycle rules remain: a later clean scan does not silently delete or resolve a prior finding.

The bounds apply to per-cycle metadata and page reads. Total work still scales with retained history; global memory depends on active activity count and asynchronous reclamation. Activities exceeding the configured bound require a separately designed segmented or offline audit, not an automatic inventory reset or an arbitrarily larger allocation.
