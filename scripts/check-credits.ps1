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

function Check-Order([string]$Text, [string]$Where) {
    $creator = $Text.IndexOf("krxzyuzivr")
    $friend = $Text.IndexOf("shrimpo")
    if ($creator -lt 0 -or $friend -lt 0) {
        Fail "$Where is missing krxzyuzivr and/or shrimpo."
    }
    if ($friend -lt $creator) {
        Fail "$Where has the credits in the wrong order; krxzyuzivr must be first."
    }
}

$creditsPath = Join-Path $root "CREDITS.md"
$stringsPath = Join-Path $root "app/src/main/res/values/strings.xml"
$uiPath = Join-Path $root "app/src/main/java/com/krxzy/krxzyinstall/Ui.java"
$mainPath = Join-Path $root "app/src/main/java/com/krxzy/krxzyinstall/MainActivity.java"
$layoutPath = Join-Path $root "app/src/main/res/layout/activity_main.xml"

$credits = Read-Required $creditsPath
Check-Order $credits "CREDITS.md"

$strings = Read-Required $stringsPath
$creditsMatch = [regex]::Match(
    $strings,
    '(?s)<string name="credits_body">(?<body>.*?)</string>')
if (-not $creditsMatch.Success) {
    Fail "The credits_body string is missing from strings.xml."
}
$creditsBody = $creditsMatch.Groups["body"].Value
Check-Order $creditsBody "The in-app Credits screen"
if ($creditsBody -notmatch 'krxzyuzivr\\nshrimpo') {
    Fail "The in-app Credits screen must list krxzyuzivr then shrimpo on consecutive lines."
}

$footerMatch = [regex]::Match(
    $strings,
    '(?s)<string name="footer">(?<footer>.*?)</string>')
if (-not $footerMatch.Success) {
    Fail "The main-screen footer string is missing from strings.xml."
}
Check-Order $footerMatch.Groups["footer"].Value "The main-screen footer"

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
