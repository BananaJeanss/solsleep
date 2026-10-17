#!/usr/bin/env python3
"""Original looping alarm chime. No samples, network, or image/audio generation service."""
from pathlib import Path
import math, struct, wave
out=Path(__file__).resolve().parents[1]/"app/src/main/res/raw/dawn.wav"
rate=22050
notes=[523.25,659.25,783.99,659.25]
samples=[]
for i in range(rate*4):
    t=i/rate
    phase=t%1
    envelope=min(phase/.025,1)*math.exp(-phase*3.8)*min((1-phase)/.08,1)
    f=notes[int(t)%len(notes)]
    value=envelope*(math.sin(2*math.pi*f*t)+.25*math.sin(4*math.pi*f*t))*.44
    samples.append(struct.pack("<h",int(max(-1,min(1,value))*32767)))
with wave.open(str(out),"wb") as wav:
    wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(rate);wav.writeframes(b"".join(samples))
