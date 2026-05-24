// Lettem In — Teensy 4.1 USB Audio + Serial bridge.
//
// USB Type (Arduino IDE → Tools → USB Type): "Serial + MIDI + Audio"
// Phone sees this device as a USB headset (mic + speaker). The Android app
// answers the call, routes audio to USB, then sends serial commands.
//
// SD card layout (FAT32):
//   /<any>.wav      WAV files. Format: 16-bit signed PCM, 44.1 kHz, mono.
//                   Default played file is the last one selected via 'F', else
//                   "greeting.wav" if it exists.
//
// Serial protocol (115200 8N1). Multi-byte fields are little-endian.
//   'P'                                              ping. Reply: 'p'.
//   'S'                                              stop playback. Reply: none.
//   'G'                                              play active file then DTMF 9.
//   'A'                                              play active file only.
//   'M'                                              DTMF 9 only.
//   'F' <u16 nameLen> <nameLen bytes>                set active filename. Reply: 'f' OK / 'e' error.
//   'W' <u16 nameLen> <nameLen bytes>
//       <u32 size> <size bytes>                      write file to SD. Reply: 'D' OK / 'E' error.

#include <Audio.h>
#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <SerialFlash.h>

// ---- Audio graph ----
AudioPlaySdWav         greeting;
AudioSynthWaveformSine dtmfRow;   // 852 Hz
AudioSynthWaveformSine dtmfCol;   // 1477 Hz
AudioMixer4            mixL;
AudioMixer4            mixR;
AudioOutputUSB         usbOut;    // appears to host as USB mic

AudioConnection patchGreetL(greeting, 0, mixL, 0);
AudioConnection patchGreetR(greeting, 1, mixR, 0);
AudioConnection patchRowL  (dtmfRow,  0, mixL, 1);
AudioConnection patchRowR  (dtmfRow,  0, mixR, 1);
AudioConnection patchColL  (dtmfCol,  0, mixL, 2);
AudioConnection patchColR  (dtmfCol,  0, mixR, 2);
AudioConnection patchOutL  (mixL, 0, usbOut, 0);
AudioConnection patchOutR  (mixR, 0, usbOut, 1);

// ---- State machine ----
enum State { IDLE, PLAYING_GREETING, GAP, DTMF_ON, DTMF_OFF, DONE };
State state = IDLE;
elapsedMillis stateClock;
uint8_t dtmfBurst = 0;
bool dtmfTrailing = true;       // play DTMF after greeting? false for 'A' command

const uint16_t GAP_MS         = 1000;  // silence between greeting end and first DTMF burst
const uint16_t DTMF_ON_MS     = 600;
const uint16_t DTMF_OFF_MS    = 120;
const uint8_t  DTMF_BURSTS    = 3;
const float    DTMF_AMP       = 0.50f;
const float    GREET_GAIN     = 0.60f;

// ---- Active file ----
char activeFile[64] = "greeting.wav";

// ---- Serial read helpers ----
static bool readExact(uint8_t* buf, size_t n, uint32_t timeoutMs = 8000) {
  uint32_t start = millis();
  size_t got = 0;
  while (got < n) {
    if (Serial.available()) {
      int c = Serial.read();
      if (c >= 0) buf[got++] = (uint8_t)c;
    } else {
      if (millis() - start > timeoutMs) return false;
    }
  }
  return true;
}

static uint16_t readU16LE() {
  uint8_t b[2];
  if (!readExact(b, 2)) return 0;
  return (uint16_t)b[0] | ((uint16_t)b[1] << 8);
}

static uint32_t readU32LE() {
  uint8_t b[4];
  if (!readExact(b, 4)) return 0;
  return (uint32_t)b[0]
       | ((uint32_t)b[1] << 8)
       | ((uint32_t)b[2] << 16)
       | ((uint32_t)b[3] << 24);
}

// ---- Audio control ----
void dtmfOn() {
  mixL.gain(1, 1.0f); mixL.gain(2, 1.0f);
  mixR.gain(1, 1.0f); mixR.gain(2, 1.0f);
  dtmfRow.amplitude(DTMF_AMP);
  dtmfCol.amplitude(DTMF_AMP);
}

void dtmfOff() {
  dtmfRow.amplitude(0);
  dtmfCol.amplitude(0);
  mixL.gain(1, 0); mixL.gain(2, 0);
  mixR.gain(1, 0); mixR.gain(2, 0);
}

