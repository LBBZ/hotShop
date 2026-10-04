function Assert-HotShopHostedRunner {
    if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
        throw 'This integration entry point requires a GitHub-hosted runner. Use script/demo.ps1 for local development.'
    }
}
