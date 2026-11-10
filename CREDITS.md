# Credits

## Tutorial narrator voice ("Wren")

The tutorial narrator's lines are pre-rendered speech files (`assets/sonar/sounds/voice/*.ogg`) made with:

- **Kokoro-82M** text-to-speech model by hexgrad, voice **af_heart**. License: Apache License 2.0.
  https://huggingface.co/hexgrad/Kokoro-82M
- Rendered through **kokoro-onnx** by thewh1teagle (MIT License), https://github.com/thewh1teagle/kokoro-onnx, using its ONNX conversion of the model and voices (`kokoro-v1.0.onnx`, `voices-v1.0.bin`).
- Phonemes by **espeak-ng** (GPL-3.0, via espeakng-loader), used only when rendering. It is not shipped with the mod.

The render tool is `tools/voice/render.py` in this repository. The line texts are original to Sonar Access (`assets/sonar/voice/script.json`).

Lines that can't be pre-rendered (names you type, a key you rebound) are spoken by your system's own voice (Windows speech).

## Sounds

Sonar's other sounds are Minecraft's own sounds, or filtered and looped versions made from them, played through the game.
