<#
.SYNOPSIS
    Signs in a demo user against the local Keycloak and puts the access token in $env:TOKEN.

.EXAMPLE
    . .\scripts\get-token.ps1 alice
    Invoke-RestMethod http://localhost:8080/api/me -Headers @{ Authorization = "Bearer $env:TOKEN" }

.NOTES
    LOCAL DEVELOPMENT ONLY. Uses the password grant on the hrms-dev-cli client, which
    exists only in the local realm. Real apps use the authorization code flow + PKCE.
    Demo passwords are read from infra/keycloak/hrms-realm.json. Tokens expire after 5 minutes.
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('alice', 'mark', 'bob', 'carol')]
    [string]$User
)

$realmFile = Join-Path $PSScriptRoot '..\infra\keycloak\hrms-realm.json'
$realm = Get-Content $realmFile -Raw | ConvertFrom-Json
$password = ($realm.users | Where-Object username -eq $User).credentials[0].value

$response = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8180/realms/hrms/protocol/openid-connect/token' `
    -Body @{ grant_type = 'password'; client_id = 'hrms-dev-cli'; username = $User; password = $password }

$env:TOKEN = $response.access_token
Write-Host "Signed in as $User. Token stored in `$env:TOKEN (valid $($response.expires_in)s)."
