import os
import json
import base64
import asyncio
from fastapi import FastAPI, HTTPException, Request, Response, UploadFile, File, Form
from fastapi.responses import StreamingResponse, FileResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List, Dict, Any

import database as db
from model_manager import manager, MODEL_CONFIGS, PERSONAS

# Try importing PDF reader
try:
    import pymupdf  # PyMuPDF
    HAS_PYMUPDF = True
except Exception:
    HAS_PYMUPDF = False

app = FastAPI(title="K2 Horizon Chat")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Initialize database
db.init_db()

# Serve static files
app.mount("/static", StaticFiles(directory="static"), name="static")

class CreateChatRequest(BaseModel):
    model: str # "0.9b" or "7b"
    title: Optional[str] = "New Conversation"
    persona: Optional[str] = "default"

class UpdateChatRequest(BaseModel):
    title: Optional[str] = None
    persona: Optional[str] = None

class SendMessageRequest(BaseModel):
    content: str
    attachments: Optional[List[Dict[str, Any]]] = []

class EditMessageRequest(BaseModel):
    content: str
    attachments: Optional[List[Dict[str, Any]]] = []

@app.get("/")
async def root():
    return FileResponse("static/index.html")

@app.get("/api/status")
async def get_status():
    return manager.get_status()

@app.get("/api/chats")
async def list_chats():
    chats = db.get_chats()
    return {"chats": chats}

@app.post("/api/chats")
async def create_chat(payload: CreateChatRequest):
    if payload.model not in MODEL_CONFIGS:
        raise HTTPException(status_code=400, detail=f"Invalid model. Choose from: {list(MODEL_CONFIGS.keys())}")
    chat = db.create_chat(model=payload.model, title=payload.title or "New Conversation", persona=payload.persona or "default")
    return chat

@app.get("/api/chats/{chat_id}")
async def get_chat_detail(chat_id: str):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    messages = db.get_messages(chat_id)
    return {"chat": chat, "messages": messages}

@app.patch("/api/chats/{chat_id}")
async def update_chat(chat_id: str, payload: UpdateChatRequest):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    if payload.title is not None:
        db.update_chat_title(chat_id, payload.title)
    if payload.persona is not None:
        db.update_chat_persona(chat_id, payload.persona)
    return {"status": "ok", "chat": db.get_chat(chat_id)}

@app.delete("/api/chats/{chat_id}")
async def delete_chat(chat_id: str):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    db.delete_chat(chat_id)
    return {"status": "deleted"}

