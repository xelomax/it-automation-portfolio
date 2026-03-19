# IT Automation Portfolio

Scripts de automatización para entornos empresariales con **Microsoft 365, Azure, Jira Cloud y Python**.  
Desarrollados y utilizados en producción para gestión de identidades, integración de plataformas y automatización de procesos IT.

> Los scripts aquí presentados son versiones anonimizadas de automatizaciones reales. Los IDs, credenciales y datos específicos han sido reemplazados por placeholders genéricos.

---

## Sobre mí

IT Engineer especializado en automatización de infraestructura, gestión de identidades en entornos Microsoft 365/Azure y automatización de procesos en Jira Cloud mediante ScriptRunner.

**Stack principal:**

| Área | Tecnologías |
|------|------------|
| Identidades & M365 | PowerShell, Microsoft Graph API, Entra ID (Azure AD) |
| Cloud & Serverless | Azure Functions, Python |
| Ticketing & Workflows | Groovy (ScriptRunner), Jira Cloud REST API |
| HRIS Integrations | HiBob API, BambooHR API |
| Automatización | n8n, Telegram Bot API, Webhooks |
| Herramientas | Git, VS Code, Exchange Online, SharePoint |

---

## Scripts incluidos

### PowerShell — Microsoft 365 / Entra ID

#### `Offboarding_Usuario_Entra.ps1`
Automatización completa del proceso de baja de un usuario en Entra ID (Azure AD):
- Restablece la contraseña a un valor aleatorio seguro
- Revoca todas las sesiones activas
- Elimina al usuario de todos los grupos (excepto grupos base configurados)
- Limpia atributos estándar y personalizados (extensionAttributes 1–15)
- Remueve el manager asignado
- Retira todas las licencias asignadas
- Deshabilita la cuenta
- Genera un reporte de verificación final en consola

**Tecnologías:** PowerShell + Microsoft Graph API (App-Only authentication)

---

#### `Agregar_Miembros_Grupo_Masivo.ps1`
Carga masiva de usuarios a un grupo de Entra ID desde un archivo Excel:
- Lee una columna de emails desde Excel
- Resuelve cada usuario en Graph API
- Detecta si ya es miembro (evita duplicados)
- Genera transcript de consola y reporte CSV con resultados detallados (Agregado / Ya estaba / No encontrado / Error)

**Tecnologías:** PowerShell + Microsoft Graph API + ImportExcel

---

#### `Reporte_Ultimo_Login.ps1`
Reporte de último inicio de sesión de todos los usuarios del tenant:
- Conecta a Graph API via Microsoft Graph PowerShell SDK
- Pagina automáticamente sobre todos los usuarios
- Exporta a Excel con columnas: email, última fecha de login, estado (Activo/Deshabilitado)

**Tecnologías:** PowerShell + Microsoft Graph Beta API + ImportExcel

---

### Python — Azure Functions

#### `Telegram_Group_Creator/function_app.py`
Azure Function HTTP trigger que crea automáticamente un supergrupo de Telegram:
- Recibe `title` y `about` via JSON en el body del request
- Crea el supergrupo usando Telethon (MTProto)
- Invita y promueve automáticamente a administradores fijos configurados
- Promueve al bot con permisos mínimos necesarios
- Retorna el `chat_id` y el link de invitación en la respuesta

Integrado con Jira Cloud via ScriptRunner: cuando se aprueba un ticket de tipo "New Telegram Group", se dispara esta Function automáticamente.

**Tecnologías:** Python, Azure Functions, Telethon, Telegram MTProto API

---

### Groovy — ScriptRunner (Jira Cloud)

#### `Script_Listener_Crear_Grupo_Telegram.groovy`
Script Listener de ScriptRunner que detecta la creación de un ticket de tipo específico en Jira Cloud y orquesta el flujo completo:
- Valida el tipo de issue y el campo personalizado
- Actualiza el summary del ticket automáticamente
- Ejecuta transiciones de workflow
- Asigna aprobadores al campo de aprobación
- Todo en respuesta a un evento de creación/actualización de issue

**Tecnologías:** Groovy, ScriptRunner for Jira Cloud, Jira REST API v3

---

## Estructura del repositorio

```
it-automation-portfolio/
├── PowerShell/
│   ├── Offboarding_Usuario_Entra.ps1
│   ├── Agregar_Miembros_Grupo_Masivo.ps1
│   └── Reporte_Ultimo_Login.ps1
├── Python-Azure-Functions/
│   └── Telegram_Group_Creator/
│       └── function_app.py
└── Groovy-ScriptRunner/
    └── Script_Listener_Crear_Grupo_Telegram.groovy
```

---

## Otros proyectos / automatizaciones (no publicados)

Adicionalmente a los scripts de este portafolio, he desarrollado:

- **~210 Script Listeners** en ScriptRunner para Jira Cloud cubriendo flujos de ITSM, Change Management, Access Management, Onboarding de proveedores y más
- **Migracion masiva BambooHR → HiBob** con mapeo de campos y reporte de errores
- **Azure Functions** para exportación mensual de auditoría de Power BI a SharePoint
- **Integraciones n8n** con Azure SQL para ingesta de logs de ejecución
- Scripts de auditoría y reportería sobre Entra ID, Exchange Online y SharePoint

---

## Contacto

- **GitHub:** [github.com/xelomax](https://github.com/xelomax)
