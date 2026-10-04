# Working in HotShop

## Workspace

- Keep exactly one HotShop project directory: the existing repository root. Work on branches in this checkout; do not create additional Git worktrees, project clones, or sibling review, task, merge or backup directories.
- Keep necessary local evidence and backups under the ignored `.local/verification/` directory inside this repository; active credentials stay in `.local/keys/`. Preserve unique files before removing obsolete directories; disposable build outputs and dependency caches can be deleted.

## Local Docker environment

- Maintain exactly one local Compose project: `hotshop`. Each service has its own container. Use `pwsh -NoProfile -File script/demo.ps1` for its lifecycle.
- Reuse `.local/keys/hotshop/.env.demo`, existing authentication keys and data volumes. Never reseed an existing database or regenerate credentials on Start.
- Generate authentication keys with the host PowerShell/.NET implementation. Resume missing key material only during an explicitly recorded first initialization; never launch a key-generation container.
- Application image tags are fixed: `hotshop-admin:local`, `hotshop-portal:local`, `hotshop-task:local`, `hotshop-agent:local`, `hotshop-web:local`, `hotshop-rabbitmq:local`. Do not create task, date, branch, worktree or verification variants, duplicate environments or a second frontend Compose project.
- Start builds only missing images or services whose build inputs changed. Rebuild the affected service under the same tag. Remove superseded project image IDs only after no container references them.
- Run local checks with installed tools. Disposable upstream tool containers may use `--rm`; they must not build project test images or create another application environment. Prefer mocks for lifecycle tests.
- Scripts under `script/ci/` that require a hosted runner are for GitHub-hosted CI only. Do not run them locally, impersonate a runner, or copy their build/setup commands into local work. CI image tags and isolation apply only to disposable hosted runners.
- `docs/quality/` and `docs/roadmap/` are historical evidence, not installation or execution instructions. Follow `docs/delivery/demo.md` and the current runbooks.
- Do not run load, stress, soak or capacity tests unless the user explicitly changes the current no-load-testing requirement.
- Before cleanup, inspect exact resource ownership. Remove only HotShop containers, project-built images and unused project networks. Preserve business data volumes, key files, backups and resources belonging to other projects. Never use global Docker prune or `down -v` locally.

## Delivery

Keep Chinese and English README instructions consistent. Update authoritative documentation directly; do not add compatibility wrappers, workaround notes or a new historical report for the Docker lifecycle change. Validate fixed naming, repeated Start, migration failure handling, source-aware builds and preservation of existing data. Complete the authorized merge and push to `master` after checks pass.
