# Preserve the loaded offer across inventory changes

The activity row version advances when orders consume or restore inventory. Requiring every such version to replace the loaded offer made ordinary reload fail after sales, while replacing the Redis balance would erase uncommitted reservations. An unchanged offer therefore reloads as an inventory-preserving no-op; its original offer revision, initial inventory baseline, reservation history, and current balance remain intact.

The existing Redis `databaseVersion` and event `activityVersion` retain the source row version of the loaded offer. The response's `databaseVersion` describes the current MySQL row and may be greater. Compare the actual product, price, quota, limit, status, and time window to establish an unchanged offer. A lower source version is still rejected. We preserve these existing event identities instead of assigning a new configuration version to historical reservations during a migration.

Changing an offer through ordinary loading is allowed only before any reservation history or inventory use, with a higher source version and an intact initial balance. A released reservation still counts as history. Dynamic quota changes, pause/resume after reservations, and recovery after lost Redis facts need their own operational protocol; this loader does not supply one.

Initial available inventory may be below total quota, as the existing loading contract permits. With no reservation history, the supplied available amount becomes the initial balance; reloading never replenishes the difference up to total quota.

Activity quota does not reserve exclusive Catalog Product inventory. Ordinary orders and other activities consume the same MySQL product balance. Order creation must continue to enforce that shared balance transactionally and compensate an accepted reservation when it cannot commit. Reloading cannot increase either balance to make the configuration fit.
