"""
Vedic Rectifier — Time Scanner (swisseph版)
=============================================
扫描出生时间±N分钟范围，输出每分钟的Lagna/D9/D10变化。
与 vedic-calculator engine.py 使用相同的天文引擎(swisseph)和Ayanamsa(Lahiri)，
确保 Lagna 度数和 D9/D10 边界完全一致。

用法:
  python time_scan.py --date 2000-01-01 --time 10:30 --lat 28.61 --lon 77.21
  python time_scan.py --date 2000-01-01 --time 10:30 --lat 28.61 --lon 77.21 --range 60

注意: --time 参数为 UTC 时间。中国(UTC+8)需减8小时。

依赖: pip install pyswisseph
"""

import swisseph as swe
import argparse
import sys

# === 与 engine.py 一致的配置 ===
swe.set_sid_mode(swe.SIDM_LAHIRI)

SIGNS = ['Ar', 'Ta', 'Ge', 'Cn', 'Le', 'Vi', 'Li', 'Sc', 'Sg', 'Cp', 'Aq', 'Pi']
SIGNS_CN = ['白羊', '金牛', '双子', '巨蟹', '狮子', '处女', '天秤', '天蝎', '射手', '摩羯', '水瓶', '双鱼']


# === 核心计算函数（与 engine.py 完全一致） ===

def calc_sidereal_asc(jd, lat, lon):
    """
    计算恒星(sidereal)上升点度数。
    与 engine.py calc_lagna() 使用相同的 swe.houses_ex() 调用。
    
    参数:
        jd: Julian Day (UT)
        lat: 纬度
        lon: 经度
    返回: 恒星Lagna绝对度数 (0-360)
    """
    cusps, ascmc = swe.houses_ex(jd, lat, lon, b'W', swe.FLG_SIDEREAL)
    return ascmc[0]


def deg_to_sign(deg):
    """绝对度数 → (星座缩写, 星座中文, 度数在星座内)"""
    sign_idx = int(deg / 30) % 12
    deg_in_sign = deg % 30
    return SIGNS[sign_idx], SIGNS_CN[sign_idx], deg_in_sign


def calc_d9(asc_deg):
    """
    Lagna绝对度数 → D9(Navamsa)星座
    与 engine.py calc_navamsha() 完全一致。
    
    Navamsha规则：每个星座30°分为9等份(3°20')
    起点取决于元素：
      火象(Ar/Le/Sg) → 从Aries(0)开始
      土象(Ta/Vi/Cp) → 从Capricorn(9)开始
      风象(Ge/Li/Aq) → 从Libra(6)开始
      水象(Cn/Sc/Pi) → 从Cancer(3)开始
    """
    sign = int(asc_deg / 30) % 12
    deg_in_sign = asc_deg % 30
    nav_part = int(deg_in_sign / (30.0 / 9))  # 0-8

    element = sign % 4  # 0=火, 1=土, 2=风, 3=水
    start_signs = [0, 9, 6, 3]  # Ar, Cp, Li, Cn
    d9_sign = (start_signs[element] + nav_part) % 12
    return SIGNS[d9_sign], SIGNS_CN[d9_sign]


def calc_d10(asc_deg):
    """
    Lagna绝对度数 → D10(Dashamsha)星座
    与 engine.py calc_dashamsha() 完全一致。
    
    Dashamsha规则：每个星座30°分为10等份(3°)
    起点取决于奇偶：
      奇数星座(Ar/Ge/Le/Li/Sg/Aq) → 从本星座开始
      偶数星座(Ta/Cn/Vi/Sc/Cp/Pi) → 从本星座+9开始
    """
    sign = int(asc_deg / 30) % 12
    deg_in_sign = asc_deg % 30
    das_part = int(deg_in_sign / 3.0)  # 0-9
    if das_part > 9:
        das_part = 9

    is_odd = (sign % 2 == 0)  # Ar=0(奇), Ta=1(偶)...
    if is_odd:
        d10_sign = (sign + das_part) % 12
    else:
        d10_sign = (sign + das_part + 8) % 12
    return SIGNS[d10_sign], SIGNS_CN[d10_sign]


