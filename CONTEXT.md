# HotShop Commerce Context

HotShop models a flash-sale purchase from product discovery through reservation, ordering, and payment. The language
below is shared by the Java application, React interface, Agent tools, tests, and interview documentation.

## Identity

**User**:
An identity that can sign in and own reservations, orders, and payments.
_Avoid_: Customer account, member

**Administrator**:
A User entrusted with operational permissions; it is a role, not a separate kind of identity.
_Avoid_: Admin account, backend user

**Username**:
A permanent sign-in identifier that is never reassigned, including after its User is disabled.
_Avoid_: Nickname, reusable handle

## Catalog and flash sale

**Catalog Product**:
An item presented for discovery and sale independently of any particular flash-sale event.
_Avoid_: Activity product, SKU record

**Flash Sale Activity**:
A time-bounded offer that allocates a price, inventory, and per-User purchase limit for one Catalog Product.
_Avoid_: Campaign order, seckill record

**Reservation**:
A User's temporary claim on inventory from one Flash Sale Activity before an Order is created.
_Avoid_: Pre-order, pending order

**Effective Reservation**:
A Reservation that still owns its activity slot because it has not been released, canceled, expired, or compensated.
_Avoid_: Successful order

## Ordering and payment

**Order**:
The durable purchase commitment created from an accepted Reservation and owned by one User.
_Avoid_: Reservation, transaction

**Order Item**:
The immutable product, quantity, and price snapshot belonging to an Order.
_Avoid_: Cart item, live product

**Payment Order**:
A request to one payment provider to collect the amount due for an Order.
_Avoid_: Order, payment callback

**Cancellation**:
The terminal business outcome in which an existing Order will not proceed.
_Avoid_: Compensation

**Compensation**:
The restoration of inventory and purchase eligibility after a Reservation cannot be converted successfully.
_Avoid_: Refund, cancellation
