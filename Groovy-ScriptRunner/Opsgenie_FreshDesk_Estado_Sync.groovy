/**
 * Script Listener — ScriptRunner for Jira Cloud
 *
 * Sincronizacion bidireccional de estados entre FreshDesk y Jira Service Management.
 * Cuando un agente de FreshDesk comenta en el ticket de Jira con el nuevo estado,
 * este script detecta el estado, calcula la ruta de transicion necesaria y
 * ejecuta las transiciones encadenadas para llegar al estado objetivo en Jira.
 *
 * Complejidad destacada:
 *  - Maquina de estados completa: calcula el camino mas corto entre cualquier
 *    par de estados (Open, InProgress, Resolved, Rejected, Reopen)
 *  - Manejo dinamico de campos obligatorios: lee los metadatos de cada transicion
 *    para inyectar los campos requeridos y evitar errores "required field missing"
 *  - Deteccion de estados FreshDesk via normalizacion de texto (elimina acentos)
 *    para ser resiliente a variaciones de escritura (Canceled/Cancelado/Cancelada)
 *  - Inyeccion de comentario inline en la transicion final (evita doble llamada API)
 *  - Auto-asignacion si el ticket no tiene assignee (prerequisito para transicionar)
 */

import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import java.text.Normalizer
import groovy.transform.Field

def logger   = LoggerFactory.getLogger(this.class)
def issueKey = issue.key

// --- CONFIGURACION ---
@Field final String RT_ID_MONITORING  = "YOUR-REQUEST-TYPE-ID"   // ID del tipo de request en JSM
@Field final int    WINDOW_SECONDS    = 100_000                   // Ventana de busqueda en comentarios
@Field final String FD_AUTHOR         = "Integracion Freshdesk"  // Nombre del autor de comentarios FD
@Field final String DEFAULT_ASSIGNEE  = "YOUR-DEFAULT-ASSIGNEE-JIRA-ID"

// Resoluciones
@Field final String RES_RESOLVED_NAME = "Done"
@Field final String RES_RESOLVED_ID   = "10000"
@Field final String RES_REJECTED_NAME = "Known Error"
@Field final String RES_REJECTED_ID   = "10005"

// IDs de transiciones del workflow (ajustar segun tu proyecto)
@Field final int T_OPEN_TO_INPROG     = 11
@Field final int T_OPEN_TO_REJECT     = 191
@Field final int T_INPROG_TO_RESOLVE  = 41
@Field final int T_INPROG_TO_REJECT   = 191
@Field final int T_REJECT_TO_REOPEN   = 2
@Field final int T_RESOLVE_TO_REOPEN  = 61
@Field final int T_REOPEN_TO_INPROG   = 151

// --- HELPERS ---
def fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

String adfToText(adf) {
    if (!(adf instanceof Map) || adf.type != "doc" || !adf.content) return ""
    def sb = new StringBuilder()
    adf.content.each { block ->
        if (block.content) {
            block.content.each { n -> if (n.text) sb.append(n.text) }
            sb.append("\n")
        }
    }
    return sb.toString().trim()
}

String normalize(String s) {
    if (!s) return ""
    return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase()
}

boolean ensureAssignee(String issueId) {
    def resp = get("/rest/api/3/issue/${issueId}?fields=assignee").header("Content-Type","application/json").asString()
    if (resp.status != 200) return false
    if (new JsonSlurper().parseText(resp.body).fields?.assignee?.accountId) return true
    def put = put("/rest/api/3/issue/${issueId}/assignee")
        .header("Content-Type","application/json")
        .body([accountId: DEFAULT_ASSIGNEE])
        .asString()
    return put.status == 204
}

Map buildRequiredFields(String issueId, int transitionId) {
    // Lee los metadatos de la transicion y obtiene los valores actuales de
    // los campos requeridos para inyectarlos en el payload (evita errores 400)
    def meta = get("/rest/api/3/issue/${issueId}/transitions?expand=transitions.fields")
        .header("Content-Type","application/json").asString()
    if (meta.status != 200) return [:]
    def t = new JsonSlurper().parseText(meta.body).transitions.find { it.id == "${transitionId}" }
    if (!t?.fields) return [:]

    def requiredKeys = t.fields.findAll { k, v -> v?.required && k != "resolution" }*.key
    if (!requiredKeys) return [:]

    def issueResp = get("/rest/api/3/issue/${issueId}?fields=${requiredKeys.join(',')}")
        .header("Content-Type","application/json").asString()
    if (issueResp.status != 200) return [:]

    def fields = new JsonSlurper().parseText(issueResp.body).fields
    Map result = [:]
    requiredKeys.each { key ->
        result[key] = fields[key] ?: (key == "assignee" ? [accountId: DEFAULT_ASSIGNEE] : null)
    }
    return result.findAll { k, v -> v != null }
}

