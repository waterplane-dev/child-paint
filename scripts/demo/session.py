# Writes a device-side sh script: a child-style drawing session for the README gif.
# (1080x2400 emulator, toolbar visible). Coordinates come from a uiautomator dump.
import math, random
random.seed(7)
out = []
def tap(x, y, pause=0.6): out.append(f"input tap {x} {y}; sleep {pause}")
def stroke(pts, pause=0.4):
    (x, y), rest = pts[0], pts[1:]
    out.append(f"input motionevent DOWN {x:.0f} {y:.0f}")
    for x, y in rest: out.append(f"input motionevent MOVE {x:.0f} {y:.0f}")
    out.append(f"input motionevent UP {rest[-1][0]:.0f} {rest[-1][1]:.0f}; sleep {pause}")
def wobble(pts, amt=8): return [(x + random.uniform(-amt, amt), y + random.uniform(-amt, amt)) for x, y in pts]
COL = {'black': (88, 1456), 'red': (604, 1456), 'orange': (733, 1456), 'amber': (862, 1456),
       'yellow': (991, 1456), 'green': (217, 1585), 'blue': (475, 1585), 'pink': (862, 1585)}
BRUSH, FILL, MORE = (127, 1869), (333, 1869), (952, 2043)
UNDO, REDO = (282, 2217), (454, 2217)
STAR, HEART, SUN = (218, 1099), (432, 1099), (218, 1495)
def colour(c): tap(*COL[c], 0.4)

out.append("sleep 1.5")
tap(*BRUSH); colour('blue'); tap(640, 1716)                      # fatter brush
# Strokes keep 48dp (144px) clear of the side edges: a swipe in from there is the back gesture.
# a wobbly closed loop, the way a child draws a face
cx, cy, r = 400, 820, 230
stroke(wobble([(cx + r * math.cos(t) * (1 + 0.08 * math.sin(3 * t)), cy + r * 0.95 * math.sin(t))
               for t in [i * 2 * math.pi / 26 for i in range(28)]], 6))
tap(*FILL); colour('yellow'); tap(cx, cy, 1.0)                    # bucket floods inside the wobble
tap(*BRUSH); colour('black')
stroke(wobble([(cx - 80 + i * 3, 760 + i * 2) for i in range(6)], 2))   # eyes
stroke(wobble([(cx + 70 + i * 3, 760 + i * 2) for i in range(6)], 2))
stroke(wobble([(cx - 110 + i * 22, 890 + 45 * math.sin(i / 10 * math.pi)) for i in range(11)], 3))  # smile
colour('green')                                                   # grass
stroke(wobble([(180 + i * 38, 1320 - 40 * abs(math.sin(i * 1.3))) for i in range(20)], 5))
tap(*MORE, 0.8); tap(*SUN, 0.5); colour('orange')                 # sun from the drawer, dragged out
# The pulled corner hangs ~280px above the finger, so a drag runs that much further down.
stroke([(720 + i * 11, 230 + i * 23) for i in range(24)], 1.2)
tap(*MORE, 0.8); tap(*HEART, 0.5); colour('pink')                 # a heart, dragged out the same way
stroke([(680 + i * 11, 800 + i * 23) for i in range(24)], 1.5)
tap(*BRUSH, 2.5)                                                  # changing tool settles the heart
open(__import__('sys').argv[1], 'w').write("\n".join(out) + "\n")
