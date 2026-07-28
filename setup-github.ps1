# ---------------------------------------------------------------------------
#  One-time setup: create the GitHub repo, upload the signing secrets, push.
#
#  Reads the keystore password straight from keystore.properties and pipes it
#  into `gh secret set`, so it is never typed, echoed, or pasted anywhere.
#
#  Run once. After this, shipping a version is: git tag v0.2.0 && git push origin v0.2.0
# ---------------------------------------------------------------------------

param(
    [string]$Owner = "Scottys3DPrints",
    [string]$Repo  = "Bastion",
    [ValidateSet("public", "private")]
    [string]$Visibility = "public"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Fail($msg) { Write-Host ""; Write-Host "  [X] $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host ""; Write-Host "  == $msg" -ForegroundColor Cyan }

Write-Host ""
Write-Host "  Bastion - GitHub setup" -ForegroundColor White
Write-Host "  $Owner/$Repo  ($Visibility)"

if ($Visibility -eq "private") {
    Write-Host ""
    Write-Host "  Note: with a private repo, release assets need authentication to" -ForegroundColor Yellow
    Write-Host "  download. The in-app updater fetches over a plain URL, so it will" -ForegroundColor Yellow
    Write-Host "  NOT work. You would have to install each build from the browser" -ForegroundColor Yellow
    Write-Host "  while signed in." -ForegroundColor Yellow
}

# --- preflight -------------------------------------------------------------

Step "Checking prerequisites"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI (gh) is not installed." }

gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Fail "You are not signed in to GitHub CLI. Run:`n`n      gh auth login`n`n      then re-run this script."
}
Write-Host "      gh authenticated"

if (-not (Test-Path "keystore.properties")) { Fail "keystore.properties is missing." }

$props = @{}
Get-Content "keystore.properties" | ForEach-Object {
    $pair = $_.Split('=', 2)
    if ($pair.Length -eq 2) { $props[$pair[0].Trim()] = $pair[1].Trim() }
}
foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $props.ContainsKey($key)) { Fail "keystore.properties has no '$key' entry." }
}
if (-not (Test-Path $props['storeFile'])) { Fail "Keystore file '$($props['storeFile'])' not found." }
Write-Host "      keystore found: $($props['storeFile'])"

# --- repo ------------------------------------------------------------------

Step "Creating the repository"

$exists = $false
gh repo view "$Owner/$Repo" 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) { $exists = $true }

if ($exists) {
    Write-Host "      $Owner/$Repo already exists - using it"
} else {
    Write-Host "      About to create $Owner/$Repo as a $Visibility repository."
    $answer = Read-Host "      Continue? (y/n)"
    if ($answer -ne 'y') { Fail "Cancelled - nothing was created." }
    gh repo create "$Owner/$Repo" --$Visibility --description "Bastion - quit porn by building the man you want to become" --disable-wiki
    if ($LASTEXITCODE -ne 0) { Fail "Could not create the repository." }
    Write-Host "      created"
}

# --- secrets ---------------------------------------------------------------

Step "Uploading signing secrets"

# Base64 the keystore into a temp file, pipe it to gh, then shred the temp file.
# The bytes never touch the console.
$tmp = [System.IO.Path]::GetTempFileName()
try {
    [Convert]::ToBase64String([System.IO.File]::ReadAllBytes((Resolve-Path $props['storeFile']))) |
        Out-File -FilePath $tmp -Encoding ascii -NoNewline

    Get-Content $tmp -Raw | gh secret set BASTION_KEYSTORE_BASE64 --repo "$Owner/$Repo"
    if ($LASTEXITCODE -ne 0) { Fail "Could not set BASTION_KEYSTORE_BASE64." }
} finally {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
}

$props['storePassword'] | gh secret set BASTION_KEYSTORE_PASSWORD --repo "$Owner/$Repo"
$props['keyAlias']      | gh secret set BASTION_KEY_ALIAS         --repo "$Owner/$Repo"
$props['keyPassword']   | gh secret set BASTION_KEY_PASSWORD      --repo "$Owner/$Repo"

Write-Host "      4 secrets set"
gh secret list --repo "$Owner/$Repo"

# --- push ------------------------------------------------------------------

Step "Pushing the code"

if (-not (Test-Path ".git")) { git init -q -b main }

git remote remove origin 2>&1 | Out-Null
git remote add origin "https://github.com/$Owner/$Repo.git"

git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    git commit -q -m "Bastion: initial commit"
}

git branch -M main
git push -u origin main
if ($LASTEXITCODE -ne 0) { Fail "Push failed." }

# --- done ------------------------------------------------------------------

Write-Host ""
Write-Host "  [OK] Repository ready." -ForegroundColor Green
Write-Host ""
Write-Host "  Ship the first build:"
Write-Host ""
Write-Host "      git tag v0.1.0" -ForegroundColor White
Write-Host "      git push origin v0.1.0" -ForegroundColor White
Write-Host ""
Write-Host "  Then watch https://github.com/$Owner/$Repo/actions"
Write-Host "  Open the 'Report signing identity' step - you want a real SHA-256"
Write-Host "  digest with no warning about a throwaway debug key underneath."
Write-Host ""
Write-Host "  The APK lands at https://github.com/$Owner/$Repo/releases"
Write-Host ""
