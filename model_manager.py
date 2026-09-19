import os
import sys
import time
import json
import queue
import asyncio
import warnings
from typing import AsyncGenerator, Dict, List, Optional
from threading import Thread

os.environ["TRANSFORMERS_VERBOSITY"] = "error"
warnings.filterwarnings("ignore")

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, TextIteratorStreamer, BitsAndBytesConfig, logging

logging.set_verbosity_error()

num_cores = os.cpu_count() or 8
torch.set_num_threads(num_cores)

PERSONAS = {
    "default": "You are K2 Horizon, an intelligent, fast, and helpful AI assistant.",
    "concise": "You are K2 Horizon. Provide ultra-fast, direct, and concise answers with zero fluff.",
    "coder": "You are K2 Horizon, an expert senior software engineer. Provide production-ready, clean code with brief explanations.",
    "reasoner": "You are K2 Horizon, an expert analytical thinker. Think step-by-step methodically before delivering the final answer.",
    "writer": "You are K2 Horizon, an expressive creative writer and editor. Produce engaging, polished, and structured text."
}

MODEL_CONFIGS = {
    "0.9b": {
        "id": "IFM/K2-Horizon-0.9B",
        "name": "K2 Horizon 0.9B (Q4 Quantized)",
        "tag": "0.9B Q4 (Ultra Fast)",
        "quantization": "4-bit NF4",
        "system_prompt": PERSONAS["default"]
    },
    "7b": {
        "id": "IFM/K2-Horizon-7B",
        "name": "K2 Horizon 7B",
        "tag": "7B (Deep Reasoning)",
        "quantization": "bfloat16",
        "system_prompt": PERSONAS["reasoner"]
    }
}

SPECIAL_TOKENS = ["<|ifm|im_end|>", "<|im_end|>", "<|endoftext|>", "<|im_start|>", "<s>", "</s>", "<ifm|think>", "<think>", "</ifm|think>", "</think>"]

def clean_special_tokens(text: str) -> str:
    for token in SPECIAL_TOKENS:
        text = text.replace(token, "")
    return text

