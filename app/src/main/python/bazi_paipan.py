# -*- coding: utf-8 -*-
"""固定排盘实现。模型只传参数，禁止修改本脚本。
依赖: lunar_python（已预装）
"""
import re
from datetime import datetime, timedelta
from lunar_python import Solar, Lunar

TG = '甲乙丙丁戊己庚辛壬癸'
DZ = '子丑寅卯辰巳午未申酉戌亥'
JIE = ['立春', '惊蛰', '清明', '立夏', '芒种', '小暑',
       '立秋', '白露', '寒露', '立冬', '大雪', '小寒']
GAN_WX = {'甲': '木', '乙': '木', '丙': '火', '丁': '火', '戊': '土',
          '己': '土', '庚': '金', '辛': '金', '壬': '水', '癸': '水'}
ZHI_WX = {'寅': '木', '卯': '木', '巳': '火', '午': '火', '申': '金', '酉': '金',
          '亥': '水', '子': '水', '辰': '土', '戌': '土', '丑': '土', '未': '土'}
WEEK = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']


def _section(name, fn):
    try:
        print('\n[' + name + ']')
        fn()
    except Exception as e:
        print('❌ ' + name + ' 计算失败: ' + str(e) + '（本节能用的信息缺失，请勿编造此部分）')


def _fmt_ba(ba):
    return ba.getYear() + ' ' + ba.getMonth() + ' ' + ba.getDay() + ' ' + ba.getTime()


def _jieqi_warn(lunar, birth_dt, label):
    # 出生时刻距任一“节”交接 <2 小时 → 月柱敏感警告
    jt = lunar.getJieQiTable()
    best = None
    for name in JIE:
        v = jt.get(name)
        if v is None:
            continue
        try:
            jdt = datetime(v.getYear(), v.getMonth(), v.getDay(), v.getHour(), v.getMinute())
        except Exception:
            continue
        delta = abs((jdt - birth_dt).total_seconds()) / 3600.0
        if best is None or delta < best[0]:
            best = (delta, name, jdt)
    if best and best[0] < 2.0:
        print('⚠️ 出生时刻距「%s」交接仅 %.1f 小时，月柱取决于精确交司时刻，请确认出生时间无误' % (best[1], best[0]))


