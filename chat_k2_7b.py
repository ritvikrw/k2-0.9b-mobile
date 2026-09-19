import sys
import os
import warnings

os.environ["TRANSFORMERS_VERBOSITY"] = "error"
warnings.filterwarnings("ignore")

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, TextStreamer, logging

logging.set_verbosity_error()

num_cores = os.cpu_count() or 8
torch.set_num_threads(num_cores)

MODEL_ID = "IFM/K2-Horizon-7B"

print("==================================================")
print(f" Loading {MODEL_ID} (bfloat16 mode)...")
print("==================================================\n")

try:
    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID, 
        trust_remote_code=True
    )
    
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        torch_dtype=torch.bfloat16,
        low_cpu_mem_usage=True,
        device_map="cpu",
        trust_remote_code=True
    )
    model.eval()
    print("\n[+] Model is ready!\n")
except Exception as e:
    print(f"\n[-] Error loading model: {e}")
    sys.exit(1)

streamer = TextStreamer(tokenizer, skip_prompt=True, skip_special_tokens=True)

print("--------------------------------------------------")
print("Chat with K2 Horizon (7B) - type 'exit' to quit")
print("--------------------------------------------------\n")

# System prompt directing the model to provide both clear reasoning and the final answer
SYSTEM_PROMPT = "You are K2 Horizon, a helpful and precise AI assistant. Reason briefly and then provide a clear, comprehensive final answer."

history = [{"role": "system", "content": SYSTEM_PROMPT}]

while True:
    try:
        user_input = input("You: ").strip()
    except (KeyboardInterrupt, EOFError):
        print("\nExiting...")
        break

    if not user_input:
        continue
    if user_input.lower() in ["exit", "quit", "q"]:
        print("Goodbye!")
        break

    history.append({"role": "user", "content": user_input})
    
    prompt = tokenizer.apply_chat_template(
        history, 
        tokenize=False, 
        add_generation_prompt=True
    )
    inputs = tokenizer(prompt, return_tensors="pt").to("cpu")
    
    print("\nK2 Horizon:\n", flush=True)
    with torch.no_grad():
        output = model.generate(
            **inputs,
            streamer=streamer,
            max_new_tokens=1024,
            temperature=0.7,
            top_p=0.9,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )
    print("\n" + "="*50 + "\n")
