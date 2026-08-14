#!/usr/bin/env python3
"""
Measure the intent matcher's thresholds instead of guessing them.

IntentMatcher decides what the driver meant by comparing sentence embeddings, and the whole
behaviour turns on two numbers. Guessed values look reasonable and are wrong in ways that only
show up in a moving car: the first pass used 0.48, which routed "how old are you" to Navigation
and refused "put something on" for Media.

Run this after changing the anchor phrases in IntentMatcher.kt, and set the thresholds to what
it reports.

    python3 -m venv .venv && .venv/bin/pip install onnxruntime numpy
    .venv/bin/python nlu-calibrate.py            # needs minilm.onnx + vocab.txt alongside

What it prints is the separation: the lowest score among phrasings that *should* route, against
the highest among questions that should *not*. A threshold is only safe below the first and
above the second, and if those cross, no threshold works and the anchors need widening instead.

Last run (all-MiniLM-L6-v2, the anchors currently in IntentMatcher.kt):
    routed correctly 11/13, misrouted 0, false routes 0/8
    the two that fall through are handled by the C++ core instead, which is the safe direction
"""

exec(open('measure.py').read().split('print("=== SHOULD match')[0])

ANCHORS = {
 "MEDIA": ["play some music","put something on","put a song on","i want to listen to something",
           "turn the radio on","next track","skip this song","play my playlist","some tunes please",
           "turn the music up","stop the music","pause the music"],
 "NAV":   ["take me home","drive me home","navigate somewhere","give me directions",
           "how do i get there","where am i","find a petrol station","route to work",
           "show me the map","how long until we arrive"],
 "PHONE": ["call someone","ring mona","phone my wife","make a phone call","dial a number",
           "give her a ring","call the office","answer the phone","hang up"],
 "DIAG":  ["how is the car doing","is anything wrong with the vehicle","check the battery",
           "what is my tyre pressure","show me the warning lights","is the car healthy",
           "how much charge is left","any faults","what is that warning light"],
 "SET":   ["open the settings","change the display","connect to wifi","pair my phone",
           "turn on bluetooth","make the screen darker","change the theme"],
}
vecs = {k:[embed(p) for p in v] for k,v in ANCHORS.items()}
def best(q):
    e = embed(q); out=[]
    for k,vs in vecs.items(): out.append((max(float(e@v) for v in vs), k))
    out.sort(reverse=True); return out[0]

SHOULD = [("put something on","MEDIA"),("i want to hear a song","MEDIA"),("play the radio","MEDIA"),
          ("drive me home","NAV"),("where are we going","NAV"),("find me a garage","NAV"),
          ("ring mona","PHONE"),("call my brother","PHONE"),
          ("is the car ok","DIAG"),("how are my tyres","DIAG"),("whats my battery like","DIAG"),
          ("turn on wifi","SET"),("make it darker","SET")]
SHOULD_NOT = ["tell me a joke","what is the capital of france","who is fatty",
              "what time is it","how old are you","tell me a story","what is the weather",
              "explain the check engine light"]

print("=== should match ===")
ok=[]
for q,exp in SHOULD:
    s,k = best(q); mark = "OK " if k==exp else "WRONG"
    ok.append(s); print(f"  {s:.3f} {mark} {q!r} -> {k} (want {exp})")
print("=== should NOT match ===")
no=[]
for q in SHOULD_NOT:
    s,k = best(q); no.append(s); print(f"  {s:.3f}      {q!r} -> {k}")
print()
print(f"lowest true match : {min(ok):.3f}")
print(f"highest false hit : {max(no):.3f}")
print("separable" if min(ok) > max(no) else "OVERLAP — threshold cannot separate")
