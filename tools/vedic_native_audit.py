#!/usr/bin/env python3
"""Strict reproducible PyJHora native audit on the public 2004 Shanghai gold chart."""
from __future__ import annotations
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCRIPTS = REPO / "skills" / "vedic-calculator" / "scripts"
sys.path.insert(0, str(SCRIPTS))
import swisseph as swe
from engine import calculate_full_chart, ENGINE_VERSION
from formatter import format_structured_data

OUT = REPO / "vedic-native-output"
OUT.mkdir(exist_ok=True)
GOLD = {
    "Lagna": 210+7+38.50/3600,
    "Sun": 120+25+48/60+5.43/3600,
    "Moon": 90+26+39/60+42.79/3600,
    "Mars": 120+26+56/60+14.73/3600,
    "Mercury": 120+8+13/60+11.32/3600,
    "Jupiter": 150+3+17/60+40.94/3600,
    "Venus": 90+11+44/60+32.27/3600,
    "Saturn": 90+0+36/60+38.53/3600,
    "Ayanamsa": 23+54/60+24.90/3600,
}
def arcsec(a, b):
    return (((a-b+180)%360)-180)*3600

def main():
    # Desktop JHora reference: 2004-09-12 11:15:27; 31°06'N, 121°22'E
    c = calculate_full_chart(2004,9,12,11,15,31+6/60,121+22/60,
                             "Asia/Shanghai", second=27)
    vals = {n:c["planets"][n]["longitude"] for n in GOLD
            if n not in ("Lagna","Ayanamsa")}
    vals["Lagna"] = c["lagna"]["longitude"]
    vals["Ayanamsa"] = c["ayanamsa"]
    errors = {n:round(arcsec(vals[n],v),6) for n,v in GOLD.items()}
    assert all(abs(e)<=0.5 for e in errors.values()), f"GOLD_PRECISION_FAILED: {errors}"
    assert c["calc_meta"]["engine_version"] == ENGINE_VERSION
    assert not c.get("calc_errors"), f"INCOMPLETE_CHART: {c.get('calc_errors')}"
    assert len(c["sav"])==12 and sum(c["sav"].values())==337, c["sav"]
    assert len(c["dashas"])==9, "Expected 9 MD"
    assert sum(len(x["antardashas"]) for x in c["dashas"])==81, "Expected 81 AD"
    assert c.get("shadbala"), "PyJHora Shadbala is absent"
    assert "Lagna" in c["divisional_charts"]["D9"], "PyJHora D9 is absent"
    assert abs((c["planets"]["Rahu"]["longitude"]-
                c["planets"]["Ketu"]["longitude"])%360-180)<1e-7

    md = format_structured_data(c,None,
        {"dob":"2004-09-12","time":"11:15:27","place":"上海 JHora 金标准",
         "lat":31.1,"lon":121+22/60,
         "time_precision":"秒级","time_source":"桌面 JHora 参考数据"},
        {"gender":"未指定","relationship":"未指定"})
    out_md=OUT/"gold_standard_structured_data.md"
    out_md.write_text(md,encoding="utf-8")
    pd=subprocess.run([sys.executable,str(SCRIPTS/"dasha_query.py"),
                       "--file",str(out_md),"--check"],
                      capture_output=True,text=True)
    assert pd.returncode==0 and "PD_OK: 729/729" in pd.stdout, (pd.stdout,pd.stderr)
    result={
      "status":"PASS",
      "engine_version":ENGINE_VERSION,
      "gold_standard_threshold_arcsec":0.5,
      "gold_standard_error_arcsec":errors,
      "swe_version":swe.version,
      "sav_total":sum(c["sav"].values()),
      "sav_by_sign":c["sav"],
      "dasha_counts":{"MD":9,"AD":81,"PD":729},
      "shadbala":c["shadbala"],
      "d9_lagna":c["divisional_charts"]["D9"]["Lagna"],
      "calc_meta":c["calc_meta"],
      "pd_validation":pd.stdout.strip(),
      "notice":"Public reference chart only, not a private natal chart."
    }
    (OUT/"gold_standard_audit.json").write_text(
      json.dumps(result,ensure_ascii=False,indent=2,default=str),encoding="utf-8")
    print("PASS: gold <=0.5 arcsec; SAV=337; MD=9 AD=81 PD=729; Shadbala present")
    print("Gold arcsec errors:",json.dumps(errors,ensure_ascii=False))
    print("Artifact:",OUT)
if __name__=="__main__":
    main()
