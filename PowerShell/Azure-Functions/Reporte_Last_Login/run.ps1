# Azure Function - HTTP Trigger (PowerShell)
# Genera reporte de ultimo login de todos los usuarios del tenant.
#
# Flujo:
#   1. Obtiene token OAuth2 App-Only (client credentials)
#   2. Pagina sobre Graph API beta /users (signInActivity requiere beta)
#   3. Clasifica cada usuario: Cuenta Externa / Usuario / Buzon Compartido
#   4. Exporta a Excel con ImportExcel
#   5. Retorna el archivo base64-encoded en el body JSON
#      (compatible con n8n: nodo HTTP Request -> leer ContentBase64)
#
# App Registration necesita: AuditLog.Read.All, Directory.Read.All, User.Read.All

using namespace System.Net
param($Request, $TriggerMetadata)

try {
    Import-Module ImportExcel -ErrorAction Stop

    # --- Credenciales desde Application Settings ---
    $TenantId     = $env:TENANT_ID
    $ClientId     = $env:CLIENT_ID
    $ClientSecret = $env:CLIENT_SECRET
    if (-not ($TenantId -and $ClientId -and $ClientSecret)) {
        throw "Faltan variables de entorno: TENANT_ID, CLIENT_ID, CLIENT_SECRET"
    }

    # --- Token App-Only ---
    $token = Invoke-RestMethod -Method Post `
        -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token" `
        -Body @{
            client_id     = $ClientId
            client_secret = $ClientSecret
            scope         = "https://graph.microsoft.com/.default"
            grant_type    = "client_credentials"
        }
    $headers = @{ Authorization = "Bearer $($token.access_token)" }

    # --- Obtener todos los usuarios (paginacion) ---
    $uri   = "https://graph.microsoft.com/beta/users?`$select=userPrincipalName,signInActivity,accountEnabled,assignedLicenses,provisionedPlans&`$top=999"
    $users = @()
    do {
        $resp   = Invoke-RestMethod -Headers $headers -Uri $uri -Method Get
        $users += $resp.value
        $uri    = $resp.'@odata.nextLink'
    } while ($uri)

    Write-Host "Total usuarios: $($users.Count)"

    # --- Clasificacion y construccion del reporte ---
    $report = foreach ($u in $users) {
        $tieneLicencia       = $u.assignedLicenses.Count -gt 0
        $exchangeHabilitado  = $u.provisionedPlans | Where-Object {
            $_.service -eq "Exchange" -and $_.capabilityStatus -eq "Enabled"
        }

        # Reglas de clasificacion (ajustar segun tu tenant)
        $clasificacion = if ($u.userPrincipalName -like "*#EXT#@yourtenant.onmicrosoft.com") {
            "Cuenta Externa"
        } elseif ($tieneLicencia) {
            "Usuario"
        } elseif ($exchangeHabilitado) {
            "Buzon Compartido"
        } else {
            $null   # Excluir del reporte
        }

        if ($null -ne $clasificacion) {
            [PSCustomObject]@{
                "Clasificacion"          = $clasificacion
                "UserPrincipalName"      = $u.userPrincipalName
                "Habilitado"             = if ($u.accountEnabled) { "TRUE" } else { "FALSE" }
                "Ultimo Login"           = $u.signInActivity.lastSignInDateTime ?? "Nunca"
                "Ultimo Login No Interactivo" = $u.signInActivity.lastNonInteractiveSignInDateTime ?? "Nunca"
            }
        }
    }

    # --- Exportar a Excel y retornar base64 ---
    $path = Join-Path $env:TMP "ReporteUsuarios_$(Get-Date -f yyyyMMdd_HHmmss).xlsx"
    $report | Export-Excel -Path $path -WorksheetName "Data" -AutoSize -AutoFilter -TableStyle Light2

    $base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($path))

    Push-OutputBinding -Name Response -Value ([HttpResponseContext]@{
        StatusCode = 200
        Headers    = @{ "Content-Type" = "application/json" }
        Body       = @{ FileName = "ReporteUsuarios.xlsx"; ContentBase64 = $base64 } | ConvertTo-Json
    })
}
catch {
    Push-OutputBinding -Name Response -Value ([HttpResponseContext]@{
        StatusCode = 500
        Body       = $_ | Out-String
    })
}
