# Verifies that the original krxzyinstall credits are present, in order, and
# still wired into the in-app Credits screen.
# Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File scripts\check-credits.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "CREDITS CHECK FAILED: $Message" -ForegroundColor Red
    exit 1
}

function Read-Required([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "Missing file: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

$creditsPath = Join-Path $root "CREDITS.md"
$stringsPath = Join-Path $root "app/src/main/res/values/strings.xml"
$uiPath = Join-Path $root "app/src/main/java/com/krxzy/krxzyinstall/Ui.java"
$mainPath = Join-Path $root "app/src/main/java/com/krxzy/krxzyinstall/MainActivity.java"
$layoutPath = Join-Path $root "app/src/main/res/layout/activity_main.xml"

$credits = Read-Required $creditsPath
if ($credits -notmatch '(?m)^1\. \*\*krxzyuzivr\*\*') {
    Fail "CREDITS.md must keep krxzyuzivr as credit number 1."
}
if ($credits -notmatch '(?m)^2\. \*\*shrimpo\*\*') {
    Fail "CREDITS.md must keep shrimpo as credit number 2."
}

$strings = Read-Required $stringsPath
$creditsMatch = [regex]::Match(
    $strings,
    '(?s)<string name="credits_body">(?<body>.*?)</string>')
if (-not $creditsMatch.Success) {
    Fail "The credits_body string is missing from strings.xml."
}

# Android stores line breaks as literal \n escapes in this resource. Decode them
# so the check compares whole credit lines, not arbitrary substrings.
$creditsText = $creditsMatch.Groups["body"].Value -replace '\\n', "`n"
$creditLines = @($creditsText -split "`r?`n" | ForEach-Object { $_.Trim() })
$creatorLine = [Array]::IndexOf($creditLines, "krxzyuzivr")
$friendLine = [Array]::IndexOf($creditLines, "shrimpo")
if ($creatorLine -lt 0 -or $friendLine -ne ($creatorLine + 1)) {
    Fail "The in-app Credits screen must list krxzyuzivr then shrimpo on consecutive lines."
}

$footerMatch = [regex]::Match(
    $strings,
    '(?s)<string name="footer">(?<footer>.*?)</string>')
if (-not $footerMatch.Success) {
    Fail "The main-screen footer string is missing from strings.xml."
}
if ($footerMatch.Groups["footer"].Value -notmatch 'Made by krxzyuzivr &amp; shrimpo') {
    Fail "The main-screen footer must keep the exact 'Made by krxzyuzivr &amp; shrimpo' credit."
}

$ui = Read-Required $uiPath
if ($ui -notmatch 'showCredits\s*\(' -or
    $ui -notmatch 'R\.string\.credits_body' -or
    $ui -notmatch 'R\.string\.credits_title') {
    Fail "Ui.showCredits no longer displays the protected credits strings."
}

$main = Read-Required $mainPath
if ($main -notmatch 'R\.id\.creditsButton' -or
    $main -notmatch 'Ui\.showCredits\s*\(\s*this\s*\)') {
    Fail "The main screen no longer opens Ui.showCredits."
}

$layout = Read-Required $layoutPath
if ($layout -notmatch '@\+id/creditsButton' -or
    $layout -notmatch '@string/credits') {
    Fail "The Credits button is missing from activity_main.xml."
}

Write-Host "credits OK - krxzyuzivr first, shrimpo second" -ForegroundColor Green
