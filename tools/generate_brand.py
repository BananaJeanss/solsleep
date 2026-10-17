#!/usr/bin/env python3
"""Convert the hand-authored, path-only brand SVGs to Android vectors."""
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
ATTR = {"fill":"fillColor","stroke":"strokeColor","stroke-width":"strokeWidth",
        "stroke-linecap":"strokeLineCap","stroke-linejoin":"strokeLineJoin",
        "fill-opacity":"fillAlpha"}
for source, target in [("solsleep.svg","ic_sleeping_android.xml"),
                       ("solsleep-monochrome.svg","ic_sleeping_android_mono.xml")]:
    svg = ET.parse(ROOT / "assets" / source).getroot()
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    for p in svg.findall("{http://www.w3.org/2000/svg}path"):
        attrs = {"pathData":p.attrib["d"], "fillColor":"#00000000"}
        for key, value in p.attrib.items():
            if key in ATTR: attrs[ATTR[key]] = "#00000000" if value == "none" else value
        if p.attrib.get("fill-rule") == "evenodd": attrs["fillType"] = "evenOdd"
        lines.append("    <path " + " ".join('android:'+key+'="'+value+'"' for key,value in attrs.items()) + " />")
    lines.append("</vector>")
    (ROOT / "app/src/main/res/drawable" / target).write_text("\n".join(lines)+"\n")
