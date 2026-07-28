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

**Access Token**:
A short-lived, audience-bound JWT used by exactly one HTTP identity domain. User Access and Administrator Access have
different issuers, audiences, signing keys, and verification key sets.
_Avoid_: Shared JWT, session token

**Refresh Session**:
A server-side login session represented to the caller by a single-use opaque Refresh Token. The database stores only
the token hash; each successful refresh rotates the token.
_Avoid_: Refresh JWT, long-lived access

**Token Family**:
The complete parent/successor chain belonging to one Refresh Session lineage. Reuse of a rotated token revokes every
active token in the family.
_Avoid_: Access-token family

**Service Identity**:
An internal process identity with its own issuer, audience, client identifier, and asymmetric credentials. It is not a
User, Administrator, or Agent Delegation. The repository-local `task` process calls Java services directly and does
not impersonate a Service Identity over HTTP.
_Avoid_: Service user, user JWT

**Agent Delegation**:
A non-refreshable, at-most-five-minute JWT issued only after token exchange validates both the Agent Service Identity
client assertion and a User Access Token. It identifies the delegated User, authorized Agent client, and allowed
scopes, and never carries Administrator authority.
_Avoid_: Agent access token, admin delegation

**Actor**:
The identity directly performing an operation. For an ordinary request it is the authenticated User, Administrator,
or Service Identity.
_Avoid_: Username string

**Delegated Actor**:
The User on whose behalf an authorized Agent client acts. Agent authorization must validate both the Agent client and
this delegated User.
_Avoid_: Agent owner, administrator

**Scope**:
A named, allowlisted Agent capability. A scope narrows an Agent Delegation; it never grants a role or crosses into
User or Administrator HTTP boundaries.
_Avoid_: Role, wildcard permission

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
