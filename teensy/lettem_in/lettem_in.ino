// Lettem In — Teensy 4.1 USB Audio + Serial trigger
//
// USB Type (Arduino IDE → Tools → USB Type): "Serial + MIDI + Audio"
// Phone sees this device as a USB headset (mic + speaker). The Android app
// answers the call, routes audio to USB, then sends 'G' over CDC Serial.
// We respond by streaming a WAV file from SD into the USB mic endpoint,
// then injecting DTMF '9' (852 Hz + 1477 Hz) for ~350 ms.
//
// SD card layout:
//   /greeting.wav     (16-bit PCM WAV; 44.1 kHz recommended)
//
// Serial protocol (115200 8N1):
//   'G'  -> play greeting, then DTMF '9'
//   'S'  -> stop playback immediately
//   'P'  -> ping; replies 'p'
//
// Build: Teensyduino (Arduino IDE). Select board "Teensy 4.1".

#include <Audio.h>
#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <SerialFlash.h>

// ---- Audio graph ----
AudioPlaySdWav         greeting;
AudioSynthWaveformSine dtmfRow;   // 852 Hz  (DTMF row index 2)
AudioSynthWaveformSine dtmfCol;   // 1477 Hz (DTMF col index 2)
AudioMixer4            mixL;
AudioMixer4            mixR;
AudioOutputUSB         usbOut;    // appears to host as USB mic input

AudioConnection patchGreetL(greeting, 0, mixL, 0);
AudioConnection patchGreetR(greeting, 1, mixR, 0);
AudioConnection patchRowL  (dtmfRow,  0, mixL, 1);
AudioConnection patchRowR  (dtmfRow,  0, mixR, 1);
AudioConnection patchColL  (dtmfCol,  0, mixL, 2);
AudioConnection patchColR  (dtmfCol,  0, mixR, 2);
AudioConnection patchOutL  (mixL, 0, usbOut, 0);
AudioConnection patchOutR  (mixR, 0, usbOut, 1);

// ---- State machine ----
enum State { IDLE, PLAYING_GREETING, GAP, DTMF, DONE };
State state = IDLE;
elapsedMillis stateClock;

const uint16_t GAP_MS  = 200;
const uint16_t DTMF_MS = 350;
const float    DTMF_AMP = 0.40f;  // each tone; sum stays under clip
const float    GREET_GAIN = 1.0f;

void setup() {
  AudioMemory(24);

  // Greeting mixer channels: ch0=greeting, ch1=dtmfRow, ch2=dtmfCol
  mixL.gain(0, GREET_GAIN); mixL.gain(1, 0); mixL.gain(2, 0); mixL.gain(3, 0);
  mixR.gain(0, GREET_GAIN); mixR.gain(1, 0); mixR.gain(2, 0); mixR.gain(3, 0);

  dtmfRow.frequency(852);  dtmfRow.amplitude(0);
  dtmfCol.frequency(1477); dtmfCol.amplitude(0);

  Serial.begin(115200);

  if (!SD.begin(BUILTIN_SDCARD)) {
    // No SD: we can still DTMF, just no greeting.
  }
}

void startGreeting() {
  if (SD.exists("greeting.wav")) {
    greeting.play("greeting.wav");
    state = PLAYING_GREETING;
  } else {
    // No file -> skip straight to DTMF
    state = GAP;
  }
  stateClock = 0;
}

void startDtmf() {
  mixL.gain(1, 1.0f); mixL.gain(2, 1.0f);
  mixR.gain(1, 1.0f); mixR.gain(2, 1.0f);
  dtmfRow.amplitude(DTMF_AMP);
  dtmfCol.amplitude(DTMF_AMP);
  state = DTMF;
  stateClock = 0;
}

void stopAll() {
  greeting.stop();
  dtmfRow.amplitude(0);
  dtmfCol.amplitude(0);
  mixL.gain(1, 0); mixL.gain(2, 0);
  mixR.gain(1, 0); mixR.gain(2, 0);
  state = IDLE;
}

void handleSerial() {
  while (Serial.available()) {
    int c = Serial.read();
    switch (c) {
      case 'G': startGreeting(); break;
      case 'S': stopAll();       break;
      case 'P': Serial.write('p'); break;
      default: break;
    }
  }
}

void loop() {
  handleSerial();

  switch (state) {
    case PLAYING_GREETING:
      if (!greeting.isPlaying() && stateClock > 100) {  // small grace to avoid race
        state = GAP;
        stateClock = 0;
      }
      break;
    case GAP:
      if (stateClock >= GAP_MS) startDtmf();
      break;
    case DTMF:
      if (stateClock >= DTMF_MS) {
        dtmfRow.amplitude(0);
        dtmfCol.amplitude(0);
        mixL.gain(1, 0); mixL.gain(2, 0);
        mixR.gain(1, 0); mixR.gain(2, 0);
        state = DONE;
      }
      break;
    case DONE:
      state = IDLE;
      break;
    case IDLE:
    default: break;
  }
}