def scan(date_str, time_str, lat, lon, range_min=30):
    """
    扫描时间范围，输出每分钟的Lagna变化。
    
    参数:
        date_str: "YYYY-MM-DD"
        time_str: "HH:MM" (UTC)
        lat, lon: 出生地纬度/经度
        range_min: 扫描范围（±分钟）
    
    返回: list of dict
    """
    # 解析日期和时间
    parts = date_str.split('-')
    year, month, day = int(parts[0]), int(parts[1]), int(parts[2])
    h, m = map(int, time_str.split(':'))
    
    # 计算基准 Julian Day (UTC)
    ut_hour = h + m / 60.0
    base_jd = swe.julday(year, month, day, ut_hour)
    
    results = []
    prev_sign = None
    prev_d9 = None

    for delta in range(-range_min, range_min + 1):
        jd = base_jd + delta / 1440.0  # 1分钟 = 1/1440天
        
        asc_deg = calc_sidereal_asc(jd, lat, lon)
        sign, sign_cn, deg_in_sign = deg_to_sign(asc_deg)
        d9, d9_cn = calc_d9(asc_deg)
        d10, d10_cn = calc_d10(asc_deg)

        # 标记变化点
        markers = []
        if prev_sign and sign != prev_sign:
            markers.append(f"★ LAGNA换座→{sign_cn}")
        if prev_d9 and d9 != prev_d9:
            markers.append(f"◆ D9换座→{d9_cn}")

        results.append({
            'delta': delta,
            'asc_deg': asc_deg,
            'sign': sign,
            'sign_cn': sign_cn,
            'deg_in_sign': deg_in_sign,
            'd9': d9,
            'd9_cn': d9_cn,
            'd10': d10,
            'd10_cn': d10_cn,
            'markers': ' '.join(markers),
        })

        prev_sign = sign
        prev_d9 = d9

    return results


def print_results(results, date_str, time_str, lat, lon):
    """格式化输出扫描结果"""
    print(f"# 时间扫描结果")
    print(f"# 基准: {date_str} {time_str} UTC | 坐标: ({lat}, {lon})")
    print(f"# 引擎: swisseph + Lahiri Ayanamsa (与calc engine一致)")
    print(f"# 范围: {results[0]['delta']:+d} ~ {results[-1]['delta']:+d} 分钟")
    print()
    print(f"{'偏移':>6} | {'Lagna度数':>10} | {'星座':>6} | {'座内度数':>8} | {'D9':>4} | {'D10':>4} | 标记")
    print("-" * 75)

    for r in results:
        marker_str = f"  {r['markers']}" if r['markers'] else ""
        is_base = " ← 原始" if r['delta'] == 0 else ""
        print(f"{r['delta']:+4d}min | {r['asc_deg']:8.2f}° | {r['sign']:>4}{r['sign_cn']} | {r['deg_in_sign']:6.2f}° | {r['d9']:>4} | {r['d10']:>4} |{marker_str}{is_base}")


def save_results(results, date_str, time_str, lat, lon, filepath):
    """保存为Markdown表格"""
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(f"# 时间扫描结果\n\n")
        f.write(f"> 基准: {date_str} {time_str} UTC\n")
        f.write(f"> 坐标: ({lat}, {lon})\n")
        f.write(f"> 引擎: swisseph + Lahiri Ayanamsa\n\n")
        f.write(f"| 偏移 | Lagna度数 | 星座 | D9 | D10 | 标记 |\n")
        f.write(f"|------|----------|------|-----|------|------|\n")

        for r in results:
            base = " ← 原始" if r['delta'] == 0 else ""
            marker = r['markers'] + base
            f.write(f"| {r['delta']:+4d}min | {r['asc_deg']:.2f}° | "
                    f"{r['sign']} {r['deg_in_sign']:.1f}° | "
                    f"{r['d9']} | {r['d10']} | {marker} |\n")

    print(f"\n已保存: {filepath}")


def main():
    parser = argparse.ArgumentParser(description='Vedic Rectifier Time Scanner (swisseph)')
    parser.add_argument('--date', required=True, help='出生日期 YYYY-MM-DD')
    parser.add_argument('--time', required=True, help='预估出生时间 HH:MM (UTC)')
    parser.add_argument('--lat', required=True, type=float, help='出生地纬度')
    parser.add_argument('--lon', required=True, type=float, help='出生地经度')
    parser.add_argument('--range', type=int, default=30, help='扫描范围±分钟 (默认30)')
    parser.add_argument('--save', type=str, help='保存结果到文件路径')

    args = parser.parse_args()

    results = scan(args.date, args.time, args.lat, args.lon, args.range)
    print_results(results, args.date, args.time, args.lat, args.lon)

    if args.save:
        save_results(results, args.date, args.time, args.lat, args.lon, args.save)

    # 输出变化点摘要
    print("\n## 关键变化点")
    for r in results:
        if r['markers']:
            print(f"  {r['delta']:+4d}min: {r['markers']}")


if __name__ == '__main__':
    main()
