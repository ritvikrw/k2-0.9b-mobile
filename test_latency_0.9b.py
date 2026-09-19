import sys
import os
import time
import warnings

os.environ["TRANSFORMERS_VERBOSITY"] = "error"
warnings.filterwarnings("ignore")

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, logging

logging.set_verbosity_error()

num_cores = os.cpu_count() or 8
torch.set_num_threads(num_cores)

MODEL_ID = "IFM/K2-Horizon-0.9B"

print("="*60)
print(f" 🚀 K2-HORIZON-0.9B (Q4 4-BIT) LATENCY & PERFORMANCE TEST")
print("="*60)
print("Loading model into RAM (4-bit NF4)...")

start_load = time.time()
try:
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, trust_remote_code=True)
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_compute_dtype=torch.float32,
        bnb_4bit_quant_type="nf4"
    )
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        quantization_config=bnb_config,
        low_cpu_mem_usage=True,
        device_map="cpu",
        trust_remote_code=True
    )
    model.eval()
    load_time = time.time() - start_load
    print(f"\n[+] Loaded in {load_time:.2f} seconds!\n")
except Exception as e:
    print(f"\n[-] Error loading model: {e}")
    sys.exit(1)

print("-" * 60)
print("Type any prompt to measure exact latency & speed (or 'exit'):")
print("-" * 60 + "\n")

SYSTEM_PROMPT = "You are K2 Horizon (0.9B Q4), a helpful and fast AI assistant. Keep reasoning short and give a complete final answer."

while True:
    try:
        user_input = input("\nYou: ").strip()
    except (KeyboardInterrupt, EOFError):
        print("\nExiting...")
        break

    if not user_input:
        continue
    if user_input.lower() in ["exit", "quit", "q"]:
        print("Goodbye!")
        break

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_input}
    ]
    
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt").to("cpu")

    print("\nK2 Horizon (0.9B Q4): ", end="", flush=True)
    
    first_token_time = None
    start_gen = time.time()

    with torch.no_grad():
        from transformers import TextIteratorStreamer
        from threading import Thread

        streamer = TextIteratorStreamer(tokenizer, skip_prompt=True, skip_special_tokens=True)
        generation_kwargs = dict(
            **inputs,
            streamer=streamer,
            max_new_tokens=2048,
            temperature=0.7,
            top_p=0.9,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )

        thread = Thread(target=model.generate, kwargs=generation_kwargs)
        thread.start()

        token_count = 0
        for text_chunk in streamer:
            if first_token_time is None:
                first_token_time = time.time() - start_gen
            print(text_chunk, end="", flush=True)
            token_count += len(tokenizer.encode(text_chunk, add_special_tokens=False))

        thread.join()
        total_time = time.time() - start_gen

    tok_per_sec = token_count / total_time if total_time > 0 else 0

    print("\n\n" + "-"*60)
    print(f"📊 LATENCY & SPEED METRICS (0.9B Q4):")
    print(f"   ⏱️  Time to First Token (TTFT): {((first_token_time or 0)*1000):.1f} ms")
    print(f"   ⚡ Generation Speed:           {tok_per_sec:.1f} tokens/sec")
    print(f"   🕒 Total Response Time:        {total_time:.2f} seconds ({token_count} tokens)")
    print("-" * 60)
