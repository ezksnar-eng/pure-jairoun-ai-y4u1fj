"""
Jeron Gateway — شغّله على جهاز عندك (كمبيوتر بكرت شاشة / سيرفر GPU) حتى تصير نماذج Qwen3 + Qwen-Image + Wan2.1
متاحة للتطبيق عن طريق الشبكة المحلية (تشتغل حتى لو النت الخارجي مقطوع، بشرط الهاتف والجهاز على نفس الشبكة).

  pip install fastapi uvicorn transformers accelerate torch diffusers pillow
  # فيديو: ثبّت متطلبات ai_projects/Wan2.1 ونزّل الأوزان حسب README تبعه
  # صوت (اختياري، استنساخ من عينة): pip install TTS
  python jeron_gateway.py --port 8000

بعدها بالتطبيق: الإعدادات > سيرفرك الخاص > http://IP_جهازك:8000/v1  (للكلام)
أو فعّل custom_model.js (BASE = http://IP_جهازك:8000) للصور والفيديو والصوت.
ملاحظة: هذا الملف ما انجرب هنا (ما عندي GPU)؛ هو هيكل جاهز، وإذا طلع خطأ بنسخة مكتبة عدّل السطر المعني.
"""
import argparse, base64, io, os, subprocess, tempfile, time
from fastapi import FastAPI, Form, UploadFile, File, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

CHAT_MODEL = os.environ.get("CHAT_MODEL", "Qwen/Qwen3-8B")          # غيّره: Qwen/Qwen3-30B-A3B ... حسب كرتك
IMAGE_MODEL = os.environ.get("IMAGE_MODEL", "Qwen/Qwen-Image")
WAN_CKPT = os.environ.get("WAN_CKPT", "./Wan2.1-T2V-1.3B")            # مجلد أوزان Wan
WAN_TASK = os.environ.get("WAN_TASK", "t2v-1.3B")

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
_state = {}

def chat_pipe():
    if "chat" not in _state:
        from transformers import AutoModelForCausalLM, AutoTokenizer
        tok = AutoTokenizer.from_pretrained(CHAT_MODEL)
        mdl = AutoModelForCausalLM.from_pretrained(CHAT_MODEL, torch_dtype="auto", device_map="auto")
        _state["chat"] = (tok, mdl)
    return _state["chat"]

@app.get("/v1/models")
def models():
    return {"data": [{"id": CHAT_MODEL, "object": "model"}]}

@app.post("/v1/chat/completions")
async def chat(req: Request):
    body = await req.json()
    tok, mdl = chat_pipe()
    msgs = []
    for m in body["messages"]:
        c = m["content"]
        if isinstance(c, list):  # صور غير مدعومة بهذا النموذج: ناخذ النص فقط
            c = " ".join(p.get("text", "") for p in c if p.get("type") == "text")
        msgs.append({"role": m["role"], "content": c})
    text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    inp = tok([text], return_tensors="pt").to(mdl.device)
    out = mdl.generate(**inp, max_new_tokens=int(body.get("max_tokens", 1024)), do_sample=True, temperature=0.7, top_p=0.9)
    reply = tok.decode(out[0][inp.input_ids.shape[1]:], skip_special_tokens=True)
    return {"id": "jg-%d" % time.time(), "object": "chat.completion", "model": CHAT_MODEL,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}]}

@app.post("/v1/images/generations")
async def images(req: Request):
    body = await req.json()
    if "img" not in _state:
        import torch
        from diffusers import DiffusionPipeline
        _state["img"] = DiffusionPipeline.from_pretrained(IMAGE_MODEL, torch_dtype=torch.bfloat16).to("cuda")
    sizes = {"square": (1328, 1328), "wide": (1664, 928), "portrait": (928, 1664)}
    w, h = sizes.get(body.get("size"), (1328, 1328))
    img = _state["img"](prompt=body["prompt"], width=w, height=h, num_inference_steps=int(body.get("steps", 30))).images[0]
    buf = io.BytesIO(); img.save(buf, format="PNG")
    return {"data": [{"b64_json": base64.b64encode(buf.getvalue()).decode()}]}

@app.post("/v1/videos/generations")
async def videos(req: Request):
    body = await req.json()
    out = os.path.join(tempfile.gettempdir(), "jeron_wan_%d.mp4" % time.time())
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "Wan2.1")
    cmd = ["python", "generate.py", "--task", WAN_TASK, "--size", "480*832", "--ckpt_dir", WAN_CKPT,
           "--prompt", body["prompt"], "--save_file", out]
    subprocess.run(cmd, cwd=here, check=True)
    return Response(open(out, "rb").read(), media_type="video/mp4")

@app.post("/tts")
async def tts(text: str = Form(...), speaker_wav: UploadFile = File(None), language: str = Form("ar")):
    # XTTS-v2 يستنسخ الصوت من العينة. استعمله بس بصوت إنت صاحبه أو عندك إذن فيه.
    from TTS.api import TTS
    if "tts" not in _state:
        _state["tts"] = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to("cuda")
    ref = None
    if speaker_wav is not None:
        ref = os.path.join(tempfile.gettempdir(), "jeron_ref.wav")
        open(ref, "wb").write(await speaker_wav.read())
    out = os.path.join(tempfile.gettempdir(), "jeron_tts.wav")
    _state["tts"].tts_to_file(text=text, speaker_wav=ref, language=language, file_path=out)
    return Response(open(out, "rb").read(), media_type="audio/wav")

if __name__ == "__main__":
    import uvicorn
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8000)
    uvicorn.run(app, host="0.0.0.0", port=ap.parse_args().port)
