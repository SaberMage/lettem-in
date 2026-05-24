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
enum State { IDLE, PLAYING_GREETING, GAP, DTMF_ON, DTMF_OFF, DONE };
State state = IDLE;
elapsedMillis stateClock;
uint8_t dtmfBurst = 0;

const uint16_t GAP_MS         = 400;   // greeting tail drain
const uint16_t DTMF_ON_MS     = 600;   // one burst length
const uint16_t DTMF_OFF_MS    = 120;   // silence between bursts
const uint8_t  DTMF_BURSTS    = 3;     // repeat so carrier decoder has multiple shots
const float    DTMF_AMP       = 0.50f; // each tone; 0.5+0.5 = 1.0 sum, max before clip
const float    GREET_GAIN     = 0.60f; // ducked so phone-side AGC doesn't compress and
                                       // suppress the louder DTMF burst that follows

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

void startDtmf() {
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
    case DTMF_ON:
      if (stateClock >= DTMF_ON_MS) {
        dtmfOff();
        dtmfBurst++;
        if (dtmfBurst >= DTMF_BURSTS) {
          state = DONE;
        } else {
          state = DTMF_OFF;
          stateClock = 0;
        }
      }
      break;
    case DTMF_OFF:
      if (stateClock >= DTMF_OFF_MS) {
        dtmfOn();
        state = DTMF_ON;
        stateClock = 0;
      }
      break;
    case DONE:
      state = IDLE;
      break;
    case IDLE:
    default: break;
  }
}
