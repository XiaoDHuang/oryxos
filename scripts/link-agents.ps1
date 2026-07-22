# Re-create cross-tool agent symlinks for OryxOS
# Run from repo root: pwsh -File scripts/link-agents.ps1

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

function Ensure-Dir($path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
    }
}

function Ensure-FileSymlink($link, $target) {
    if (Test-Path $link) {
        $item = Get-Item $link -Force
        if ($item.LinkType -eq "SymbolicLink") {
            Remove-Item $link -Force
        } else {
            throw "Refusing to replace non-symlink file: $link"
        }
    }
    New-Item -ItemType SymbolicLink -Path $link -Target (Resolve-Path $target).Path | Out-Null
    Write-Host "OK file  $link -> $target"
}

function Ensure-DirSymlink($link, $target) {
    Ensure-Dir (Split-Path $link -Parent)
    if (Test-Path $link) {
        $item = Get-Item $link -Force
        if ($item.LinkType -eq "SymbolicLink" -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Remove-Item $link -Force
        } else {
            throw "Refusing to replace non-symlink directory: $link (move contents to .agents/skills first)"
        }
    }
    New-Item -ItemType SymbolicLink -Path $link -Target (Resolve-Path $target).Path | Out-Null
    Write-Host "OK dir   $link -> $target"
}

if (-not (Test-Path "AGENTS.md")) {
    throw "AGENTS.md missing — create it before linking"
}
Ensure-Dir ".agents\skills"

Ensure-FileSymlink "CLAUDE.md" "AGENTS.md"
Ensure-Dir ".claude"
Ensure-DirSymlink ".claude\skills" ".agents\skills"
Ensure-Dir ".cursor"
Ensure-DirSymlink ".cursor\skills" ".agents\skills"

Write-Host ""
Write-Host "Done. Verify with: Get-Item CLAUDE.md, .claude\skills, .cursor\skills | Format-List FullName, LinkType, Target"