@app.post("/api/upload")
async def upload_document(file: UploadFile = File(...)):
    """Extract text from uploaded documents (PDF, TXT, DOCX, Code, CSV, etc.) or process images"""
    try:
        filename = file.filename or "uploaded_file"
        ext = os.path.splitext(filename)[1].lower()
        contents = await file.read()
        size_kb = round(len(contents) / 1024, 1)

        extracted_text = ""
        file_type = "file"
        preview_data = None

        if ext == ".pdf":
            file_type = "pdf"
            if HAS_PYMUPDF:
                doc = pymupdf.open(stream=contents, filetype="pdf")
                pages_text = []
                for idx, page in enumerate(doc):
                    if idx >= 20: # cap at first 20 pages
                        pages_text.append(f"\n[... truncated {len(doc) - 20} more pages ...]")
                        break
                    p_txt = page.get_text().strip()
                    if p_txt:
                        pages_text.append(f"[Page {idx+1}]\n{p_txt}")
                extracted_text = "\n\n".join(pages_text)
                if not extracted_text:
                    extracted_text = "(PDF contains scanned images or non-selectable text)"
            else:
                extracted_text = f"(PDF reader not initialized for {filename})"

        elif ext in [".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif"]:
            file_type = "image"
            b64_img = base64.b64encode(contents).decode("utf-8")
            mime = "image/png" if ext == ".png" else "image/jpeg"
            preview_data = f"data:{mime};base64,{b64_img}"
            extracted_text = f"[Image Attached: {filename} ({size_kb} KB)]"

        else:
            file_type = "code" if ext in [".py", ".js", ".ts", ".html", ".css", ".cpp", ".c", ".rs", ".go", ".java", ".sql", ".sh", ".json", ".yaml", ".yml"] else "text"
            try:
                extracted_text = contents.decode("utf-8", errors="replace")
                if len(extracted_text) > 20000:
                    extracted_text = extracted_text[:20000] + "\n\n[... Truncated due to size limit ...]"
            except Exception as err:
                extracted_text = f"Error reading text content: {err}"

        return {
            "name": filename,
            "type": file_type,
            "size_kb": size_kb,
            "text": extracted_text,
            "preview": preview_data
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to process upload: {str(e)}")

@app.post("/api/chats/{chat_id}/message")
async def send_message(chat_id: str, payload: SendMessageRequest):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    if not payload.content.strip() and not payload.attachments:
        raise HTTPException(status_code=400, detail="Empty message")

    user_msg = db.add_message(
        chat_id=chat_id,
        role="user",
        content=payload.content.strip() or "[Sent attachment]",
        attachments=payload.attachments or []
    )
    
    # Auto-generate title if this is the first message
    messages = db.get_messages(chat_id)
    if len(messages) <= 1:
        auto_title = payload.content.strip()[:35] + ("..." if len(payload.content.strip()) > 35 else "")
        if not auto_title and payload.attachments:
            auto_title = f"Document: {payload.attachments[0].get('name', 'File')}"
        db.update_chat_title(chat_id, auto_title or "New Conversation")

    return {"message": user_msg}

@app.post("/api/chats/{chat_id}/abort")
async def abort_chat_stream(chat_id: str):
    """Abort running inference stream"""
    manager.abort_stream(chat_id)
    return {"status": "aborted"}

@app.post("/api/chats/{chat_id}/regenerate/{message_id}")
async def regenerate_from_message(chat_id: str, message_id: str):
    """Delete messages starting from assistant response and re-run"""
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    db.delete_messages_after(chat_id, message_id)
    return {"status": "ok"}

@app.get("/api/chats/{chat_id}/export")
async def export_chat(chat_id: str, format: str = "markdown"):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")
    messages = db.get_messages(chat_id)

    if format == "json":
        return {"chat": chat, "messages": messages}
    
    # Format markdown
    md_lines = [
        f"# {chat.title}",
        f"*Model: {chat['model']} | Created: {chat['created_at']}*",
        "\n---\n"
    ]
    for m in messages:
        role_title = "👤 User" if m["role"] == "user" else "🤖 K2 Horizon"
        md_lines.append(f"### {role_title}\n")
        if m.get("thinking"):
            md_lines.append(f"> **Thought Process**:\n> {m['thinking'].replace(chr(10), chr(10)+'> ')}\n")
        md_lines.append(m["content"] + "\n")
        if m.get("metrics") and m["metrics"].get("tok_per_sec"):
            met = m["metrics"]
            md_lines.append(f"*\\[Latency: {met.get('ttft_ms',0)}ms | Speed: {met.get('tok_per_sec',0)} tok/s\\]*\n")
        md_lines.append("\n---\n")

    md_content = "\n".join(md_lines)
    return PlainTextResponse(
        md_content,
        headers={"Content-Disposition": f'attachment; filename="k2_chat_{chat_id[:8]}.md"'}
    )

@app.get("/api/chats/{chat_id}/stream")
async def stream_chat_response(chat_id: str):
    chat = db.get_chat(chat_id)
    if not chat:
        raise HTTPException(status_code=404, detail="Chat not found")

    messages = db.get_messages(chat_id)
    if not messages:
        raise HTTPException(status_code=400, detail="No messages in conversation")

    persona = chat.get("persona", "default") or "default"

    async def event_generator():
        thinking_text = ""
        answer_text = ""
        final_metrics = {}

        try:
            async for sse_event in manager.stream_chat(model_key=chat["model"], messages=messages, chat_id=chat_id, persona=persona):
                yield sse_event

                # Intercept metrics to persist on completion
                if sse_event.startswith("event: metrics"):
                    lines = sse_event.strip().split("\n")
                    for line in lines:
                        if line.startswith("data: "):
                            try:
                                data = json.loads(line[6:])
                                final_metrics = data
                                thinking_text = data.get("full_thinking", "")
                                answer_text = data.get("full_answer", "")
                            except Exception:
                                pass

            # Save assistant message to database
            if answer_text or thinking_text:
                db.add_message(
                    chat_id=chat_id,
                    role="assistant",
                    content=answer_text or "*(Response completed with reasoning)*",
                    thinking=thinking_text,
                    metrics=final_metrics
                )
        except Exception as e:
            error_data = json.dumps({"error": str(e)})
            yield f"event: error\ndata: {error_data}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

if __name__ == "__main__":
    import uvicorn
    print("\n" + "="*60)
    print(" [K2 Horizon] ChatGPT-Style Web Server Running")
    print(" Access UI at: http://localhost:8000")
    print("="*60 + "\n")
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=False)

