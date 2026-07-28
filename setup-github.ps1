# ---------------------------------------------------------------------------
#  One-time setup: create the GitHub repo, upload the signing secrets, push.
#
#  Reads the keystore password straight from keystore.properties and hands it to
#  `gh secret set`, so it is never typed out or echoed to the console.
#
#  Run once. After this, shipping a version is:
#      git tag v0.2.0 && git push origin v0.2.0
# ---------------------------------------------------------------------------

param(
    [string]$Owner = "Scottys3DPrints",
    [string]$Repo  = "Bastion",
    [ValidateSet("public", "private")]
    [string]$Visibility = "public"
)

# Note: deliberately NOT 'Stop'. Windows PowerShell turns any stderr output from
# a native command into a NativeCommandError record, and `gh auth status` writes
# its perfectly normal output to stderr. With ErrorActionPreference=Stop that
# healthy output aborts the script. Native failures are detected by inspecting
# $LASTEXITCODE explicitly instead, which is the only reliable signal anyway.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

function Fail($msg) {
    Write-Host ""
    Write-Host "  [X] $msg" -ForegroundColor Red
    Write-Host ""
    exit 1
}
function Step($msg) { Write-Host ""; Write-Host "  == $msg" -ForegroundColor Cyan }

# Runs a native command, swallowing its output, and returns the exit code.
# Wrapping it this way keeps stderr from being reinterpreted as a PowerShell error.
function Invoke-Quiet {
    param([Parameter(Mandatory = $true)][scriptblock]$Command)
    & $Command 2>&1 | Out-Null
    return $LASTEXITCODE
}

Write-Host ""
Write-Host "  Bastion - GitHub setup" -ForegroundColor White
Write-Host "  $Owner/$Repo  ($Visibility)"

if ($Visibility -eq "private") {
    Write-Host ""
    Write-Host "  Note: with a private repo, release assets need authentication to" -ForegroundColor Yellow
    Write-Host "  download. The in-app updater fetches over a plain URL, so it will" -ForegroundColor Yellow
    Write-Host "  NOT work. You would install each build from the browser instead," -ForegroundColor Yellow
    Write-Host "  while signed in to GitHub on the phone." -ForegroundColor Yellow
}

# --- preflight -------------------------------------------------------------

Step "Checking prerequisites"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Fail "GitHub CLI (gh) is not installed.  https://cli.github.com"
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail "git is not installed."
}

# Deliberately not `gh auth status`: that reports failure if ANY configured
# account has a stale token, even when the active one is perfectly good. Asking
# the API who we are tests the thing that actually matters — can this CLI act on
# your behalf right now.
$account = (& { gh api user --jq .login } 2>&1 | Select-Object -First 1)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($account)) {
    Fail "GitHub CLI cannot reach the API. Run:`n`n      gh auth login`n`n      then re-run this script."
}
$account = $account.ToString().Trim()
Write-Host "      authenticated as $account"

# Pushing .github/workflows/ requires the 'workflow' scope; without it the push
# is rejected at the end, after the repo and secrets already exist.
$scopeCheck = (& { gh auth status } 2>&1 | Out-String)
if ($scopeCheck -notmatch 'workflow') {
    Write-Host "      [!] Your token may lack the 'workflow' scope, which is required to" -ForegroundColor Yellow
    Write-Host "          push the release pipeline. If the push is rejected, run:" -ForegroundColor Yellow
    Write-Host "          gh auth refresh -h github.com -s workflow" -ForegroundColor Yellow
}

if (-not (Test-Path "keystore.properties")) {
    Fail "keystore.properties is missing. Without the signing key, CI builds cannot update your phone."
}

$props = @{}
foreach ($line in Get-Content "keystore.properties") {
    if ($line -match '^\s*#') { continue }
    $pair = $line.Split('=', 2)
    if ($pair.Length -eq 2) { $props[$pair[0].Trim()] = $pair[1].Trim() }
}
foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $props.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($props[$key])) {
        Fail "keystore.properties has no usable '$key' entry."
    }
}
if (-not (Test-Path $props['storeFile'])) {
    Fail "Keystore file '$($props['storeFile'])' not found."
}
Write-Host "      keystore found: $($props['storeFile'])"

