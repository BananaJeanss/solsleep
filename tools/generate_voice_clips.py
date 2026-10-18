#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Original phrases, CMU Flite 2.2 cmu_us_kal diphone voice (no neural model).
Requires the Debian libflite1 package. No engine or voice code is linked into the app.
See docs/VOICEOVER.md and third_party/flite-COPYING.txt for provenance/license.
"""
import sys
import hashlib
import ctypes
from pathlib import Path
import wave

root=Path(__file__).resolve().parents[1]
if '--check' in sys.argv:
    expected={
        'voice_captain':'f38c2b0487fb871b8181cfa0aab249e5b71bff28f7436ca29361e49bc878868d',
        'voice_moon':'0888ad8d3aeddc72fd275a36d04822863907c00606a9cb931e104702094e8105',
        'voice_morning':'c0e06f1fd39e5ef20cd1b34694fd7d1c7886022ac90ad3fc6a9afa3f8cfaf8cb'}
    for name,digest in expected.items():
        path=root/'app/src/main/res/raw'/f'{name}.wav'
        assert hashlib.sha256(path.read_bytes()).hexdigest()==digest, name
        with wave.open(str(path),'rb') as f:
            assert (f.getnchannels(),f.getsampwidth(),f.getframerate())==(1,2,8000)
            assert 4000<=f.getnframes()<=48000
    print('Three original voice assets: hashes and bounded PCM headers verified')
    sys.exit(0)
flite=ctypes.CDLL('libflite.so.2.2',mode=ctypes.RTLD_GLOBAL)
kal=ctypes.CDLL('libflite_cmu_us_kal.so.2.2',mode=ctypes.RTLD_GLOBAL)
flite.flite_init()
kal.register_cmu_us_kal.argtypes=[ctypes.c_char_p]
kal.register_cmu_us_kal.restype=ctypes.c_void_p
voice=kal.register_cmu_us_kal(None)
assert voice
flite.flite_text_to_speech.argtypes=[ctypes.c_char_p,ctypes.c_void_p,ctypes.c_char_p]
flite.flite_text_to_speech.restype=ctypes.c_float
for name,text in [('voice_morning','Good morning. The sun is up.'),('voice_captain','Captain, the pillows have taken over.'),('voice_moon','Moon control. I am ready for breakfast.')]:
    path=root/'app/src/main/res/raw'/f'{name}.wav'
    assert flite.flite_text_to_speech(text.encode(),voice,str(path).encode())>0
    with wave.open(str(path),'rb') as f:
        assert f.getframerate()==8000 and f.getnchannels()==1 and f.getsampwidth()==2
        assert 4000<=f.getnframes()<=48000
    print(name,text)
