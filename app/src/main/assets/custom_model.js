/* ==========================================================================
   Jeron — مكان النموذج الخارجي (Plug-in)
   هذا الملف يعرّف نموذج خارجي واحد يقدر يسوي: كلام + صور + فيديو + صوت (TTS) + سماع (STT).
   كل قسم اختياري: احذف الدالة اللي ما تحتاجها.
   للتفعيل: غيّر enabled إلى true، وبعدين من الإعدادات اختر "مخصص" (بدل عادي/برو).
   تقدر تخلي Gemini يكتب هذا الملف: انسخ GEMINI_MODEL_PLUGIN.md واعطيه له.
   ========================================================================== */
window.JERON_CUSTOM = {
  enabled: false,              // ← غيّرها true بعد ما تحط بيانات النموذج
  name: 'نموذجي الخارجي',       // الاسم اللي يظهر بالتطبيق

  /* ---- إعدادات (غيّرها حسب نموذجك) ---- */
  BASE: 'http://192.168.1.5:8000',   // رابط السيرفر/المزود
  KEY: '',                           // مفتاح API إذا يحتاج
  MODEL: 'my-model',                 // اسم النموذج

  /* ---- الكلام ----
     messages: [{role:'system'|'user'|'assistant', content: string | [{type:'text',text},{type:'image_url',image_url:{url}}]}]
     لازم ترجع النص الكامل، وتقدر تنادي onDelta(قطعة_نص) للعرض الحي (streaming). */
  async chat({ messages, onDelta, signal }) {
    const r = await fetch(this.BASE + '/v1/chat/completions', {
      method: 'POST', signal,
      headers: { 'Content-Type': 'application/json', ...(this.KEY ? { Authorization: 'Bearer ' + this.KEY } : {}) },
      body: JSON.stringify({ model: this.MODEL, messages, stream: false })
    });
    if (!r.ok) throw new Error('chat ' + r.status);
    const j = await r.json();
    return j.choices[0].message.content;
  },

  /* ---- الصور ---- لازم ترجع Blob صورة. size: 'square' | 'wide' | 'portrait'. refs: مصفوفة صور (data URL) للتعديل */
  async image({ prompt, refs, size, signal }) {
    const r = await fetch(this.BASE + '/v1/images/generations', {
      method: 'POST', signal,
      headers: { 'Content-Type': 'application/json', ...(this.KEY ? { Authorization: 'Bearer ' + this.KEY } : {}) },
      body: JSON.stringify({ prompt, size, response_format: 'b64_json' })
    });
    if (!r.ok) throw new Error('image ' + r.status);
    const j = await r.json();
    const b64 = j.data[0].b64_json;
    const bin = atob(b64), u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return new Blob([u8], { type: 'image/png' });
  },

  /* ---- الفيديو ---- لازم ترجع Blob فيديو (mp4). ref: صورة بداية اختيارية. note(نص) لعرض التقدم */
  async video({ prompt, ref, signal, note }) {
    note && note('جاري التوليد…');
    const r = await fetch(this.BASE + '/v1/videos/generations', {
      method: 'POST', signal,
      headers: { 'Content-Type': 'application/json', ...(this.KEY ? { Authorization: 'Bearer ' + this.KEY } : {}) },
      body: JSON.stringify({ prompt, image: ref || undefined })
    });
    if (!r.ok) throw new Error('video ' + r.status);
    return await r.blob();
  },

  /* ---- الصوت (TTS) ---- text: النص. refVoice: Blob عينة صوتك (إذا رفعتها) أو null. لازم ترجع Blob صوت */
  async tts({ text, refVoice, signal }) {
    const fd = new FormData();
    fd.append('text', text);
    if (refVoice) fd.append('speaker_wav', refVoice, 'ref.wav');
    const r = await fetch(this.BASE + '/tts', { method: 'POST', body: fd, signal });
    if (!r.ok) throw new Error('tts ' + r.status);
    return await r.blob();
  },

  /* ---- السماع (STT) ---- blob: تسجيل الصوت. لازم ترجع النص */
  async stt({ blob, lang, signal }) {
    const fd = new FormData();
    fd.append('file', blob, 'voice.webm'); fd.append('model', 'whisper-1'); fd.append('language', (lang || 'ar').slice(0, 2));
    const r = await fetch(this.BASE + '/v1/audio/transcriptions', { method: 'POST', body: fd, signal });
    if (!r.ok) throw new Error('stt ' + r.status);
    return (await r.json()).text;
  }
};
