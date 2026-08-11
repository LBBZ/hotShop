# Agent tools and purchase confirmation

## Security boundary

The model never selects a database table, Java method, HTTP origin, URL, shell command, file, or
runtime-created tool. Python owns two immutable registries and each entry contains a code-defined
name, strict Pydantic input model, identity boundary, required scope, HTTP method, fixed relative
path, and low-cardinality resource type. User and Administrator registries are separate.

User tools are `search_products`, `get_product`, `compare_products`, `list_my_orders`,
`list_my_reservations`, and `create_purchase_draft`. Administrator tools are
`read_statistics`, `read_anomaly_summary`, and `create_configuration_draft`. The Administrator
tools expose only aggregate counts, anomaly counts, and an unapplied low-risk configuration
draft. Refund, compensation, Outbox replay, authorization changes, User suspension, arbitrary
network access, SQL, and shell execution are absent from both registries.

Every User run exchanges the current User Access token again and receives a short-lived Agent
Delegation. Every tool invocation then verifies the token again in Python and again in the Java
request filter. Verification covers the protected header, fixed RS256 algorithm, key ID,
signature, token type, issuer, audience, subject, issued/not-before/expiry times, token use,
authorized party, delegated User, exact scope, and server allowlist. Java method authorization
checks the scope again. Order and Reservation queries derive `userId` only from the verified
principal. Administrator tools accept only Administrator Access tokens and cannot enter the User
confirmation boundary.

The current scope matrix is:

| Capability | Required identity | Required scope |
|---|---|---|
| Product search/read/compare | Agent Delegation | `catalog:read` |
| Own Order query | Agent Delegation | `orders:self:read` |
| Own Reservation query | Agent Delegation | `reservations:self:read` |
| Purchase Draft creation | Agent Delegation | `purchase-drafts:create` |
| Low-risk statistics/anomalies/configuration draft | Administrator Access | none; fixed ROLE_ADMIN boundary |
| Confirmation issue/revoke/consume | User Access | never delegated |

Model output and retrieved text are untrusted data. The LangGraph tool node resolves only the
fixed registry. A run can execute at most one tool. A second tool request, an unknown name, a
high-risk name, a malformed argument object, or an over-limit collection produces a bounded
structured failure and no backend call. Tool results contain purpose-built DTOs instead of
database entities. Backend failures are collapsed to stable tool errors; SQL, internal exception
messages, credentials, and tokens are never returned to the model.

## Purchase Draft

A Purchase Draft is an expiring presentation snapshot, not a reservation or transaction fact.
Creation performs ordinary non-locking reads of active Catalog Products and stores:

- the authenticated delegated User;
- fixed action `CREATE_ORDER`;
- normalized SHA-256 parameter digest;
- unique Product IDs and quantities;
- Product name and price snapshots, line totals, currency, and validity time.

Draft creation does not reduce inventory, create an Order, create a Reservation, or lock a Product.
The response explicitly says that confirmation is required. Snapshot prices explain what the User
reviewed; they do not override the transaction service. Final Order prices and available stock are
read and validated again by `OrderStateService` inside the Order transaction.

## One-time confirmation

Only an authenticated User Access token can issue, revoke, or consume a confirmation. Agent
Delegation and Administrator Access tokens fail at the audience/type boundary before the
controller. The User operations live under the existing `/api/v1/orders/**` transaction boundary:
Draft confirmation issue/revoke and one-time confirmation consumption. Issuance locks the owned
active Draft, checks its fixed action and validity, and creates
a 256-bit CSPRNG opaque value. The clear value is returned once. MySQL stores only its lowercase
SHA-256 hash together with confirmation ID, Draft ID, User ID, action, parameter digest,
canonical Product/quantity JSON, unique nonce, issued/expiry times, and status.

The clear confirmation value is not stored in MySQL, audit state, application logs, SSE, Agent
state, prompts, or model context.

Confirmation states are:

```text
ISSUED --consume successfully--> CONSUMED
ISSUED --User revokes----------> REVOKED
ISSUED --time passes-----------> EXPIRED (derived from expires_at; materialization is optional)
```

Draft states are `ACTIVE`, `CONFIRMATION_ISSUED`, `EXPIRED`, and `CANCELLED`. MySQL CHECK
constraints enforce all state/timestamp/order combinations.

Consumption hashes the supplied value and, in one MySQL transaction:

1. selects the confirmation row `FOR UPDATE`;
2. checks User, Draft, fixed action, parameter digest, canonical Product/quantity parameters,
   status, and expiry;
3. conditionally marks it `CONSUMED` with the new Order ID;
4. invokes the existing transaction service, which rechecks live inventory and prices and writes
   the Order, items, Outbox facts, and durable User timeline;
5. appends the successful confirmation audit record.

The row lock and conditional state update allow only one concurrent success. Replay, cross-User
use, action or item tampering, expiry, and revocation fail. If inventory, Order persistence,
timeline, Outbox, or audit persistence fails, the same transaction rolls back both the Order and
the `CONSUMED` transition, so there is no consumed-without-Order half state.

## Audit and data minimization

Java appends tool and confirmation records to the existing append-only `audit_log`. Tool records
identify actor, delegated User when applicable, fixed tool/action, resource type/ID, result,
request ID, trace ID, source, and a fixed safe parameter summary such as item count, limit,
keyword length, or parameter digest. Confirmation records contain Draft/Order identifiers and the
parameter digest, never the clear token. Administrator summaries similarly contain only the fixed
tool, result, and bounded parameter shape.

No audit or log path records Authorization/Cookie headers, confirmation values, full request or
response bodies, prompts, model output, or reasoning. SSE exposes only allowlisted phase, tool
name, resource type, outcome, bounded summary, usage, and final answer fields.

## Availability

Python Agent state uses its own memory/Redis boundary and reaches Java only through fixed HTTP
APIs. It has no MySQL, Redis, or RabbitMQ client for business facts. Agent/provider failure affects
Agent sessions only; ordinary Product, Order, Reservation, payment, and transaction APIs remain
available independently.

Flyway V1.8 creates the four TASK-16 tables and no foreign keys. V1.0 through V1.7 remain
unchanged.
