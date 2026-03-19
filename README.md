# IT Automation Portfolio

Scripts de automatizacion para entornos empresariales desarrollados y ejecutados en produccion.
Cubren gestion de identidades en Microsoft 365/Azure, migracion de HRIS, integracion entre
plataformas de ticketing y despliegues serverless en Azure Functions.

> Los scripts son versiones anonimizadas de automatizaciones reales.
> IDs, credenciales y nombres de infraestructura han sido reemplazados por placeholders.

---

## Stack

| Area | Tecnologias |
|---|---|
| Identidades & M365 | PowerShell, Microsoft Graph API (v1/beta), Entra ID, Exchange Online |
| Cloud & Serverless | Azure Functions (Python v2, PowerShell), Azure SQL, Azure Logic Apps |
| Privileged Access | Azure PIM, Managed Identities, App Registrations, RBAC |
| Ticketing & ITSM | Groovy, ScriptRunner for Jira Cloud, Jira REST API v3, FreshDesk API v2 |
| HRIS Integration | HiBob API, BambooHR API, pandas, openpyxl |
| Orquestacion | n8n, Webhooks, Azure Logic Apps, Power Automate |
| Herramientas | Git, VS Code, Exchange Online Management, SharePoint PnP |

---

## Scripts

### Python

#### [`Migracion_BambooHR_HiBob/migracion_masiva.py`](Python/Migracion_BambooHR_HiBob/migracion_masiva.py)

Migracion masiva de documentos de empleados desde BambooHR a HiBob.

**Que hace:**
- Recibe un Excel con `bamboo_employee_id` + `hibob_email`
- Busca a cada empleado en HiBob via POST search con doble condicion (root.email / work.email)
- Descarga el catalogo de documentos de BambooHR y filtra por `CATEGORY_MAP`
- Para cada archivo, ejecuta un **pipeline de sanitizacion PDF en cascada**:
  pikepdf (normal) → pikepdf (linearizado) → PyPDF2 → PyMuPDF, probando cada
  resultado con nombre original/sanitizado y content-type `application/pdf`/`octet-stream`
  hasta que HiBob acepta el archivo
- Redirige archivos >2MB en carpetas custom al endpoint `/shared/upload`
- Todos los requests tienen **backoff exponencial** (base 1.5) con reintentos en 429/5xx
- Genera reporte Excel de 3 hojas: `resultado` / `errores` / `meta`

**Tecnologias:** Python, BambooHR REST API, HiBob REST API, pandas, pikepdf, PyPDF2, PyMuPDF, requests

---

#### [`Azure-Functions/n8n_log_ingest/function_app.py`](Python/Azure-Functions/n8n_log_ingest/function_app.py)

Azure Function HTTP Trigger que recibe eventos de ejecucion de n8n y los persiste en Azure SQL.

**Que hace:**
- Recibe el webhook de n8n (`workflow.success` / `workflow.error`)
- Extrae `executionId`, `workflowName`, `status`, `timestamp`
- Persiste en `dbo.N8n_ExecutionLogs` usando **Azure Functions SQL output binding**
  (sin escribir codigo de conexion — el binding maneja la insercion declarativamente)
- Guarda el JSON completo en columna `RawJson` para debugging

**Tecnologias:** Python, Azure Functions v2, Azure SQL, n8n webhook

---

### PowerShell — Azure Functions

#### [`Azure-Functions/Reporte_Last_Login/run.ps1`](PowerShell/Azure-Functions/Reporte_Last_Login/run.ps1)

Azure Function HTTP Trigger que genera un reporte de ultimo login de todos los usuarios del tenant.

**Que hace:**
- Autentica con OAuth2 App-Only (client credentials) contra Graph API
- Pagina automaticamente sobre `/beta/users` con `signInActivity` + `assignedLicenses` + `provisionedPlans`
- Clasifica cada usuario en 3 categorias con reglas configurables:
  **Cuenta Externa** (UPN con `#EXT#`) / **Usuario** (tiene licencia) / **Buzon Compartido** (tiene Exchange plan)
- Exporta a Excel con `ImportExcel` (AutoSize, AutoFilter, TableStyle)
- Retorna el archivo **base64-encoded en el body JSON** para consumo directo desde n8n

**Tecnologias:** PowerShell, Microsoft Graph API beta, Azure Functions HTTP binding, ImportExcel

---

#### [`Azure-Functions/PowerBI_Monthly_Audit/run.ps1`](PowerShell/Azure-Functions/PowerBI_Monthly_Audit/run.ps1)

Azure Function Timer Trigger que exporta los logs de auditoria de Power BI del mes anterior.

**Que hace:**
- Se ejecuta automaticamente el primero de cada mes
- Divide el mes en **bloques de 10 dias** para sortear el limite de 5000 filas
  por llamada de `Search-UnifiedAuditLog`
- Filtra por `RecordType PowerBIAudit` y operaciones `ViewDashboard`/`ViewReport`
- Exporta el resultado consolidado a CSV

**Tecnologias:** PowerShell, Exchange Online Management, Microsoft 365 Unified Audit Log, Azure Functions Timer