# --- repo ------------------------------------------------------------------

Step "Repository"

$exists = (Invoke-Quiet { gh repo view "$Owner/$Repo" }) -eq 0

if ($exists) {
    Write-Host "      $Owner/$Repo already exists - using it"
} else {
    Write-Host "      About to create $Owner/$Repo as a $Visibility repository."
    $answer = Read-Host "      Continue? (y/n)"
    if ($answer -ne 'y') { Fail "Cancelled - nothing was created." }

    gh repo create "$Owner/$Repo" "--$Visibility" --disable-wiki `
        --description "Bastion - quit porn by building the man you want to become"
    if ($LASTEXITCODE -ne 0) { Fail "Could not create the repository." }
    Write-Host "      created"
}

# --- secrets ---------------------------------------------------------------

Step "Uploading signing secrets"

# Passed with --body rather than piped through stdin on purpose. PowerShell
# appends a trailing newline when piping a string to a native process, and that
# newline would become part of the stored password — CI would then fail to sign
# with a "wrong password" error that looks like a mystery. --body passes the
# value as an argument, byte for byte.
$keystoreB64 = [Convert]::ToBase64String(
    [System.IO.File]::ReadAllBytes((Resolve-Path $props['storeFile']))
)

$secrets = [ordered]@{
    'BASTION_KEYSTORE_BASE64'   = $keystoreB64
    'BASTION_KEYSTORE_PASSWORD' = $props['storePassword']
    'BASTION_KEY_ALIAS'         = $props['keyAlias']
    'BASTION_KEY_PASSWORD'      = $props['keyPassword']
}

foreach ($name in $secrets.Keys) {
    $value = $secrets[$name]
    if ((Invoke-Quiet { gh secret set $name --repo "$Owner/$Repo" --body $value }) -ne 0) {
        Fail "Could not set $name."
    }
    Write-Host "      set $name"
}

Write-Host ""
gh secret list --repo "$Owner/$Repo"

# --- push ------------------------------------------------------------------

Step "Pushing the code"

if (-not (Test-Path ".git")) {
    git init -q -b main
}

Invoke-Quiet { git remote remove origin } | Out-Null
git remote add origin "https://github.com/$Owner/$Repo.git"
if ($LASTEXITCODE -ne 0) { Fail "Could not set the git remote." }

git add -A

# `git diff --cached --quiet` exits 1 when there IS something staged.
Invoke-Quiet { git diff --cached --quiet } | Out-Null
if ($LASTEXITCODE -ne 0) {
    git commit -q -m "Bastion: initial commit"
    if ($LASTEXITCODE -ne 0) { Fail "Commit failed. Set your git identity with:`n`n      git config --global user.email you@example.com`n      git config --global user.name  ""Your Name""" }
}

git branch -M main
git push -u origin main
if ($LASTEXITCODE -ne 0) {
    Fail "Push failed. If it asked for a password, use a personal access token instead - GitHub no longer accepts account passwords over https."
}

# --- done ------------------------------------------------------------------

Write-Host ""
Write-Host "  [OK] Repository ready." -ForegroundColor Green
Write-Host ""
Write-Host "  Ship the first build:"
Write-Host ""
Write-Host "      git tag v0.1.0" -ForegroundColor White
Write-Host "      git push origin v0.1.0" -ForegroundColor White
Write-Host ""
Write-Host "  Then watch  https://github.com/$Owner/$Repo/actions"
Write-Host "  Open the 'Report signing identity' step. You want this digest:"
Write-Host ""
Write-Host "      91626d9ade623311129c6b9ee557e83f1562a39943ec7a308b520c414416f7c2" -ForegroundColor White
Write-Host ""
Write-Host "  with no warning underneath about a throwaway debug key. That is the"
Write-Host "  same key your local builds use, and it is what lets every future"
Write-Host "  version install over the top instead of forcing a reinstall."
Write-Host ""
Write-Host "  The APK lands at  https://github.com/$Owner/$Repo/releases"
Write-Host ""
