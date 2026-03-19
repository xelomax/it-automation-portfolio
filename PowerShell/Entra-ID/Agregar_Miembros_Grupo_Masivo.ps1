# =====================================================================================
#  CARGA MASIVA DE USUARIOS A UN GRUPO DE ENTRA ID DESDE EXCEL
#  - Lee columna 'Email' desde un archivo Excel
#  - Resuelve cada usuario en Graph API
#  - Evita duplicados (detecta si ya es miembro)
#  - Genera transcript de consola + reporte CSV con resultado por usuario
# =====================================================================================

# ─── CONFIGURACIÓN ────────────────────────────────────────────────────────────
$ExcelPath = "C:\Temp\usuarios.xlsx"           # Excel con columna 'Email'
$GroupId   = "YOUR-GROUP-OBJECT-ID"            # ObjectId del grupo destino en Entra ID

$OutDir    = "C:\Temp\AddMembersLogs"
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$Stamp          = (Get-Date -Format "yyyyMMdd_HHmmss")
$CsvReport      = Join-Path $OutDir "Reporte_$Stamp.csv"
$TranscriptPath = Join-Path $OutDir "Transcript_$Stamp.log"

# ─── HELPERS ──────────────────────────────────────────────────────────────────
function Write-Log {
    param([ValidateSet('INFO','OK','WARN','ERROR','STEP')][string]$Level = 'INFO', [string]$Message)
    $icons = @{ OK='✔'; WARN='△'; ERROR='✖'; STEP='▶'; INFO='•' }
    Write-Host "[$(Get-Date -f HH:mm:ss)] $($icons[$Level]) $Message"
}

function Invoke-GraphGet($Uri) {
    return Invoke-MgGraphRequest -Method GET -Uri $Uri -OutputType PSObject -ErrorAction Stop
}

function Get-GraphPaged($Uri) {
    $items = @(); $next = $Uri
    while ($next) { $p = Invoke-GraphGet $next; if ($p.value) { $items += $p.value }; $next = $p.'@odata.nextLink' }
    return $items
}

# ─── INICIO ───────────────────────────────────────────────────────────────────
Start-Transcript -Path $TranscriptPath -Force | Out-Null
Write-Log STEP "Conectando a Microsoft Graph..."
Disconnect-MgGraph -ErrorAction SilentlyContinue
Connect-MgGraph -Scopes "Group.Read.All","User.Read.All","GroupMember.ReadWrite.All" -NoWelcome

# Validar grupo
$group = Invoke-GraphGet "https://graph.microsoft.com/v1.0/groups/$GroupId`?`$select=id,displayName"
Write-Log OK "Grupo: $($group.displayName)"

# Obtener miembros existentes (para evitar duplicados)
$existing = @{}
(Get-GraphPaged "https://graph.microsoft.com/v1.0/groups/$GroupId/members?`$select=id") | ForEach-Object { $existing[$_.id] = $true }
Write-Log INFO "Miembros actuales: $($existing.Keys.Count)"

# ─── PROCESAR EXCEL ───────────────────────────────────────────────────────────
$rows    = Import-Excel -Path $ExcelPath
$results = New-Object System.Collections.Generic.List[psobject]
$i       = 0

foreach ($row in $rows) {
    $i++
    $email = $row.Email.Trim().ToLower()
    if ([string]::IsNullOrWhiteSpace($email)) { continue }

    Write-Log STEP "[$i] $email"
    try {
        $resp = Invoke-GraphGet "https://graph.microsoft.com/v1.0/users?`$filter=userPrincipalName eq '$email'&`$select=id,displayName"
        $user = $resp.value | Select-Object -First 1
        if (-not $user) { Write-Log ERROR "No encontrado: $email"; $results.Add([pscustomobject]@{Email=$email; Estado='No encontrado'}); continue }
    } catch { Write-Log ERROR "Error buscando $email`: $_"; $results.Add([pscustomobject]@{Email=$email; Estado='Error'}); continue }

    if ($existing[$user.id]) { Write-Log INFO "Ya era miembro: $email"; $results.Add([pscustomobject]@{Email=$email; Estado='Ya estaba'}); continue }

    try {
        Invoke-MgGraphRequest -Method POST `
            -Uri "https://graph.microsoft.com/v1.0/groups/$GroupId/members/`$ref" `
            -Body (@{ "@odata.id" = "https://graph.microsoft.com/v1.0/directoryObjects/$($user.id)" } | ConvertTo-Json) `
            -ContentType "application/json" | Out-Null
        Write-Log OK "Agregado: $email"
        $existing[$user.id] = $true
        $results.Add([pscustomobject]@{Email=$email; Estado='Agregado'})
    } catch { Write-Log ERROR "Error agregando $email`: $_"; $results.Add([pscustomobject]@{Email=$email; Estado='Error'}) }
}

# ─── REPORTE ──────────────────────────────────────────────────────────────────
$results | Export-Csv -Path $CsvReport -NoTypeInformation -Encoding UTF8
Write-Log OK ("Resumen -> Agregados: {0} | Ya estaban: {1} | No encontrados: {2} | Errores: {3}" -f
    ($results | Where-Object Estado -eq 'Agregado').Count,
    ($results | Where-Object Estado -eq 'Ya estaba').Count,
    ($results | Where-Object Estado -eq 'No encontrado').Count,
    ($results | Where-Object Estado -eq 'Error').Count)

Stop-Transcript | Out-Null
Write-Host "`n Reporte: $CsvReport`n Transcript: $TranscriptPath"
