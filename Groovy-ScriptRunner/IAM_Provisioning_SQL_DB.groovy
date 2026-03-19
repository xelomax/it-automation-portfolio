/**
 * Script Listener — ScriptRunner for Jira Cloud
 *
 * IAM Self-Service: provisionamiento automatico de acceso a Azure SQL
 * directamente desde un ticket de Jira.
 *
 * Cuando un ticket transiciona a "Open":
 *   1. Lee los campos del ticket: entorno, servidor SQL, email, tipo de permiso, fecha
 *   2. Si la fecha objetivo es futura: deja un comentario y espera
 *   3. Resuelve el grupo de Azure AD correspondiente (por servidor x tipo de permiso x entorno)
 *   4. Obtiene token OAuth2 App-Only desde ScriptRunner secrets
 *   5. Agrega al usuario al grupo via Microsoft Graph API
 *   6. Maneja el caso "ya es miembro" como exito
 *   7. Transiciona el ticket y lo asigna al bot de automatizacion
 *
 * Requiere ScriptRunner Secrets: TENANTID, CLIENTID, CLIENTSECRET
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import groovy.transform.Field
import java.time.LocalDate
import java.time.ZoneId

@Field final PERM_FIELD   = "customfield_XXXXX"   // Campo "Tipo de permiso" (Read/Write)
@Field final SERVER_FIELD = "customfield_XXXXX"   // Campo "Servidor SQL"
@Field final EMAIL_FIELD  = "customfield_XXXXX"   // Campo "Email del usuario"
@Field final DATE_FIELD   = "customfield_XXXXX"   // Campo "Fecha de acceso"
@Field final ENV_FIELD    = "customfield_XXXXX"   // Campo "Entorno" (PROD/DEV/STG)

@Field final WRITE_OPTION_ID = "XXXXX"            // ID de la opcion "Write" en el campo permiso
@Field final READ_OPTION_ID  = "XXXXX"            // ID de la opcion "Read"
@Field final TRANSITION_ID   = "YOUR-TRANSITION-ID"
@Field final BOT_ASSIGNEE_ID = "YOUR-BOT-JIRA-ACCOUNT-ID"

// Mapeo PROD: servidor SQL -> {tipo permiso -> [nombre grupo, UUID grupo Azure AD]}
// Cada servidor tiene grupos separados para lectura y escritura
@Field
Map<String, Map<String, List<String>>> sqlGroupMapProd = [
    "sqlserver-prod-1.database.windows.net": [
        Write: ["AZ-SQL-PROD1-RW",       "YOUR-WRITE-GROUP-UUID-1"],
        Read:  ["AZ-SQL-PROD1-ReadOnly",  "YOUR-READ-GROUP-UUID-1"],
    ],
    "sqlserver-prod-2.database.windows.net": [
        Write: ["AZ-SQL-PROD2-RW",       "YOUR-WRITE-GROUP-UUID-2"],
        Read:  ["AZ-SQL-PROD2-ReadOnly",  "YOUR-READ-GROUP-UUID-2"],
    ],
    "sqlserver-prod-creditcards.database.windows.net": [
        Write: ["AZ-SQL-CC-RW",          "YOUR-WRITE-GROUP-UUID-3"],
        Read:  ["AZ-SQL-CC-ReadOnly",     "YOUR-READ-GROUP-UUID-3"],
    ],
    // Agregar mas servidores segun tu infraestructura...
]

// Grupos unificados para DEV y STG (mismo grupo para todos los servidores no-productivos)
@Field
Map<String, List<String>> devStgGroups = [
    Write: ["AZ-SQL-DEVSTG-RW",      "YOUR-DEVSTG-WRITE-GROUP-UUID"],
    Read:  ["AZ-SQL-DEVSTG-ReadOnly", "YOUR-DEVSTG-READ-GROUP-UUID"],
]

// ─────────────────────────────────────────────────────────────────────────────

def logger   = LoggerFactory.getLogger(this.class)
def issueKey = issue.key

// 1. Detectar transicion a "Open" en los ultimos 10 segundos
def changelog = get("/rest/api/3/issue/${issueKey}/changelog")
    .header("Content-Type","application/json").asString()
if (changelog.status != 200) { logger.error("Changelog error: ${changelog.status}"); return }

def now = System.currentTimeMillis()
def transitionedToOpen = new JsonSlurper().parseText(changelog.body).values.any { entry ->
    (now - new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(entry.created).time) < 10_000 &&
    entry.items.any { it.field == "status" && it.toString == "Open" }
}
if (!transitionedToOpen) return

// 2. Leer campos del ticket
def fields = get("/rest/api/3/issue/${issueKey}?fields=${PERM_FIELD},${SERVER_FIELD},${EMAIL_FIELD},${DATE_FIELD},${ENV_FIELD}")
    .header("Content-Type","application/json").asString()
if (fields.status != 200) { logger.error("Field read error"); return }

def f = new JsonSlurper().parseText(fields.body).fields

// 2a. Validar fecha: si es futura, comentar y salir
def targetDate = LocalDate.parse(f[DATE_FIELD] as String)
if (targetDate.isAfter(LocalDate.now(ZoneId.of("UTC")))) {
    addComment(issueKey, "Access will be granted on ${f[DATE_FIELD]}. The automation will run on that date.")
    return
}

// 2b. Entorno
def env = f[ENV_FIELD]?.value
if (env !in ["PROD", "DEV", "STG"]) { logger.info("Entorno no reconocido: ${env}"); return }

// 2c. Servidor (omitir PostgreSQL)
def serverVal = (f[SERVER_FIELD] instanceof List) ? f[SERVER_FIELD][0]?.value : f[SERVER_FIELD]?.value
def serverId  = (f[SERVER_FIELD] instanceof List) ? f[SERVER_FIELD][0]?.id    : f[SERVER_FIELD]?.id
if (!serverVal) { logger.error("Server field empty"); return }
if (serverVal == "PostgreSQL" || serverId == "POSTGRESQL-OPTION-ID") {
    logger.info("PostgreSQL detectado, saltando (gestion separada)")
    return
}

// 2d. Tipo de permiso y email
def permId   = f[PERM_FIELD]?.id ?: f[PERM_FIELD]?.first()?.id
def permType = (permId == WRITE_OPTION_ID) ? "Write" : (permId == READ_OPTION_ID) ? "Read" : null
if (!permType) { logger.error("Tipo de permiso desconocido: ${permId}"); return }

def email = f[EMAIL_FIELD] as String
if (!email) { logger.error("Email vacio"); return }

// 3. Resolver grupo destino
def grpInfo = (env == "PROD") ? sqlGroupMapProd[serverVal]?.get(permType) : devStgGroups[permType]
if (!grpInfo) { logger.error("No hay grupo mapeado para ${serverVal} (${permType}) en ${env}"); return }
def (groupName, groupId) = grpInfo

// 4. Obtener token Graph (credenciales desde ScriptRunner Secrets)
def tokenResp = Unirest.post("https://login.microsoftonline.com/${TENANTID}/oauth2/v2.0/token")
    .header("Content-Type","application/x-www-form-urlencoded")
    .body("grant_type=client_credentials&client_id=${CLIENTID}&client_secret=${CLIENTSECRET}&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default")
    .asString()
if (tokenResp.status != 200) { logger.error("Token error: ${tokenResp.body}"); return }
def token = new JsonSlurper().parseText(tokenResp.body).access_token

// 5. Agregar usuario al grupo de Azure AD via Graph API
def addResp = Unirest.post("https://graph.microsoft.com/v1.0/groups/${groupId}/members/\$ref")
    .header("Authorization","Bearer ${token}")
    .header("Content-Type","application/json")
    .body(JsonOutput.toJson(["@odata.id": "https://graph.microsoft.com/v1.0/users/${email}"]))
    .asString()

boolean success = false
switch (addResp.status) {
    case [201, 204]:
        logger.info("${email} added to ${groupName}"); success = true; break
    case 400:
        // "already exists" -> tratar como exito
        if (addResp.body?.contains("already exist")) {
            logger.info("${email} already in ${groupName}"); success = true
        } else {
            logger.error("Graph 400: ${addResp.body}")
        }
        break
    case 404:
        addComment(issueKey, "User ${email} was not found in Entra ID. Please verify the email address.")
        return
    default:
        logger.error("Graph error ${addResp.status}: ${addResp.body}")
        return
}

// 6. Transicionar y asignar al bot
if (success) {
    addComment(issueKey, "${email} was added to group **${groupName}** (${env}). Access granted.")
    def transResp = post("/rest/api/3/issue/${issueKey}/transitions")
        .header("Content-Type","application/json")
        .body(JsonOutput.toJson([
            transition: [id: TRANSITION_ID],
            fields:     [assignee: [id: BOT_ASSIGNEE_ID]]
        ]))
        .asString()
    if (transResp.status == 204) logger.info("Transicion ${TRANSITION_ID} OK para ${issueKey}")
}

// --- Helper ---
def addComment(String key, String text) {
    post("/rest/api/3/issue/${key}/comment")
        .header("Content-Type","application/json")
        .body([body: [type: "doc", version: 1,
               content: [[type: "paragraph", content: [[type: "text", text: text]]]]]])
        .asString()
}
