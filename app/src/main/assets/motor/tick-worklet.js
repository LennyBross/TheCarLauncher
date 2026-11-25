// MYDS: a clock for the engine model that runs on the audio thread. Timers in a WebView that is
// not on screen can be slowed to once a second (the sound then jumps in steps); audio keeps time.
class MydsTick extends AudioWorkletProcessor {
  constructor(o) { super(); this.every = (o && o.processorOptions && o.processorOptions.every) || 735; this.n = 0; }
  process(inputs, outputs) {
    this.n += 128;
    if (this.n >= this.every) { this.n -= this.every; this.port.postMessage(currentTime); }
    return true;
  }
}
registerProcessor('myds-tick', MydsTick);
