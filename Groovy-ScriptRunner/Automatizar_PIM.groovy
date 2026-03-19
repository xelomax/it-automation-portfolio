/**
 * Script Listener — ScriptRunner for Jira Cloud
 *
 * Automatizacion de PIM (Privileged Identity Management) desde Jira:
 * cuando un ticket transiciona de "Esperando implementacion" a "Implementando",
 * extrae los campos de rol, fecha inicio y fecha fin, calcula la duracion
 * en horas (redondeada al alza) y dispara un Azure Logic App que activa
 * la asignacion PIM en Entra ID para la ventana calculada.
 *
 * Flujo: Jira (aprobacion) -> ScriptRunner -> Azure Logic App -> Azure PIM
 */

import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.Duration
import java.math.RoundingMode

def logger   = LoggerFactory.getLogger(this.class)
def issueKey = issue.key

// IDs de campos personalizados (ajustar segun tu instancia de Jira)
final String ROLE_FIELD  = "customfield_XXXXX"   // Campo "Rol PIM"
final String START_FIELD = "customfield_XXXXX"   // Campo "Fecha inicio"
final String END_FIELD   = "customfield_XXXXX"   // Campo "Fecha fin"

// URL del Azure Logic App / Power Automate Flow HTTP trigger
// IMPORTANTE: nunca hardcodear el SAS token aqui, usar ScriptRunner Secrets o Variable de Script
final String LOGIC_APP_URL = "https://prod-XXX.westeurope.logic.azure.com:443/workflows/YOUR-WORKFLOW-ID/triggers/manual/paths/invoke?api-version=2016-06-01&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=YOUR-SAS-TOKEN"

// --- 1. Detectar transicion especifica en los ultimos 5 segundos ---
def changelogResp = get("/rest/api/3/issue/${issueKey}?expand=changelog")
    .header("Content-Type","application/json").asString()
if (changelogResp.status != 200) {
    logger.error("Error obteniendo changelog: ${changelogResp.status}")
    return
}

def fiveSecondsAgo = ZonedDateTime.now().minusSeconds(5)
def transicionEncontrada = new groovy.json.JsonSlurper()
    .parseText(changelogResp.body).changelog.histories.any { history ->
        ZonedDateTime.parse(history.created, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
            .isAfter(fiveSecondsAgo) &&
        history.items.any {
            it.field == "status" &&
            it.fromString == "Esperando implementacion" &&
            it.toString  == "Implementando"
        }
    }

if (!transicionEncontrada) {
    logger.info("Sin transicion relevante en los ultimos 5s. Saliendo.")
    return
}

// --- 2. Leer campos del ticket ---
def issueResp = get("/rest/api/3/issue/${issueKey}")
    .header("Content-Type","application/json").asObject(Map)
if (issueResp.status != 200) {
    logger.error("Error leyendo issue: ${issueResp.status}")
    return
}

def fields     = issueResp.body.fields
def rol        = fields[ROLE_FIELD]
def startStr   = fields[START_FIELD] as String
def endStr     = fields[END_FIELD]   as String

if (!rol || !startStr || !endStr) {
    logger.error("Campos PIM incompletos: rol=${rol}, inicio=${startStr}, fin=${endStr}")
    return
}

// --- 3. Calcular duracion en horas (redondeada al alza) ---
def fmt       = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
def startDt   = ZonedDateTime.parse(startStr, fmt)
def endDt     = ZonedDateTime.parse(endStr,   fmt)
def horas     = (Duration.between(startDt, endDt).toMinutes() / 60.0)
                    .setScale(0, RoundingMode.CEILING)

logger.info("PIM request: rol=${rol} | inicio=${startDt} | fin=${endDt} | duracion=${horas}h")

// --- 4. Disparar Azure Logic App ---
def payload = JsonOutput.toJson([
    role            : rol,
    startDateTime   : startDt.format(DateTimeFormatter.ISO_INSTANT),
    endDateTime     : endDt.format(DateTimeFormatter.ISO_INSTANT),
    durationHours   : horas,
    jiraIssueKey    : issueKey,
])

def resp = post(LOGIC_APP_URL)
    .header("Content-Type","application/json")
    .body(payload)
    .asString()

if (resp.status in [200, 202]) {
    logger.info("Logic App notificada correctamente para ${issueKey}")
} else {
    logger.error("Error notificando Logic App: ${resp.status} ${resp.body}")
}
