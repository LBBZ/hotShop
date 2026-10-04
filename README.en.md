# HotShop

**Product discovery, AI-assisted purchases, and commerce operations in one project.**

HotShop is a full-stack commerce application you can run locally. A React storefront, Java transaction services, and a Python shopping assistant support regular purchases, flash-sale reservations, order payments, and administration.

[简体中文](README.md) · **English** · [Quick start](#quick-start) · [Documentation](docs/README.md) · [Contributing](CONTRIBUTING.md)

[![CI](https://github.com/LBBZ/hotShop/actions/workflows/ci.yml/badge.svg)](https://github.com/LBBZ/hotShop/actions/workflows/ci.yml)

![Storefront with search, category filters, and product cards](docs/assets/catalog.png)

| Shopping assistant                                                                                                       | Operations console                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| [![The assistant prepares a purchase draft for user confirmation](docs/assets/assistant.png)](docs/assets/assistant.png) | [![The administrator reviews orders, reservations, and pending work](docs/assets/operations.png)](docs/assets/operations.png) |
| Find products, ask about policies, and review a draft before creating an order.                                          | Manage products, stock, and campaigns, with audits and operational summaries.                                                 |

Screenshots show the actual frontend with fixed presentation fixtures. Click to enlarge. See [capture notes and reproduction steps](docs/assets/README.md). The application and detailed guides are primarily in Simplified Chinese.

## What you can explore

- **A complete shopping flow** — Search and filter products, zoom into image galleries, review specifications, make purchases, reserve flash-sale stock, and track orders, payments, and reservations.
- **AI purchases with user confirmation** — Product lookup and comparison, the user's own orders, and knowledge answers with citations. Purchase requests become drafts that the user confirms.
- **A dedicated admin workspace** — Product maintenance, stock adjustments, campaign loading, audit records, transaction metrics, and exception summaries.

The interface supports mobile layouts, keyboard navigation, and reduced motion. Demo products use labeled AI-generated images, with category artwork as a fallback. Administrators can edit image URLs, alternative text, and specifications.

## Quick start

Install [Git](https://git-scm.com/downloads), [Docker](https://docs.docker.com/get-started/get-docker/) with Linux containers and Compose **2.24.4+**, and [PowerShell 7.2+](https://learn.microsoft.com/powershell/scripting/install/installing-powershell). Start Docker, then run:

```powershell
git clone https://github.com/LBBZ/hotShop.git
cd hotShop
pwsh -NoProfile -File ./script/demo.ps1 -Action Start
```

The local project is always `hotshop`, with one container per service and fixed image tags. Repeated Start reuses configuration and business data, building only missing images or services whose build inputs changed.

The first run downloads dependencies, builds images, migrates the database, and seeds the demo. After the script reports a successful start, open **http://127.0.0.1:18080** and register to enter your personal workspace.

No model API key is needed: the default demo uses **FakeModel** with predefined responses and a **Mock Provider** for payments. To use a live model, configure DeepSeek or Qwen. The seed contains eight fictional products across audio, electronics, home, and travel, plus three campaigns: available, sold out, and expired.

Try `推荐通勤耳机` (“suggest headphones for commuting”), select two products to compare prices and specifications, then prepare a purchase draft. An order is created only after your confirmation. You can also type `购买商品 913001 数量 1 件` (“buy one unit of product 913001”). The [demo guide](docs/delivery/demo.md) covers admin sign-in, knowledge queries, restart, and stop commands. If the port is busy, append `-WebPort 18081` to the initial start command.

## Implementation highlights

- **Two transaction paths**: Regular purchases deduct stock and create an order in a MySQL transaction. Flash-sale reservations atomically reserve stock and append a Redis Stream event through Lua; workers create the order asynchronously. The UI distinguishes reservation and order states.
- **Recovery after failure**: A transactional Outbox, RabbitMQ publisher confirms, and consumer idempotency handle duplicate delivery. Payment timeouts, stock compensation, and reconciliation cover failure paths.
- **AI connected to business tools**: The Agent calls restricted HTTP tools with identity and resource-ownership checks. Purchases require a single-use confirmation; the model cannot pay directly.

The application is modular and runs as multiple processes in one repository. Java services share domain code and MySQL. See the [current architecture](docs/architecture/current-state.md) and [transaction journey](docs/architecture/user-transaction-journey.md) for diagrams and details.

## Stack and source

| Component and guide                            | Technology                                  |
| ---------------------------------------------- | ------------------------------------------- |
| [Web UI](web/README.md)                        | React · TypeScript · Vite · Tailwind CSS    |
| [Commerce](docs/architecture/current-state.md) | Java 21 · Spring Boot · MyBatis · Flyway    |
| [Assistant](docs/runbooks/agent-service.md)    | Python · FastAPI · LangGraph · Qdrant       |
| [Data](docker-compose.yml)                     | MySQL · Redis Lua / Stream · RabbitMQ       |
| [Monitoring](docs/runbooks/observability.md)   | Prometheus · Grafana · Loki · Tempo · Alloy |

Exact dependency versions are recorded in the [Maven](pom.xml), [Web](web/package.json), and [Python](agent/pyproject.toml) manifests and lockfiles.

## Documentation and contributions

- [Contribution guide](CONTRIBUTING.md): Development prerequisites, checks, and submission conventions.
- [API contract](docs/api/api-contract.md): User, administrator, and Agent interfaces.
- [CI and verification](docs/quality/ci.md): Automated checks and how to run them.
- [Documentation hub](docs/README.md): Architecture, operations, historical reports, and the [backlog](docs/delivery/next-iteration.md).

This project is intended for local demos and engineering practice. Mock payments do not settle real money; unfinished Agent streams do not survive process restarts; existing load tests do not establish production capacity. See the [evidence index](docs/quality/evidence-index.md) for verification scope.

Report bugs and suggest improvements in [Issues](https://github.com/LBBZ/hotShop/issues).
