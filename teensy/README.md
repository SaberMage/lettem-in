# Teensy firmware — lettem-in

## Hardware
- Teensy 4.1 (built-in microSD slot). Teensy 4.0 works with external SD breakout on SPI.
- microSD card with `greeting.wav` at the root.
- USB-C → phone (phone must support USB OTG host mode).

## WAV format
- 16-bit signed PCM, little-endian
- 44.1 kHz, stereo or mono (mono auto-mixes both USB channels)
- Keep it ≤ a few seconds: e.g. `"Hello! Unlocking the door."`

Convert anything to compatible WAV:
```
ffmpeg -i greeting.mp3 -ac 1 -ar 44100 -sample_fmt s16 greeting.wav
```

## Arduino IDE setup
1. Install Teensyduino.
2. Tools → Board → Teensy 4.1
3. Tools → USB Type → **Serial + MIDI + Audio**
4. Open `lettem_in/lettem_in.ino`, compile + upload.

## Serial protocol (115200 8N1)
- `G` — play greeting then DTMF 9
- `S` — stop playback
- `P` — ping; replies `p`

## Wiring notes
No wiring — Teensy 4.1's built-in SD slot + USB-C handle everything.
For Teensy 4.0: wire an SD breakout to SPI (CS=10, MOSI=11, MISO=12, SCK=13)
and replace `BUILTIN_SDCARD` with `10` in `SD.begin()`.
