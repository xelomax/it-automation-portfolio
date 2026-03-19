import json
import os
import logging
import azure.functions as func
import asyncio

from telethon import TelegramClient
from telethon.sessions import StringSession
from telethon.tl.functions.channels import CreateChannelRequest, InviteToChannelRequest, EditAdminRequest
from telethon.tl.types import ChatAdminRights
from telethon.tl.functions.messages import ExportChatInviteRequest

logging.getLogger().setLevel(logging.INFO)

app = func.FunctionApp(http_auth_level=func.AuthLevel.FUNCTION)

# ─── CONFIGURACIÓN FIJA ───────────────────────────────────────────────────────
# Administradores humanos con permisos completos (configurar en Application Settings o aquí)
HUMAN_ADMINS_FIJOS = [
    "@admin1",
    "@admin2",
]

BOT_ADMIN_USERNAME = "@YourCompanyBot"
DEFAULT_ABOUT = "Grupo generado automáticamente."

HUMAN_ADMIN_RIGHTS = ChatAdminRights(
    change_info=True, post_messages=True, edit_messages=True, delete_messages=True,
    ban_users=True, invite_users=True, pin_messages=True, add_admins=False,
    anonymous=False, manage_call=True, manage_topics=True,
)

BOT_ADMIN_RIGHTS = ChatAdminRights(
    change_info=False, post_messages=False, edit_messages=False,
    delete_messages=True,   # Mínimo necesario para que Telegram acepte el rol
    ban_users=False, invite_users=True,
    pin_messages=False, add_admins=False,
    anonymous=False, manage_call=False, manage_topics=False,
)

# ─── Variables de entorno (Application Settings en Azure) ─────────────────────
API_ID   = int(os.environ["TG_API_ID"])
API_HASH = os.environ["TG_API_HASH"]
SESSION  = os.environ["TG_SESSION"]   # String Session generada con Telethon


async def promote_fixed_admins(client, chat):
    """Invita y promueve a los administradores configurados."""
    for username in HUMAN_ADMINS_FIJOS + [BOT_ADMIN_USERNAME]:
        uname    = username.replace('@', '').strip()
        is_bot   = (username == BOT_ADMIN_USERNAME)
        rights   = BOT_ADMIN_RIGHTS if is_bot else HUMAN_ADMIN_RIGHTS
        rank     = 'Bot' if is_bot else 'Admin'
        try:
            entity = await client.get_entity(uname)
            try:
                await client(InviteToChannelRequest(chat, [entity]))
                await asyncio.sleep(2)
            except Exception as e:
                logging.warning(f"Invite warning for {username}: {e}")
            await client(EditAdminRequest(chat, entity, rights, rank=rank))
            logging.info(f"Promoted {username} as {rank}")
        except Exception as e:
            logging.error(f"Failed to process {username}: {e}")


@app.route(route="Telegram_Group", methods=["POST"])
async def telegram_group(req: func.HttpRequest) -> func.HttpResponse:
    """
    HTTP Trigger — Crea un supergrupo de Telegram.

    Body esperado (JSON):
        {
            "title": "Nombre del grupo",
            "about": "Descripción opcional"   (opcional)
        }

    Respuesta:
        {
            "ok": true,
            "title": "Nombre del grupo",
            "chat_id": -1001234567890,
            "invite_link": "https://t.me/+xxxx"
        }
    """
    try:
        data = req.get_json()
    except ValueError:
        return func.HttpResponse("Invalid JSON.", status_code=400)

    title = data.get("title")
    if not title:
        return func.HttpResponse("Missing 'title'.", status_code=400)

    about  = data.get("about", DEFAULT_ABOUT)
    client = TelegramClient(StringSession(SESSION), API_ID, API_HASH)

    try:
        await client.connect()
        if not await client.is_user_authorized():
            return func.HttpResponse("Telegram session not authorized.", status_code=500)

        # Crear supergrupo
        result = await client(CreateChannelRequest(title=title, about=about, megagroup=True))
        chat   = result.chats[0]
        logging.info(f"Supergroup created: {chat.title} (ID: {chat.id})")

        # Invitar y promover admins
        await promote_fixed_admins(client, chat)

        # Generar link de invitación
        invite_link = None
        try:
            invite      = await client(ExportChatInviteRequest(chat))
            invite_link = invite.link
        except Exception as e:
            logging.warning(f"Could not generate invite link: {e}")

        await client.disconnect()

        return func.HttpResponse(
            json.dumps({"ok": True, "title": chat.title, "chat_id": chat.id, "invite_link": invite_link}),
            status_code=200,
            mimetype="application/json"
        )

    except Exception as e:
        logging.error(f"Unexpected error: {e}", exc_info=True)
        return func.HttpResponse(f"Server error: {e}", status_code=500)
    finally:
        if client.is_connected():
            await client.disconnect()