void startGreeting(bool withDtmf) {
  dtmfTrailing = withDtmf;
  dtmfBurst = 0;
  if (SD.exists(activeFile)) {
    greeting.play(activeFile);
    state = PLAYING_GREETING;
  } else {
    state = withDtmf ? GAP : DONE;
  }
  stateClock = 0;
}

void startDtmfOnly() {
  dtmfTrailing = true;
  dtmfBurst = 0;
  dtmfOn();
  state = DTMF_ON;
  stateClock = 0;
}

void stopAll() {
  greeting.stop();
  dtmfOff();
  state = IDLE;
}

// ---- File ops ----
void cmdSetActive() {
  uint16_t n = readU16LE();
  if (n == 0 || n >= sizeof(activeFile)) {
    Serial.write('e'); return;
  }
  uint8_t buf[64];
  if (!readExact(buf, n)) { Serial.write('e'); return; }
  memcpy(activeFile, buf, n);
  activeFile[n] = '\0';
  Serial.write('f');
}

void cmdWriteFile() {
  uint16_t n = readU16LE();
  if (n == 0 || n >= 64) { Serial.write('E'); return; }
  uint8_t nameBuf[64];
  if (!readExact(nameBuf, n)) { Serial.write('E'); return; }
  nameBuf[n] = '\0';
  uint32_t size = readU32LE();
  if (size == 0 || size > 16UL * 1024UL * 1024UL) {  // 16 MB cap
    Serial.write('E'); return;
  }

  // Overwrite if exists
  if (SD.exists((char*)nameBuf)) SD.remove((char*)nameBuf);
  File f = SD.open((char*)nameBuf, FILE_WRITE);
  if (!f) { Serial.write('E'); return; }

  uint8_t chunk[512];
  uint32_t remaining = size;
  uint32_t start = millis();
  while (remaining > 0) {
    uint32_t take = remaining > sizeof(chunk) ? sizeof(chunk) : remaining;
    if (!readExact(chunk, take, 15000)) { f.close(); Serial.write('E'); return; }
    if (f.write(chunk, take) != (int)take) { f.close(); Serial.write('E'); return; }
    remaining -= take;
    if (millis() - start > 60000) { f.close(); Serial.write('E'); return; }
  }
  f.close();
  Serial.write('D');
}

// ---- Serial dispatch ----
void handleSerial() {
  while (Serial.available()) {
    int c = Serial.read();
    if (c < 0) return;
    switch (c) {
      case 'G': startGreeting(true);  return;
      case 'A': startGreeting(false); return;
      case 'M': startDtmfOnly();      return;
      case 'S': stopAll();            return;
      case 'P': Serial.write('p');    break;
      case 'F': cmdSetActive();       return;
      case 'W': cmdWriteFile();       return;
      default: break;
    }
  }
}

// ---- Setup / loop ----
void setup() {
  AudioMemory(24);
  mixL.gain(0, GREET_GAIN); mixL.gain(1, 0); mixL.gain(2, 0); mixL.gain(3, 0);
  mixR.gain(0, GREET_GAIN); mixR.gain(1, 0); mixR.gain(2, 0); mixR.gain(3, 0);
  dtmfRow.frequency(852);  dtmfRow.amplitude(0);
  dtmfCol.frequency(1477); dtmfCol.amplitude(0);
  Serial.begin(115200);
  SD.begin(BUILTIN_SDCARD);
}

void loop() {
  handleSerial();

  switch (state) {
    case PLAYING_GREETING:
      if (!greeting.isPlaying() && stateClock > 100) {
        state = dtmfTrailing ? GAP : DONE;
        stateClock = 0;
      }
      break;
    case GAP:
      if (stateClock >= GAP_MS) { dtmfOn(); state = DTMF_ON; stateClock = 0; }
      break;
    case DTMF_ON:
      if (stateClock >= DTMF_ON_MS) {
        dtmfOff();
        dtmfBurst++;
        if (dtmfBurst >= DTMF_BURSTS) state = DONE;
        else { state = DTMF_OFF; stateClock = 0; }
      }
      break;
    case DTMF_OFF:
      if (stateClock >= DTMF_OFF_MS) { dtmfOn(); state = DTMF_ON; stateClock = 0; }
      break;
    case DONE:
      state = IDLE;
      break;
    case IDLE:
    default: break;
  }
}
