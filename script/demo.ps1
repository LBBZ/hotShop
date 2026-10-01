#requires -Version 7.0
<#
.SYNOPSIS
Start and manage an isolated local HotShop demo.
.DESCRIPTION
Public entry point for the existing demo lifecycle. Project names and stored
configuration remain compatible with task21-demo.ps1.
#>
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Stop','Restart')][string]$Action = 'Start',
    [string]$ProjectName = '',
    [ValidateRange(1024,65535)][int]$WebPort = 18080,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'task21-demo.ps1') @PSBoundParameters
