# -*- coding: utf-8 -*-
"""金标准验证：lunar_python（App 运行时库）vs sxtwl（寿星天文历，独立实现）
1900-2100 均匀采样 + 边界日期，四柱/农历交叉比对。

已知口径差异（两库均为合理实现，属流派/精度问题而非 bug）：
1. 晚子时(23点)：时干按次日日干推 vs 按当日日干推 —— 跳过时柱比对
2. 节气交司当天±2天：两库节气时刻算到分钟级有差异（lunar_python 立春按整天切换年柱），年/月柱归属不同 —— 跳过年月柱比对
其余任何不一致都判失败。两库独立实现同时出错概率趋近于零。
用法: python verify_bazi.py [采样数]
"""
import sys
import os
import random

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app", "src", "main", "python"))
from lunar_python import Solar
import sxtwl

GAN = '甲乙丙丁戊己庚辛壬癸'
ZHI = '子丑寅卯辰巳午未申酉戌亥'
JIE = ['立春', '惊蛰', '清明', '立夏', '芒种', '小暑',
       '立秋', '白露', '寒露', '立冬', '大雪', '小寒']


def bazi_lunar_python(y, m, d, h):
    s = Solar.fromYmdHms(y, m, d, h, 0, 0)
    ba = s.getLunar().getEightChar()
    return ba.getYear(), ba.getMonth(), ba.getDay(), ba.getTime()


def bazi_sxtwl(y, m, d, h):
    day = sxtwl.fromSolar(y, m, d)
    dtg = day.getDayGZ()
    hour_zhi = (h + 1) // 2 % 12
    htg = (dtg.tg % 5 * 2 + hour_zhi) % 10
    return (GAN[day.getYearGZ().tg] + ZHI[day.getYearGZ().dz],
            GAN[day.getMonthGZ().tg] + ZHI[day.getMonthGZ().dz],
            GAN[dtg.tg] + ZHI[dtg.dz],
            GAN[htg] + ZHI[hour_zhi])


def near_jieqi(y, m, d):
    """是否距任一节气交司日 2 天内（月柱敏感区）"""
    import datetime
    for year in (y - 1, y, y + 1):
        try:
            lunar = Solar.fromYmdHms(year, 6, 1, 12, 0, 0).getLunar()
            jt = lunar.getJieQiTable()
            for name in JIE:
                v = jt.get(name)
                if v is None:
                    continue
                jd = datetime.date(v.getYear(), v.getMonth(), v.getDay())
                if abs((datetime.date(y, m, d) - jd).days) <= 2:
                    return True
        except Exception:
            continue
    return False


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    random.seed(42)
    fails = 0
    skipped = 0

    samples = []
    for _ in range(n):
        y = random.randint(1900, 2100)
        m = random.randint(1, 12)
        d = random.randint(1, 28)
        h = random.randint(0, 23)
        samples.append((y, m, d, h))

    edges = [
        (2024, 2, 9, 12), (2024, 2, 10, 12),
        (2026, 2, 3, 12), (2026, 2, 4, 12), (2026, 2, 5, 12),
        (2026, 8, 6, 12), (2026, 8, 7, 12), (2026, 8, 8, 12),
        (2000, 2, 29, 12),
        (1990, 5, 3, 15), (2005, 6, 1, 12),
        (1988, 10, 25, 12), (2023, 12, 31, 23), (2024, 1, 1, 0),
        (2005, 6, 1, 23), (1918, 9, 8, 8), (2039, 10, 8, 15),
    ]
    samples += edges

    checked = 0
    for (y, m, d, h) in samples:
        try:
            b1 = bazi_lunar_python(y, m, d, h)
        except Exception as e:
            print(f'[{y}-{m}-{d} {h}] lunar_python 异常: {e}')
            fails += 1
            continue
        try:
            b2 = bazi_sxtwl(y, m, d, h)
        except Exception as e:
            print(f'[{y}-{m}-{d} {h}] sxtwl 异常: {e}')
            fails += 1
            continue
        checked += 1
        skip_time = (h == 23)
        skip_month = near_jieqi(y, m, d)
        bad = []
        if b1[0] != b2[0] and not skip_month:
            bad.append(f'年柱 {b1[0]} vs {b2[0]}')
        if b1[1] != b2[1] and not skip_month:
            bad.append(f'月柱 {b1[1]} vs {b2[1]}')
        if b1[2] != b2[2]:
            bad.append(f'日柱 {b1[2]} vs {b2[2]}')
        if b1[3] != b2[3] and not skip_time:
            bad.append(f'时柱 {b1[3]} vs {b2[3]}')
        if bad:
            fails += 1
            print(f'[{y}-{m:02d}-{d:02d} {h:02d}] 不一致: {"; ".join(bad)}')
        else:
            if skip_time or skip_month:
                skipped += 1
        if fails > 20:
            print('错误过多，提前终止')
            break

    print(f'\n校验完成: {checked} 个时间点，失败 {fails} 个，口径跳过 {skipped} 个')
    if fails == 0:
        print('✅ 双库交叉验证全部通过（口径差异之外完全一致）')
    else:
        print('❌ 存在不一致，需排查')
    return 0 if fails == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
