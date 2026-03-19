# =====================================================================================
#  OFFBOARDING COMPLETO DE USUARIO EN ENTRA ID (AZURE AD)
#  Ejecuta todos los pasos de baja: password reset, revocar sesiones, remover grupos,
#  limpiar atributos, quitar licencias y deshabilitar cuenta.
#  Autenticacion App-Only via Microsoft Graph API.
# =====================================================================================

# ─── CONFIGURACIÓN ────────────────────────────────────────────────────────────
$UserUPN        = "usuario@tudominio.com"           # UPN del usuario a dar de baja
$ClientId       = "YOUR-CLIENT-ID"                  # App Registration Client ID
$TenantId       = "YOUR-TENANT-ID"                  # Azure Tenant ID
$ignoreGroupId  = "YOUR-ALL-USERS-GROUP-ID"         # Grupo que NO se debe eliminar (ej: All Users)
$PasswordLength = 16

# El Client Secret se lee desde un archivo cifrado local (no se guarda en texto plano)
$encSecret    = Get-Content -Path ".\secret.txt"
$secureStr    = $encSecret | ConvertTo-SecureString
$ptr          = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureStr)
$ClientSecret = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($ptr)
[System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)

# ─── GENERAR CONTRASEÑA ALEATORIA SEGURA ──────────────────────────────────────
function New-RandomPassword {
    param([int]$Length = 16)
    $lower   = 'abcdefghijklmnopqrstuvwxyz'.ToCharArray()
    $upper   = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.ToCharArray()
    $digits  = '0123456789'.ToCharArray()
    $symbols = '!@#$%^&*-_+=[]{}()<>?'.ToCharArray()
    $pool    = $lower + $upper + $digits + $symbols

    $pwd = @(
        Get-Random -InputObject $lower
        Get-Random -InputObject $upper
        Get-Random -InputObject $digits
        Get-Random -InputObject $symbols
    )
    while ($pwd.Count -lt $Length) { $pwd += Get-Random -InputObject $pool }
    ($pwd | Get-Random -Count $Length) -join ''
}

# ─── OBTENER TOKEN APP-ONLY (Microsoft Graph) ─────────────────────────────────
$token = Invoke-RestMethod -Method Post `
    -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token" `
    -Body @{
        client_id     = $ClientId
        client_secret = $ClientSecret
        scope         = "https://graph.microsoft.com/.default"
        grant_type    = "client_credentials"
    }
if (-not $token.access_token) { Write-Error "No se obtuvo token."; exit 1 }

$headers = @{ Authorization = "Bearer $($token.access_token)"; "Content-Type" = "application/json" }
$uEnc    = [uri]::EscapeDataString($UserUPN)
$baseUri = "https://graph.microsoft.com/v1.0/users/$uEnc"

# ─── 1. RESTABLECER CONTRASEÑA ────────────────────────────────────────────────
$newPwd = New-RandomPassword -Length $PasswordLength
Invoke-RestMethod -Method PATCH -Uri $baseUri -Headers $headers -Body (
    @{ passwordProfile = @{ password = $newPwd; forceChangePasswordNextSignIn = $true } } | ConvertTo-Json
)
Write-Host "✓ Contraseña restablecida" -ForegroundColor Yellow

# ─── 2. REVOCAR SESIONES ACTIVAS ──────────────────────────────────────────────
Invoke-RestMethod -Method POST -Uri "$baseUri/microsoft.graph.revokeSignInSessions" -Headers $headers -Body '{}'
Write-Host "✓ Sesiones revocadas" -ForegroundColor Green

# ─── 3. LIMPIAR EXTENSION ATTRIBUTES (1–15) ───────────────────────────────────
$extPatch = @{ onPremisesExtensionAttributes = @{} }
1..15 | ForEach-Object { $extPatch.onPremisesExtensionAttributes["extensionAttribute$_"] = $null }
Invoke-RestMethod -Method PATCH -Uri $baseUri -Headers $headers -Body ($extPatch | ConvertTo-Json -Depth 2)
Write-Host "✓ Extension attributes limpiados" -ForegroundColor Green

