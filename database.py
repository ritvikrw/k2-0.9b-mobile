import sqlite3
import json
import uuid
import time
from typing import List, Dict, Optional

DB_PATH = "k2_chat.db"

def get_db():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS chats (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                model TEXT NOT NULL, -- '0.9b' or '7b'
                persona TEXT DEFAULT 'default',
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            )
        """)
        try:
            conn.execute("ALTER TABLE chats ADD COLUMN persona TEXT DEFAULT 'default'")
        except Exception:
            pass

        conn.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                thinking TEXT DEFAULT '',
                attachments TEXT DEFAULT '[]',
                metrics TEXT DEFAULT '{}',
                created_at REAL NOT NULL,
                FOREIGN KEY (chat_id) REFERENCES chats (id) ON DELETE CASCADE
            )
        """)
        try:
            conn.execute("ALTER TABLE messages ADD COLUMN attachments TEXT DEFAULT '[]'")
        except Exception:
            pass

        conn.commit()

def create_chat(model: str, title: str = "New Chat", persona: str = "default") -> Dict:
    chat_id = str(uuid.uuid4())
    now = time.time()
    with get_db() as conn:
        conn.execute(
            "INSERT INTO chats (id, title, model, persona, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            (chat_id, title, model, persona, now, now)
        )
        conn.commit()
    return {"id": chat_id, "title": title, "model": model, "persona": persona, "created_at": now, "updated_at": now}

def get_chats() -> List[Dict]:
    with get_db() as conn:
        rows = conn.execute("SELECT * FROM chats ORDER BY updated_at DESC").fetchall()
        return [dict(r) for r in rows]

def get_chat(chat_id: str) -> Optional[Dict]:
    with get_db() as conn:
        row = conn.execute("SELECT * FROM chats WHERE id = ?", (chat_id,)).fetchone()
        return dict(row) if row else None

def update_chat_title(chat_id: str, title: str):
    with get_db() as conn:
        conn.execute("UPDATE chats SET title = ?, updated_at = ? WHERE id = ?", (title, time.time(), chat_id))
        conn.commit()

def update_chat_persona(chat_id: str, persona: str):
    with get_db() as conn:
        conn.execute("UPDATE chats SET persona = ?, updated_at = ? WHERE id = ?", (persona, time.time(), chat_id))
        conn.commit()

def delete_chat(chat_id: str):
    with get_db() as conn:
        conn.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))
        conn.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
        conn.commit()

def add_message(chat_id: str, role: str, content: str, thinking: str = "", attachments: List = None, metrics: Dict = None) -> Dict:
    msg_id = str(uuid.uuid4())
    now = time.time()
    metrics_json = json.dumps(metrics or {})
    attachments_json = json.dumps(attachments or [])
    with get_db() as conn:
        conn.execute(
            "INSERT INTO messages (id, chat_id, role, content, thinking, attachments, metrics, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (msg_id, chat_id, role, content, thinking, attachments_json, metrics_json, now)
        )
        conn.execute("UPDATE chats SET updated_at = ? WHERE id = ?", (now, chat_id))
        conn.commit()
    return {
        "id": msg_id,
        "chat_id": chat_id,
        "role": role,
        "content": content,
        "thinking": thinking,
        "attachments": attachments or [],
        "metrics": metrics or {},
        "created_at": now
    }

def delete_messages_after(chat_id: str, message_id: str):
    """Delete message and all subsequent messages in chat (for editing/regeneration)"""
    with get_db() as conn:
        row = conn.execute("SELECT created_at FROM messages WHERE id = ?", (message_id,)).fetchone()
        if row:
            created_at = row["created_at"]
            conn.execute("DELETE FROM messages WHERE chat_id = ? AND created_at >= ?", (chat_id, created_at))
            conn.commit()

def get_messages(chat_id: str) -> List[Dict]:
    with get_db() as conn:
        rows = conn.execute("SELECT * FROM messages WHERE chat_id = ? ORDER BY created_at ASC", (chat_id,)).fetchall()
        res = []
        for r in rows:
            d = dict(r)
            try:
                d["metrics"] = json.loads(d["metrics"]) if d.get("metrics") else {}
            except Exception:
                d["metrics"] = {}
            try:
                d["attachments"] = json.loads(d["attachments"]) if d.get("attachments") else []
            except Exception:
                d["attachments"] = []
            res.append(d)
        return res
