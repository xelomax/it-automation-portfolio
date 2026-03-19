import azure.functions as func
import logging
import json
import datetime
from azure.functions.decorators.core import DataType

app = func.FunctionApp()

@app.function_name(name="IngestN8nLogs")
@app.route(route="IngestN8nLogs", auth_level=func.AuthLevel.FUNCTION)
@app.generic_output_binding(
    arg_name="logEntry",
    type="sql",
    CommandText="dbo.N8n_ExecutionLogs",
    ConnectionStringSetting="SqlConnectionString",   # App Setting en Azure
    data_type=DataType.STRING
)
def ingest_n8n_log(req: func.HttpRequest, logEntry: func.Out[func.SqlRow]) -> func.HttpResponse:
    """
    HTTP Trigger que recibe eventos de ejecucion de n8n via webhook
    y los persiste en Azure SQL usando output binding.

    Payload esperado de n8n:
    {
        "eventName": "workflow.success" | "workflow.error",
        "ts": "2024-01-15T10:30:00.000Z",
        "payload": {
            "executionId": "12345",
            "workflowName": "My Workflow"
        }
    }
    """
    logging.info("n8n log received")

    try:
        body = req.get_json()
    except ValueError:
        return func.HttpResponse("Invalid JSON", status_code=400)

    payload  = body.get("payload", {})
    exec_id  = payload.get("executionId") or "MISSING_ID"
    wf_name  = payload.get("workflowName") or "Unknown"
    status   = body.get("eventName", "unknown")
    ts       = body.get("ts")

    logEntry.set(func.SqlRow({
        "ExecutionId":  str(exec_id),
        "WorkflowName": str(wf_name),
        "Status":       status,
        "StartTime":    ts or datetime.datetime.utcnow().isoformat(),
        "DurationMs":   0,
        "RawJson":      json.dumps(body),
        "InsertedAt":   datetime.datetime.utcnow().isoformat(),
    }))

    logging.info(f"Saved: exec={exec_id} wf={wf_name} status={status}")
    return func.HttpResponse(f"OK: {exec_id}", status_code=200)