# ─── 4. REMOVER MANAGER ───────────────────────────────────────────────────────
try {
    Invoke-RestMethod -Method DELETE -Uri "$baseUri/manager/`$ref" -Headers $headers
    Write-Host "✓ Manager eliminado" -ForegroundColor Green
} catch { Write-Warning "No se pudo eliminar manager: $_" }

# ─── 5. REMOVER DE TODOS LOS GRUPOS (excepto grupo base) ─────────────────────
$user     = Invoke-RestMethod -Method GET -Uri $baseUri -Headers $headers
$userId   = $user.id
$memberOf = Invoke-RestMethod -Method GET -Uri "$baseUri/memberOf" -Headers $headers

foreach ($grp in $memberOf.value | Where-Object { $_.'@odata.type' -eq '#microsoft.graph.group' -and $_.id -ne $ignoreGroupId }) {
    try {
        Invoke-RestMethod -Method DELETE `
            -Uri "https://graph.microsoft.com/v1.0/groups/$($grp.id)/members/$userId/`$ref" `
            -Headers $headers
        Write-Host "✓ Removido de grupo: $($grp.displayName)" -ForegroundColor Green
    } catch { Write-Warning "No pude remover de $($grp.displayName): $_" }
}

# ─── 6. LIMPIAR ATRIBUTOS ESTÁNDAR ────────────────────────────────────────────
$standard = @{
    jobTitle = $null; companyName = $null; department = $null
    employeeId = $null; employeeType = $null; officeLocation = $null
    streetAddress = $null; city = $null; state = $null
    postalCode = $null; country = $null; businessPhones = @()
    mobilePhone = $null
}
Invoke-RestMethod -Method PATCH -Uri $baseUri -Headers $headers -Body ($standard | ConvertTo-Json)
Write-Host "✓ Atributos estándar limpiados" -ForegroundColor Green

# ─── 7. QUITAR LICENCIAS ──────────────────────────────────────────────────────
$licDetails = Invoke-RestMethod -Method GET -Uri "$baseUri/licenseDetails" -Headers $headers
foreach ($lic in $licDetails.value) {
    $body = @{ addLicenses = @(); removeLicenses = @($lic.skuId) } | ConvertTo-Json
    Invoke-RestMethod -Method POST -Uri "$baseUri/assignLicense" -Headers $headers -Body $body
    Write-Host "✓ Licencia removida: $($lic.skuPartNumber)" -ForegroundColor Green
}

# ─── 8. DESHABILITAR CUENTA ───────────────────────────────────────────────────
Invoke-RestMethod -Method PATCH -Uri $baseUri -Headers $headers -Body (@{ accountEnabled = $false } | ConvertTo-Json)
Write-Host "✓ Cuenta deshabilitada" -ForegroundColor Green

# ─── VERIFICACIÓN FINAL ───────────────────────────────────────────────────────
Write-Host "`n───── RESULTADO FINAL ─────" -ForegroundColor Cyan
$fields  = 'jobTitle,companyName,department,employeeId,accountEnabled'
$verify  = Invoke-RestMethod -Method GET -Uri "$baseUri`?`$select=$fields" -Headers $headers
foreach ($p in $verify.PSObject.Properties.Name) {
    $v     = $verify.$p
    $empty = ($null -eq $v) -or (($v -is [string]) -and $v.Trim() -eq '')
    $color = if ($empty) { 'Green' } else { 'Yellow' }
    Write-Host ("{0,-25}: {1}" -f $p, $(if ($empty) { 'OK (vacío)' } else { $v })) -ForegroundColor $color
}
Write-Host "`n✓ Offboarding COMPLETO para $UserUPN" -ForegroundColor Cyan
