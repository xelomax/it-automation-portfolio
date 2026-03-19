# =====================================================================================
#  REPORTE DE ÚLTIMO INICIO DE SESIÓN — TODOS LOS USUARIOS DEL TENANT
#  Exporta a Excel: email, última fecha de login, estado (Activo/Deshabilitado)
#  Usa Microsoft Graph Beta API (necesaria para signInActivity)
# =====================================================================================

Disconnect-MgGraph -ErrorAction SilentlyContinue
Connect-MgGraph -Scopes "AuditLog.Read.All,Directory.Read.All" -NoWelcome

# Endpoint beta con paginacion automatica
$uri   = "https://graph.microsoft.com/beta/users?`$select=userPrincipalName,signInActivity,accountEnabled&`$top=999"
$users = @()

do {
    $resp   = Invoke-MgGraphRequest -Method GET -Uri $uri
    $users += $resp.value
    $uri    = $resp.'@odata.nextLink'
} while ($uri)

Write-Host "Total usuarios obtenidos: $($users.Count)"

# Construir reporte
$reporte = foreach ($u in $users) {
    [PSCustomObject]@{
        'Correo electronico'               = $u.userPrincipalName
        'Ultima fecha de inicio de sesion' = $u.signInActivity.lastSignInDateTime
        'Estado'                           = if ($u.accountEnabled) { 'Activo' } else { 'Deshabilitado' }
    }
}

# Exportar a Excel
$path = "C:\Temp\Reporte_Ultimo_Login.xlsx"
$reporte | Export-Excel -Path $path -AutoSize -TableName "Usuarios" -Show
Write-Host "Reporte guardado en: $path"
