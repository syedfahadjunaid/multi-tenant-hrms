<#
.SYNOPSIS
    Loads demo data through the public API (no direct database access):
    employee records for the demo Keycloak users, so they can use the leave workflow,
    and the reporting line bob -> mark. With -Documents, also two HR policy documents.

    Safe to run more than once: employees that already exist are skipped.
    (Documents have no duplicate check, so pass -Documents only once.)

.EXAMPLE
    .\scripts\seed-demo.ps1 -Documents
#>
param([switch]$Documents)

$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'

function Invoke-Api([string]$Method, [string]$Path, $Body) {
    $params = @{ Method = $Method; Uri = "$api$Path"; Headers = @{ Authorization = "Bearer $env:TOKEN" } }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = $Body | ConvertTo-Json -Depth 5
    }
    Invoke-RestMethod @params
}

function Add-Employee([string]$Code, [string]$First, [string]$Last, [string]$Email,
                      [string]$Department, [string]$Title, $ManagerId) {
    $existing = (Invoke-Api GET '/api/employees?size=100').items | Where-Object email -eq $Email | Select-Object -First 1
    if ($existing) {
        Write-Host "  exists  $First $Last"
        return $existing.id
    }
    $created = Invoke-Api POST '/api/employees' @{
        employeeCode = $Code; firstName = $First; lastName = $Last; email = $Email
        department = $Department; jobTitle = $Title; hireDate = '2023-04-03'; managerId = $ManagerId
    }
    Write-Host "  added   $First $Last"
    return $created.id
}

. "$PSScriptRoot\get-token.ps1" alice
Write-Host 'acme:'
$null = Add-Employee 'ACME-100' 'Alice' 'Admin' 'alice@acme.test' 'People' 'HR Lead' $null
$mark = Add-Employee 'ACME-101' 'Mark' 'Manager' 'mark@acme.test' 'Engineering' 'Engineering Manager' $null
$null = Add-Employee 'ACME-102' 'Bob' 'Builder' 'bob@acme.test' 'Engineering' 'Software Engineer' $mark

if ($Documents) {
    # The nine policies of the small evaluation set (evals/retrieval/datasets/hr_small.json).
    # Salary bands are for HR admins only. (The 66-policy set would exceed the assistant's
    # rate limit of 10 calls per minute.)
    $dataset = Get-Content "$PSScriptRoot\..\services\ai-service\evals\retrieval\datasets\hr_small.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($doc in $dataset.documents) {
        # @(...) keeps a one-element list a list (PowerShell would otherwise unwrap it to a string)
        $roles = @(if ($doc.title -eq 'Salary bands') { 'HR_ADMIN' } else { 'HR_ADMIN', 'MANAGER', 'EMPLOYEE' })
        $null = Invoke-Api POST '/api/assistant/documents' @{ title = $doc.title; content = $doc.content; allowedRoles = $roles }
        Write-Host "  document $($doc.title)"
    }
}

. "$PSScriptRoot\get-token.ps1" carol
Write-Host 'globex:'
$null = Add-Employee 'GLOBEX-1' 'Carol' 'Globex' 'carol@globex.test' 'People' 'HR Lead' $null

Write-Host 'Done. Sign in as bob to request leave, mark to approve it, then alice for the HR step.'
