window.JERON_CUSTOM = {
  enabled: true,
  name: 'Jeron Multi-Key Pro',
  BASE: 'https://openrouter.ai/api/v1',
  MODEL: 'deepseek/deepseek-r1:free',
  keys: [
    'YOUR_OPENROUTER_KEY',
    'YOUR_OPENROUTER_KEY',
    'YOUR_OPENROUTER_KEY'
  ],
  keyIndex: 0,

  getKey() {
    return this.keys[this.keyIndex];
  },

  rotateKey() {
    this.keyIndex = (this.keyIndex + 1) % this.keys.length;
  },

  async fetchWithRotation(url, options, retries = 3) {
    for (let i = 0; i < retries; i++) {
      const key = this.getKey();
      const headers = {
        ...options.headers,
        'Authorization': `Bearer ${key}`,
        'HTTP-Referer': 'https://jeron.app',
        'X-Title': 'Jeron AI'
      };

      const res = await fetch(url, { ...options, headers });
      if (res.ok) return res;

      if ([401, 402, 429, 500, 502, 503, 504].includes(res.status)) {
        this.rotateKey();
        continue;
      }

      const errText = await res.text();
      throw new Error(`API Error ${res.status}: ${errText}`);
    }
    throw new Error('All API keys exhausted.');
  },

  async chat({ messages, onDelta, signal, task }) {
    const res = await this.fetchWithRotation(`${this.BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: this.MODEL,
        messages: messages,
        stream: !!onDelta
      }),
      signal
    });

    if (!onDelta) {
      const data = await res.json();
      return data.choices?.[0]?.message?.content || '';
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let fullText = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      const lines = chunk.split('\n');
      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const dataStr = line.slice(6).trim();
          if (dataStr === '[DONE]') continue;
          try {
            const parsed = JSON.parse(dataStr);
            const content = parsed.choices?.[0]?.delta?.content || '';
            if (content) {
              fullText += content;
              onDelta(content);
            }
          } catch (e) {}
        }
      }
    }
    return fullText;
  },

  async image({ prompt, refs, size, signal }) {
    const encoded = encodeURIComponent(prompt);
    const w = size === 'wide' ? 1024 : size === 'portrait' ? 768 : 1024;
    const h = size === 'wide' ? 576 : size === 'portrait' ? 1024 : 1024;
    const url = `https://image.pollinations.ai/prompt/${encoded}?width=${w}&height=${h}&nologo=true`;
    const res = await fetch(url, { signal });
    if (!res.ok) throw new Error('Image generation failed');
    return await res.blob();
  },

  async tts({ text, refVoice, signal }) {
    const url = `https://translate.google.com/translate_tts?ie=UTF-8&q=${encodeURIComponent(text)}&tl=ar&client=tw-ob`;
    const res = await fetch(url, { signal });
    if (!res.ok) throw new Error('TTS failed');
    return await res.blob();
  }
};