boolean doTransition(String issueId, int transitionId, Map extraFields = null, String inlineComment = null) {
    def payload = [transition: [id: "${transitionId}"]]
    if (extraFields) payload.fields = extraFields
    if (inlineComment) {
        payload.update = [comment: [[add: [body: [type: "doc", version: 1,
            content: [[type: "paragraph", content: [[type: "text", text: inlineComment]]]]]]]]]
    }
    def resp = post("/rest/api/3/issue/${issueId}/transitions")
        .header("Content-Type","application/json").body(payload).asString()
    if (resp.status == 204) { logger.info("Transition ${transitionId} OK -> ${issueId}"); return true }
    logger.error("Transition ${transitionId} failed: ${resp.status} ${resp.body}")
    return false
}

// --- 1. VALIDAR QUE EL TICKET ES DE TIPO MONITORING ---
def rtResp = get("/rest/api/3/issue/${issueKey}?fields=customfield_10010,status")
    .header("Content-Type","application/json").asString()
if (rtResp.status != 200) return

def rtJson        = new JsonSlurper().parseText(rtResp.body)
def rtId          = rtJson.fields?.customfield_10010?.requestType?.id
def currentStatus = (rtJson.fields?.status?.name ?: "").trim()

if (rtId != RT_ID_MONITORING) return
logger.info("Estado actual: '${currentStatus}'")

// --- 2. DETECTAR ULTIMO ESTADO DE FRESHDESK EN COMENTARIOS ---
def commResp = get("/rest/api/3/issue/${issueKey}/comment?orderBy=-created&maxResults=10")
    .header("Content-Type","application/json").asString()
if (commResp.status != 200) return

def cutoff  = System.currentTimeMillis() - (WINDOW_SECONDS * 1000L)
def fdState = null

(new JsonSlurper().parseText(commResp.body)?.comments ?: []).find { c ->
    try {
        if (fmt.parse(c.created).time < cutoff) return false
        if (c.author?.displayName != FD_AUTHOR) return false
        def text = normalize((c.body instanceof Map) ? adfToText(c.body) : c.body?.toString() ?: "")
        if      (text =~ /cancel/)  { fdState = "Canceled"; return true }
        else if (text =~ /resolv/)  { fdState = "Resolved"; return true }
        else if (text =~ /pending/) { fdState = "Pending";  return true }
        else if (text =~ /open|abiert/) { fdState = "Open"; return true }
        return false
    } catch (ignored) { false }
}
if (!fdState) return
logger.info("Estado FreshDesk detectado: ${fdState}")

// --- 3. RESOLVER ESTADO OBJETIVO EN JIRA ---
String target = switch (fdState) {
    case "Pending"  -> "In Progress"
    case "Resolved" -> "Resolved"
    case "Canceled" -> "Rejected"
    case "Open"     -> (currentStatus in ["Resolved","Rejected"]) ? "Reopen" : "Open"
    default         -> null
}
if (!target || currentStatus == target) return

ensureAssignee(issueKey)

// --- 4. CALCULAR RUTA DE TRANSICIONES ---
// Maquina de estados: calcula la secuencia necesaria para ir de currentStatus a target
List<Integer> path = []

switch (currentStatus) {
    case "Open":
        if      (target == "In Progress") path = [T_OPEN_TO_INPROG]
        else if (target == "Resolved")    path = [T_OPEN_TO_INPROG, T_INPROG_TO_RESOLVE]
        else if (target == "Rejected")    path = [T_OPEN_TO_REJECT]
        break
    case "In Progress":
        if      (target == "Resolved") path = [T_INPROG_TO_RESOLVE]
        else if (target == "Rejected") path = [T_INPROG_TO_REJECT]
        break
    case "Resolved":
        if      (target == "Reopen")     path = [T_RESOLVE_TO_REOPEN]
        else if (target == "In Progress") path = [T_RESOLVE_TO_REOPEN, T_REOPEN_TO_INPROG]
        else if (target == "Rejected")    path = [T_RESOLVE_TO_REOPEN, T_REOPEN_TO_INPROG, T_INPROG_TO_REJECT]
        break
    case "Rejected":
        if      (target == "Reopen")      path = [T_REJECT_TO_REOPEN]
        else if (target == "In Progress") path = [T_REJECT_TO_REOPEN, T_REOPEN_TO_INPROG]
        else if (target == "Resolved")    path = [T_REJECT_TO_REOPEN, T_REOPEN_TO_INPROG, T_INPROG_TO_RESOLVE]
        break
    case "Reopen":
        if      (target == "In Progress") path = [T_REOPEN_TO_INPROG]
        else if (target == "Resolved")    path = [T_REOPEN_TO_INPROG, T_INPROG_TO_RESOLVE]
        break
}
if (!path) return

// --- 5. EJECUTAR TRANSICIONES ---
path.eachWithIndex { tId, i ->
    boolean isLast    = (i == path.size() - 1)
    Map required      = buildRequiredFields(issueKey, tId)

    // En la ultima transicion: inyectar resolution si aplica
    if (isLast) {
        if (target == "Resolved") required.resolution = [name: RES_RESOLVED_NAME, id: RES_RESOLVED_ID]
        if (target == "Rejected") required.resolution = [name: RES_REJECTED_NAME, id: RES_REJECTED_ID]
    }

    String comment = (isLast && target == "Resolved") ? "FreshDesk ticket resolved. Closing Jira issue." : null
    if (!doTransition(issueKey, tId, required ?: null, comment)) return
}