class ModelManager:
    def __init__(self):
        self.current_model_key: Optional[str] = None
        self.model = None
        self.tokenizer = None
        self.lock = asyncio.Lock()
        self.active_aborts: Dict[str, bool] = {}

    def get_status(self) -> Dict:
        return {
            "loaded_model": self.current_model_key,
            "models_available": list(MODEL_CONFIGS.keys()),
            "personas_available": list(PERSONAS.keys()),
            "quantization": MODEL_CONFIGS.get(self.current_model_key, {}).get("quantization", "None"),
            "ram_info": f"Ryzen 7 ({num_cores} cores) / 32GB RAM"
        }

    def abort_stream(self, chat_id: str):
        """Signal generation loop to cancel immediately"""
        self.active_aborts[chat_id] = True

    def load_model(self, model_key: str):
        if model_key not in MODEL_CONFIGS:
            raise ValueError(f"Unknown model key: {model_key}")

        if self.current_model_key == model_key and self.model is not None:
            return

        model_id = MODEL_CONFIGS[model_key]["id"]
        print(f"\n[ModelManager] Loading model into RAM: {model_id} ({model_key})...")

        if self.model is not None:
            del self.model
            del self.tokenizer
            import gc
            gc.collect()

        tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)

        if model_key == "0.9b":
            # 4-bit NF4 Quantization for K2-0.9B Q4
            bnb_config = BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_compute_dtype=torch.float32,
                bnb_4bit_quant_type="nf4"
            )
            try:
                model = AutoModelForCausalLM.from_pretrained(
                    model_id,
                    quantization_config=bnb_config,
                    device_map="cpu",
                    low_cpu_mem_usage=True,
                    trust_remote_code=True
                )
                print(f"[ModelManager] {model_id} loaded in 4-bit (NF4 Q4) successfully!")
            except Exception as e:
                print(f"[ModelManager] BNB 4-bit fallback: {e}. Loading bfloat16...")
                model = AutoModelForCausalLM.from_pretrained(
                    model_id,
                    dtype=torch.bfloat16,
                    device_map="cpu",
                    low_cpu_mem_usage=True,
                    trust_remote_code=True
                )
        else:
            model = AutoModelForCausalLM.from_pretrained(
                model_id,
                dtype=torch.bfloat16,
                low_cpu_mem_usage=True,
                device_map="cpu",
                trust_remote_code=True
            )

        model.eval()
        self.tokenizer = tokenizer
        self.model = model
        self.current_model_key = model_key
        print(f"[ModelManager] {model_id} ready!\n")

    async def stream_chat(self, model_key: str, messages: List[Dict], chat_id: str = "", persona: str = "default") -> AsyncGenerator[str, None]:
        async with self.lock:
            if chat_id:
                self.active_aborts[chat_id] = False

            if self.current_model_key != model_key or self.model is None:
                model_name = MODEL_CONFIGS[model_key]["name"]
                yield f"event: status\ndata: {json.dumps({'message': f'Loading {model_name} into RAM...'})}\n\n"
                await asyncio.to_thread(self.load_model, model_key)
                yield f"event: status\ndata: {json.dumps({'message': 'Model ready! Evaluating prompt...'})}\n\n"

            system_prompt = PERSONAS.get(persona, PERSONAS["default"])
            
            # Format messages with attachments context & 'think' field for Jinja template
            formatted_messages = [{"role": "system", "content": system_prompt}]
            recent_messages = messages[-8:] if len(messages) > 8 else messages

            for m in recent_messages:
                clean_c = clean_special_tokens(m.get("content", ""))
                attachments = m.get("attachments", [])
                
                # Append attachment text context if present
                if attachments and isinstance(attachments, list):
                    att_context = "\n\n[Attached Documents / Context]:\n"
                    for att in attachments:
                        att_name = att.get("name", "Document")
                        att_text = att.get("text", "").strip()
                        if att_text:
                            att_context += f"--- {att_name} ---\n{att_text[:3000]}\n"
                    clean_c = clean_c + att_context

                if m["role"] == "assistant":
                    clean_th = clean_special_tokens(m.get("thinking", "")) or "thought"
                    formatted_messages.append({"role": "assistant", "content": clean_c, "think": clean_th})
                else:
                    formatted_messages.append({"role": "user", "content": clean_c})

            prompt = self.tokenizer.apply_chat_template(
                formatted_messages,
                tokenize=False,
                add_generation_prompt=True
            )
            inputs = self.tokenizer(prompt, return_tensors="pt").to("cpu")
            input_tokens_count = inputs["input_ids"].shape[1]

            q = queue.Queue()
            streamer = TextIteratorStreamer(self.tokenizer, skip_prompt=True, skip_special_tokens=False)
            
            generation_kwargs = dict(
                **inputs,
                streamer=streamer,
                max_new_tokens=1024,
                temperature=0.7,
                top_p=0.9,
                do_sample=True,
                pad_token_id=self.tokenizer.eos_token_id
            )

            def worker():
                try:
                    with torch.no_grad():
                        self.model.generate(**generation_kwargs)
                except Exception as ex:
                    q.put(("error", str(ex)))

            def streamer_reader():
                try:
                    for text_chunk in streamer:
                        if chat_id and self.active_aborts.get(chat_id, False):
                            break
                        q.put(("token", text_chunk))
                except Exception as ex:
                    q.put(("error", str(ex)))
                finally:
                    q.put(("end", None))

            t_gen = Thread(target=worker, daemon=True)
            t_stream = Thread(target=streamer_reader, daemon=True)
            t_gen.start()
            t_stream.start()

            start_time = time.time()
            first_token_time: Optional[float] = None
            total_tokens = 0
            
            in_thinking = True
            accumulated_thinking = []
            accumulated_answer = []
            buffer = ""

            yield f"event: start\ndata: {json.dumps({'model': model_key, 'prompt_tokens': input_tokens_count, 'quant': MODEL_CONFIGS[model_key]['quantization']})}\n\n"

            while True:
                if chat_id and self.active_aborts.get(chat_id, False):
                    yield f"event: status\ndata: {json.dumps({'message': 'Generation stopped by user.'})}\n\n"
                    break

                try:
                    item_type, val = await asyncio.to_thread(q.get, timeout=0.08)
                except queue.Empty:
                    yield ": ping\n\n"
                    continue

                if item_type == "end":
                    break
                if item_type == "error":
                    yield f"event: error\ndata: {json.dumps({'error': val})}\n\n"
                    break

                chunk = val
                now = time.time()
                if first_token_time is None and chunk.strip():
                    first_token_time = now - start_time
                    ttft_ms = first_token_time * 1000
                    yield f"event: ttft\ndata: {json.dumps({'ttft_ms': round(ttft_ms, 1)})}\n\n"

                total_tokens += len(self.tokenizer.encode(chunk, add_special_tokens=False))
                buffer += chunk

                if in_thinking:
                    if "</ifm|think>" in buffer or "</think>" in buffer:
                        in_thinking = False
                        tag = "</ifm|think>" if "</ifm|think>" in buffer else "</think>"
                        parts = buffer.split(tag, 1)
                        think_part = clean_special_tokens(parts[0])
                        ans_part = parts[1] if len(parts) > 1 else ""

                        if think_part:
                            accumulated_thinking.append(think_part)
                            yield f"event: think\ndata: {json.dumps({'chunk': think_part})}\n\n"
                        
                        duration = round(now - start_time, 2)
                        yield f"event: think_end\ndata: {json.dumps({'duration': duration})}\n\n"
                        
                        buffer = ""
                        clean_ans = clean_special_tokens(ans_part)
                        if clean_ans:
                            accumulated_answer.append(clean_ans)
                            yield f"event: answer\ndata: {json.dumps({'chunk': clean_ans})}\n\n"
                    else:
                        clean_chunk = clean_special_tokens(chunk)
                        if clean_chunk:
                            accumulated_thinking.append(clean_chunk)
                            yield f"event: think\ndata: {json.dumps({'chunk': clean_chunk})}\n\n"
                else:
                    clean_chunk = clean_special_tokens(chunk)
                    if clean_chunk:
                        accumulated_answer.append(clean_chunk)
                        yield f"event: answer\ndata: {json.dumps({'chunk': clean_chunk})}\n\n"

            t_gen.join(timeout=0.5)
            t_stream.join(timeout=0.5)
            
            end_time = time.time()
            total_duration = max(end_time - start_time, 0.01)
            tok_per_sec = (total_tokens / total_duration) if total_duration > 0 else 0
            ttft_ms = ((first_token_time or total_duration) * 1000)

            full_think_clean = clean_special_tokens("".join(accumulated_thinking)).strip()
            full_ans_clean = clean_special_tokens("".join(accumulated_answer)).strip()

            metrics = {
                "ttft_ms": round(ttft_ms, 1),
                "tok_per_sec": round(tok_per_sec, 1),
                "total_time_s": round(total_duration, 2),
                "total_tokens": total_tokens,
                "prompt_tokens": input_tokens_count,
                "quantization": MODEL_CONFIGS[model_key]["quantization"],
                "full_thinking": full_think_clean,
                "full_answer": full_ans_clean
            }

            yield f"event: metrics\ndata: {json.dumps(metrics)}\n\n"
            yield "event: done\ndata: {}\n\n"

manager = ModelManager()