---

### PowerShell — Entra ID / M365

#### [`Entra-ID/Offboarding_Usuario_Entra.ps1`](PowerShell/Entra-ID/Offboarding_Usuario_Entra.ps1)

Automatizacion completa del proceso de baja de un usuario (offboarding) en Entra ID.

**Que hace en una sola ejecucion:**
restablece contrasena aleatoria → revoca sesiones activas → limpia extension attributes 1-15 →
remueve manager → elimina de todos los grupos (con excepcion configurable) →
borra atributos estandar → retira todas las licencias → deshabilita la cuenta →
imprime reporte de verificacion final

**Tecnologias:** PowerShell, Microsoft Graph REST API, App-Only authentication

---

#### [`Entra-ID/Agregar_Miembros_Grupo_Masivo.ps1`](PowerShell/Entra-ID/Agregar_Miembros_Grupo_Masivo.ps1)

Carga masiva de usuarios a un grupo de Entra ID desde Excel.

**Que hace:** Lee emails desde Excel → resuelve cada usuario en Graph API →
detecta miembros existentes (evita duplicados) → genera transcript de consola +
reporte CSV con status por fila (Agregado / Ya estaba / No encontrado / Error)

**Tecnologias:** PowerShell, Microsoft Graph SDK (Mg modules), ImportExcel

---

### Groovy — ScriptRunner for Jira Cloud

#### [`Opsgenie_FreshDesk_Estado_Sync.groovy`](Groovy-ScriptRunner/Opsgenie_FreshDesk_Estado_Sync.groovy)

Motor de sincronizacion bidireccional de estados entre FreshDesk y Jira Service Management.

**Que hace:**
- Detecta comentarios del agente de FreshDesk en el ticket de Jira
- Normaliza el texto (elimina acentos) para detectar el estado: Canceled/Resolved/Pending/Open
- **Calcula la ruta optima de transiciones** a traves de la maquina de estados del workflow
  (p.ej. Resolved → Rejected requiere: Resolved → Reopen → InProgress → Reject)
- Para cada transicion intermedia: consulta los metadatos de la transicion, lee los valores
  actuales de los campos requeridos y los inyecta en el payload (evita errores 400)
- En la transicion final: inyecta la `resolution` correcta y un comentario inline
- Auto-asigna el ticket si no tiene assignee (prerequisito para transicionar en JSM)

**Tecnologias:** Groovy, ScriptRunner DSL, Jira REST API v3, Java Normalizer, ADF parsing

---

#### [`IAM_Provisioning_SQL_DB.groovy`](Groovy-ScriptRunner/IAM_Provisioning_SQL_DB.groovy)

IAM Self-Service: provisionamiento automatico de acceso a Azure SQL desde Jira.

**Que hace:**
- Se activa cuando un ticket de "Acceso a Base de Datos" transiciona a "Open"
- Lee: entorno (PROD/DEV/STG), servidor SQL, email del usuario, tipo de permiso (Read/Write), fecha
- Si la fecha es futura: deja comentario y sale sin actuar
- Resuelve el **grupo de Azure AD** correcto del mapa de 7 servidores x 2 permisos = 14 grupos
- Omite PostgreSQL (gestion separada)
- Obtiene token OAuth2 App-Only y llama a Graph API para agregar al usuario al grupo
- Maneja "already exists" como exito
- Si el usuario no existe en Entra ID: comenta en el ticket con el error
- Transiciona y asigna al bot de automatizacion

**Tecnologias:** Groovy, ScriptRunner DSL + Secrets, Jira REST API v3, Microsoft Graph API

---

#### [`Automatizar_PIM.groovy`](Groovy-ScriptRunner/Automatizar_PIM.groovy)

Activacion de roles PIM (Privileged Identity Management) desde un ticket de Jira.

**Que hace:**
- Detecta transicion de "Esperando implementacion" a "Implementando" (ventana de 5s)
- Lee campos de rol, fecha inicio y fecha fin del ticket
- Calcula la duracion en horas con `RoundingMode.CEILING` usando `java.time.Duration`
- Dispara un Azure Logic App / Power Automate con el payload estructurado
- La Logic App activa el rol PIM en Entra ID para la ventana calculada

**Tecnologias:** Groovy, ScriptRunner DSL, Jira REST API v3, Azure Logic Apps, java.time

---

## Otras automatizaciones (no publicadas)

- ~210 Script Listeners en ScriptRunner cubriendo ITSM, Change Management,
  Access Management, Onboarding de proveedores, integracion con OpsGenie y FreshDesk
- Integracion bidireccional Jira <-> FreshDesk para tickets de Monitoring y Merchant Support
- Migracion BambooHR → HiBob con mapeo de 12 categorias de documentos
- Azure Functions para exportacion de auditoria Power BI y reporteria de sign-in
- Scripts de auditoria y gestion de Entra ID: ciclo de vida de usuarios, PIM, RBAC,
  App Registrations, SharePoint, Exchange Online, licencias

---

## Contacto

**GitHub:** [github.com/xelomax](https://github.com/xelomax)
