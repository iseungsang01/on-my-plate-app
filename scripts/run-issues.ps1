$ErrorActionPreference = "Stop"

$repo = (Resolve-Path ".").Path

$issueIds = @(
    "P1-1",
    "P1-2",
    "P1-3",
    "P2-1",
    "P2-2",
    "P2-3",
    "P3-1"
)

function Get-IssueSection {
    param (
        [string]$IssueId
    )

    $issues = Get-Content ".\issues.md" -Raw

    # Matches: ## P1-1. title ... until next ## Pn-m. section or end of file
    $pattern = "(?ms)^## $([regex]::Escape($IssueId))\..*?(?=^## P\d+-\d+\.|\z)"
    $match = [regex]::Match($issues, $pattern)

    if (-not $match.Success) {
        throw "Issue section not found: ${IssueId}"
    }

    return $match.Value
}

foreach ($issueId in $issueIds) {
    Write-Host ""
    Write-Host "====================================="
    Write-Host "Running ${issueId} with fresh context"
    Write-Host "====================================="

    $issueText = Get-IssueSection -IssueId $issueId

    $prompt = @"
Implement only the following issue from issues.md.

$issueText

Rules:
- Treat this as a fresh independent task.
- Do not work on other issues.
- Do not use previous Codex session context.
- Inspect the related files before editing.
- Make minimal changes.
- Run the verification commands listed in the issue.
- Update only the matching checkbox and 처리 기록 in issues.md after completion.
- If verification fails, document the failure in 처리 기록 and stop after summarizing the cause.
"@

    Write-Host "About to run: codex exec --ephemeral for ${issueId}"

    $prompt | codex exec `
        --cd "$repo" `
        --ephemeral `
        --sandbox workspace-write `
        --ask-for-approval never `
        --output-last-message "codex-${issueId}-summary.md" `
        -

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "${issueId} failed. Stopping."
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "Git status after ${issueId}:"
    git status --short

    $changes = git status --porcelain

    if ([string]::IsNullOrWhiteSpace($changes)) {
        Write-Host "No changes produced for ${issueId}."
        continue
    }

    git add .
    git commit -m "Implement ${issueId} from issues.md"

    Write-Host "${issueId} committed."
}

Write-Host ""
Write-Host "All selected issues completed."