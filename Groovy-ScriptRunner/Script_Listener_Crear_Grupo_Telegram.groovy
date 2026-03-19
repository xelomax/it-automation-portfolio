/**
 * Script Listener — ScriptRunner for Jira Cloud
 *
 * Detecta la creación/actualización de un ticket con tipo de issue específico
 * y campo personalizado "Telegram group" seleccionado.
 * Cuando las condiciones se cumplen:
 *   1. Actualiza el summary y assignee del ticket automáticamente
 *   2. Ejecuta la transición al estado "In Progress"
 *   3. Asigna aprobadores al campo de aprobación y transiciona al estado de aprobación
 *
 * Integración: este listener dispara el flujo que eventualmente llama a la
 * Azure Function de creación de grupos Telegram cuando el ticket es aprobado.
 */

import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper

def logger = LoggerFactory.getLogger(this.class)
def issueKey = issue.key

// ─── 1. Obtener datos del issue ───────────────────────────────────────────────
def issueResponse = get("/rest/api/3/issue/${issueKey}?fields=issuetype,customfield_XXXXX")
    .header('Content-Type', 'application/json')
    .asString()

if (issueResponse.status != 200) {
    logger.error("Error obteniendo issue ${issueKey}. Status: ${issueResponse.status}")
    return
}

def json          = new JsonSlurper().parseText(issueResponse.body)
def issueTypeId   = json.fields.issuetype?.id
def customField   = json.fields.customfield_XXXXX
def customFieldId = customField?.id

// ─── 2. Evaluar condiciones ───────────────────────────────────────────────────
// Solo actuar cuando: tipo de issue = "Access Request" Y campo = "Telegram group"
if (issueTypeId == "YOUR-ISSUE-TYPE-ID" && customFieldId == "YOUR-CUSTOM-FIELD-OPTION-ID") {

    logger.info("Condiciones cumplidas para ${issueKey} — iniciando flujo Telegram")

    // A. Actualizar summary y assignee
    def updateResp = put("/rest/api/3/issue/${issueKey}")
        .header("Content-Type", "application/json")
        .body([
            fields: [
                summary : "New Telegram group",
                assignee: [ accountId: "YOUR-ASSIGNEE-ACCOUNT-ID" ]
            ]
        ])
        .asString()

    if (updateResp.status != 204) {
        logger.error("Error actualizando issue. Status: ${updateResp.status}")
        return
    }
    logger.info("Summary y assignee actualizados en ${issueKey}")

    // B. Transición a "In Progress"
    def transitionInProgress = post("/rest/api/3/issue/${issueKey}/transitions")
        .header("Content-Type", "application/json")
        .body([ transition: [ id: "YOUR-IN-PROGRESS-TRANSITION-ID" ] ])
        .asString()

    if (transitionInProgress.status != 204) {
        logger.error("Error ejecutando transición In Progress. Status: ${transitionInProgress.status}")
        return
    }
    logger.info("Transición a In Progress ejecutada")

    // C. Asignar aprobadores y transicionar a estado de aprobación
    def approvers = [
        [ accountId: "YOUR-APPROVER-1-ACCOUNT-ID" ],
        [ accountId: "YOUR-APPROVER-2-ACCOUNT-ID" ]
    ]

    def transitionApproval = post("/rest/api/3/issue/${issueKey}/transitions")
        .header("Content-Type", "application/json")
        .body([
            transition: [ id: "YOUR-APPROVAL-TRANSITION-ID" ],
            fields    : [ customfield_APPROVAL: approvers ]
        ])
        .asString()

    if (transitionApproval.status != 204) {
        logger.error("Error asignando aprobadores. Status: ${transitionApproval.status}")
        return
    }
    logger.info("Aprobadores asignados y transición de aprobación ejecutada en ${issueKey}")

} else {
    logger.info("Condiciones no cumplidas para ${issueKey} — issuetype=${issueTypeId}, customField.id=${customFieldId}")
}
