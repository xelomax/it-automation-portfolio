# Azure Function - Timer Trigger (PowerShell)
# Exporta logs de auditoria de Power BI del mes anterior
# via Exchange Online Unified Audit Log.
#
# Estrategia: divide el mes en bloques de 10 dias para evitar el
# limite de 5000 resultados por consulta de Search-UnifiedAuditLog.
# Exporta a CSV en $env:TEMP (adaptable a Blob Storage).

param($Timer)

Import-Module ExchangeOnlineManagement -Force -ErrorAction Stop
Write-Output "Iniciando exportacion de logs Power BI..."

Connect-ExchangeOnline -ShowBanner:$false

# Rango: primer dia del mes anterior hasta hoy
$startDate = (Get-Date -Day 1 -Hour 0 -Minute 0 -Second 0).AddMonths(-1)
$endDate   = $startDate.AddMonths(1)
$outputPath = "$env:TEMP\PowerBIAudit_$($startDate.ToString('yyyy-MM')).csv"

$auditLogs    = @()
$currentStart = $startDate

# Consultas en bloques de 10 dias (workaround limite 5000 filas por llamada)
while ($currentStart -lt $endDate) {
    $currentEnd = [Math]::Min(($currentStart.AddDays(10)).Ticks, $endDate.Ticks)
    $currentEnd = [datetime]::FromFileTime(0).AddTicks($currentEnd - [datetime]::FromFileTime(0).Ticks)
    $currentEnd = $currentStart.AddDays(10)
    if ($currentEnd -gt $endDate) { $currentEnd = $endDate }

    Write-Output "  Bloque: $currentStart -> $currentEnd"

    $results = Search-UnifiedAuditLog `
        -StartDate $currentStart `
        -EndDate   $currentEnd `
        -RecordType PowerBIAudit `
        -Operations "ViewDashboard", "ViewReport" `
        -ResultSize 5000

    if ($results) {
        $auditLogs += $results
        Write-Output "  Registros acumulados: $($auditLogs.Count)"
    }

    Start-Sleep -Seconds 1
    $currentStart = $currentEnd
}

$auditLogs | Export-Csv -Path $outputPath -NoTypeInformation -Encoding UTF8
Write-Output "Exportacion completa: $($auditLogs.Count) registros -> $outputPath"

Disconnect-ExchangeOnline -Confirm:$false
