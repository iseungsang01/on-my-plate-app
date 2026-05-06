$ErrorActionPreference = "Stop"

$repo = (Resolve-Path ".").Path

# Synthetic IDs:
# P1-1 -> first  ## P1. section
# P1-2 -> second ## P1. section
# P1-3 -> third  ## P1. section
# P2-1 -> first  ## P2. section
# P2-2 -> second ## P2. section
# P2-3 -> third  ## P2. section
# P3-1 -> first  ## P3. section
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

    $issuesPath = Join-Path $repo "issues.md"

    if (-not (Test-Path $issuesPath)) {
        throw "issues.md not found at: ${issuesPath}"
    }

    $issues = Get-Content $issuesPath -Raw -Encoding UTF8

    if ($IssueId -notmatch '^P(\d+)-(\d+)$') {
        throw "Invalid issue id format: ${IssueId}. Expected format like P1-1."
    }

    $priority = $Matches[1]
    $ordinal = [int]$Matches[2]

    # Match sections like:
    # ## P1. title
    # until next ## Pn. heading or end of file
    #
    # This keeps issues.md unchanged while allowing synthetic IDs.
    $pattern = "(?ms)^##\s+P${priority}\.\s+.*?(?=^##\s+P\d+\.\s+|\z)"
    $matches = [regex]::Matches($issues, $pattern)

    if ($matches.Count -lt $ordinal) {
        Write-Host "Available priority issue headings:"
        Select-String -Path $issuesPath -Pattern '^##\s+P\d+\.' | ForEach-Object {
            Write-Host $_.Line
        }

        throw "Issue section not found: ${IssueId}. Found only $($matches.Count) section(s) for P${priority}."
    }

    return $matches[$ordinal - 1].Value
}

foreach ($issueId in $issueIds) {
    Write-Host ""
    Write-Host "====================================="
    Write-Host "Running ${issueId} with fresh context"
    Write-Host "====================================="

    $issueText = Get-IssueSection -IssueId $issueId

    $prompt = @"
Implement only the following extracted issue section from issues.md.

Synthetic issue id: ${issueId}

$issueText

Rules:
- Treat this as a fresh independent task.
- Do not work on other issues.
- Do not use previous Codex session context.
- Inspect the related files before editing.
- Make minimal changes.
- Run the verification commands listed in the issue.
- Update only this issue's checkbox and 처리 기록 in issues.md after completion.
- Do not rename issue headings in issues.md.
- Do not modify unrelated issue sections.
- Do not delete files unless the issue explicitly requires it.
- Do not run destructive git commands such as reset, clean, rebase, or force push.
- Do not modify files outside this repository.
- Do not install global packages or change system/user-level configuration.
- If verification fails, document the failure in 처리 기록 and stop after summarizing the cause.
"@

    Write-Host "About to run: codex exec --ephemeral --yolo for ${issueId}"

    $prompt | codex exec `
        --cd "$repo" `
        --ephemeral `
        --yolo `
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