def _dayun(lunar, ba, birth_dt, gender):
    year_gan = ba.getYearGan()
    shun = (year_gan in '甲丙戊庚壬') == (gender == 1)
    jt = lunar.getJieQiTable()
    anchor = None
    for name in JIE:
        v = jt.get(name)
        if v is None:
            continue
        try:
            jdt = datetime(v.getYear(), v.getMonth(), v.getDay(), v.getHour(), v.getMinute())
        except Exception:
            continue
        if shun and jdt >= birth_dt:
            if anchor is None or jdt < anchor:
                anchor = jdt
        elif (not shun) and jdt < birth_dt:
            if anchor is None or jdt > anchor:
                anchor = jdt
    if anchor is None:
        print('❌ 起运计算失败：未找到锚点节气')
        return
    days = abs((anchor - birth_dt).total_seconds()) / 86400.0
    qy = int(days // 3)
    qm = int((days % 3) / 3 * 12)
    month_gz = ba.getMonth()
    mg = TG.index(month_gz[0])
    mz = DZ.index(month_gz[1])
    dayun = []
    for i in range(8):
        gi = (mg + i + 1) % 10 if shun else (mg - i - 1) % 10
        zi = (mz + i + 1) % 12 if shun else (mz - i - 1) % 12
        dayun.append(TG[gi] + DZ[zi])
    direction = '顺排' if shun else '逆排'
    print('起运（%s，距%s %.1f 天）: %d岁%d个月' % (direction, anchor.strftime('%m-%d %H:%M'), days, qy, qm))
    print('大运: ' + ' '.join(dayun))


def paipan(year, month, day, hour=None, minute=0, gender=1, is_lunar=0, longitude=120.0):
    """八字排盘。hour=None 表示时辰未知。"""
    try:
        year = int(year); month = int(month); day = int(day); minute = int(minute or 0)
        gender = int(gender); is_lunar = int(is_lunar)
        longitude = float(longitude)
    except Exception:
        print('❌ 参数非法：年/月/日/性别必须为数字')
        return
    if not (1900 <= year <= 2100):
        print('❌ 仅支持 1900-2100 年公历（古代历法有断代问题，超出范围拒绝排盘）')
        return
    if not (1 <= month <= 12 and 1 <= day <= 31):
        print('❌ 月/日超出合法范围')
        return
    if gender not in (0, 1):
        print('❌ gender 必须为 0(女) 或 1(男)')
        return
    unknown_hour = hour is None or str(hour).strip() == ''
    if unknown_hour:
        hour = 0
        print('⚠️ 时辰未知，暂按子时(0点)假设排盘。时柱与部分推演依赖时辰，请提供出生时刻以精确排盘')
    else:
        hour = int(hour)
        if not (0 <= hour <= 23):
            print('❌ 小时超出 0-23 范围')
            return

    # 公历/农历归一
    if is_lunar == 1:
        try:
            solar = Lunar.fromYmdHms(year, month, day, 0, 0, 0).getSolar()
            y2, m2, d2 = solar.getYear(), solar.getMonth(), solar.getDay()
            print('输入为农历%d年%d月%d日，换算公历: %d-%02d-%02d' % (year, month, day, y2, m2, d2))
            year, month, day = y2, m2, d2
        except Exception as e:
            print('❌ 农历转换失败: ' + str(e))
            return

    # 真太阳时（平太阳时经度修正，未含均时差）
    tz_min = (longitude - 120.0) * 4.0
    true_dt = datetime(year, month, day, hour, minute) + timedelta(minutes=tz_min)
    if abs(longitude - 120.0) > 0.01:
        print('真太阳时修正: 经度%s° → 时差%+.0f分钟（平太阳时，未含均时差）' % (longitude, tz_min))
        if true_dt.hour != hour or true_dt.day != day:
            print('⚠️ 真太阳时修正后时辰/日期变化: %s → %s' % (
                '%d-%02d-%02d %02d:%02d' % (year, month, day, hour, minute),
                true_dt.strftime('%Y-%m-%d %H:%M')))
    y2, m2, d2, h2, mi2 = true_dt.year, true_dt.month, true_dt.day, true_dt.hour, true_dt.minute

    solar = Solar.fromYmdHms(y2, m2, d2, h2, mi2, 0)
    lunar = solar.getLunar()
    ba = lunar.getEightChar()
    birth_dt = datetime(y2, m2, d2, h2, mi2)

    def core():
        # 校验行 + 四柱（永远打印在开头，落保留区内）
        print('校验: 公历%d-%02d-%02d %02d:%02d → 农历%s年%s月%s，日柱%s' % (
            y2, m2, d2, h2, mi2, lunar.getYearInChinese(), lunar.getMonthInChinese(),
            lunar.getDayInChinese(), ba.getDay()))
        print('八字: ' + _fmt_ba(ba))
        print('日主: %s(%s) | 生肖: %s' % (ba.getDayGan(), GAN_WX[ba.getDayGan()], lunar.getYearShengXiao()))
        print('纳音: 年%s 月%s 日%s 时%s' % (ba.getYearNaYin(), ba.getMonthNaYin(),
                                              ba.getDayNaYin(), ba.getTimeNaYin()))
        if hour == 23:
            print('⚠️ 时辰为晚子时(23点)。本盘日柱按当日计算（23点不换日）；若按晚子时流派（23点后日柱算次日），请告知流派偏好后重排')

    def shishen():
        print('十神(日干对四柱): 年%s 月%s 日%s 时%s' % (
            ba.getYearShiShenGan(), ba.getMonthShiShenGan(),
            ba.getDayShiShenGan(), ba.getTimeShiShenGan()))
        print('藏干十神: 年[%s] 月[%s] 日[%s] 时[%s]' % (
            '/'.join(ba.getYearShiShenZhi() or []), '/'.join(ba.getMonthShiShenZhi() or []),
            '/'.join(ba.getDayShiShenZhi() or []), '/'.join(ba.getTimeShiShenZhi() or [])))

    _section('四柱', core)
    _section('十神五行', shishen)

    def dayun_sec():
        _dayun(lunar, ba, birth_dt, gender)

    _section('大运', dayun_sec)

    def liunian():
        now_year = datetime.now().year
        gz = TG[(now_year - 4) % 10] + DZ[(now_year - 4) % 12]
        print('当前流年(%d): %s' % (now_year, gz))

    _section('流年', liunian)

    def jieqi():
        _jieqi_warn(lunar, birth_dt, '节气')

    _section('节气', jieqi)
    print('\n<PAIPAN_JSON>{"bazi":"%s","dayMaster":"%s(%s)","lunar":"%s年%s月%s","hour":"%s"}</PAIPAN_JSON>' % (
        _fmt_ba(ba), ba.getDayGan(), GAN_WX[ba.getDayGan()],
        lunar.getYearInChinese(), lunar.getMonthInChinese(), lunar.getDayInChinese(),
        '未知(子时假设)' if unknown_hour else '%02d:%02d' % (hour, minute)))


def date_info(date_str, hour=12, minute=0):
    """日期换算。date_str 支持 2026-8-10 / 2026年8月10日 / 2026/8/10"""
    m = re.match(r'(\d{4})\D+(\d{1,2})\D+(\d{1,2})', str(date_str))
    if not m:
        print('❌ 日期格式无法解析，请用 2026-8-10 或 2026年8月10日')
        return
    y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
    try:
        hour = int(hour); minute = int(minute)
    except Exception:
        hour, minute = 12, 0
    solar = Solar.fromYmdHms(y, mo, d, hour, minute, 0)
    lunar = solar.getLunar()
    ba = lunar.getEightChar()
    jq = lunar.getJieQi()
    print('公历: %d-%02d-%02d %s' % (y, mo, d, WEEK[datetime(y, mo, d).weekday()]))
    print('农历: %s年%s月%s' % (lunar.getYearInChinese(), lunar.getMonthInChinese(), lunar.getDayInChinese()))
    print('干支: %s年 %s月 %s日 %s时' % (ba.getYear(), ba.getMonth(), ba.getDay(), ba.getTime()))
    print('日干: %s(%s) | 生肖: %s | 日纳音: %s' % (
        ba.getDayGan(), GAN_WX[ba.getDayGan()], lunar.getYearShengXiao(), ba.getDayNaYin()))
    print('当日节气: %s' % (jq if jq else '无'))
