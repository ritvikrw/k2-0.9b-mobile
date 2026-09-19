import sys
import os
import warnings

os.environ["TRANSFORMERS_VERBOSITY"] = "error"
warnings.filterwarnings("ignore")

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, TextStreamer, BitsAndBytesConfig, logging

logging.set_verbosity_error()

num_cores = os.cpu_count() or 8
torch.set_num_threads(num_cores)

MODEL_ID = "IFM/K2-Horizon-0.9B"

print("==================================================")
print(f" Loading {MODEL_ID} (Q4 4-bit Quantized)...")
print("==================================================\n")

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
    print("\n[+] K2 Horizon 0.9B Q4 is ready! Ultra-low memory & fast.\n")
except Exception as e:
    print(f"\n[-] Error loading model: {e}")
    sys.exit(1)

streamer = TextStreamer(tokenizer, skip_prompt=True, skip_special_tokens=True)

print("--------------------------------------------------")
print("Chat with K2 Horizon 0.9B (Q4) - type 'exit' to quit")
print("--------------------------------------------------\n")

history = []

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
    
    print("\nK2 Horizon: ", end="", flush=True)
    with torch.no_grad():
        output = model.generate(
            **inputs,
            streamer=streamer,
            max_new_tokens=512,
            temperature=0.7,
            top_p=0.9,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )
    print("\n")
