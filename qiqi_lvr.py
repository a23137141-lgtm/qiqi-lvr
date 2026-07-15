"""
台中七期重劃區 實價登錄自動彙整
================================================
資料源：內政部不動產成交案件實際資訊資料供應系統
        https://plvr.land.moi.gov.tw/DownloadOpenData
發布日：每月 1、11、21 日

七期範圍採「地段」認定，段別依台中市政府地政局公告之第七期市地
重劃區重劃後段別（共 10 段）。這是官方定義，涵蓋北七期、南七期、
西七期全部範圍，且不受行政區（西屯 / 南屯）切割影響。

用法：
    python qiqi_lvr.py                 # 抓本期，寫入 DB + 匯出 docs/data.json
    python qiqi_lvr.py 115S2           # 灌歷史季度資料（民國年+S+季）
"""

from __future__ import annotations

import io
import json
import re
import sqlite3
import sys
import zipfile
from datetime import date, datetime, timezone
from pathlib import Path

import pandas as pd
import requests

# ══════════════════════════════════════════════
# 設定
# ══════════════════════════════════════════════

# 台中市地政局公告：第七期市地重劃區重劃後段別
QIQI_SECTIONS = (
    "惠國", "惠泰", "惠民", "惠安", "惠順",
    "惠仁", "惠義", "惠禮", "惠智", "惠信",
)
SECTION_RE = re.compile("|".join(f"{s}段" for s in QIQI_SECTIONS))

CITY = "b"  # 台中市代碼（A台北 B台中 E高雄 F新北 H桃園 D台南…）

# ⚠️ 兩個係數是倒數關係，寫反不會報錯，只會安靜給出錯誤數字
SQM_TO_PING = 0.3025      # 面積：平方公尺 → 坪
PRICE_TO_PING = 3.305785  # 單價：元/m²   → 元/坪

DB_PATH = Path("qiqi.db")
JSON_PATH = Path("docs/data.json")

# 本期買賣批次資料的直連。出處：政府資料開放平臺 dataset 25119 官方詮釋資料
# （「本期發布之不動產買賣實價登錄批次資料」，每月 1、11、21 發布 1 次）
DIRECT_URL = "https://plvr.land.moi.gov.tw/opendata/lvr_landAcsv.zip"
# 直連掛掉時回頭問這裡現在的網址。該資料集是「系統介接程式」自動上架，
# 官方改路徑時這頁的連結會跟著更新 → 等於免費的自我修復。
DATASET_URL = "https://data.gov.tw/dataset/25119"


# ══════════════════════════════════════════════
# 1. 下載
# ══════════════════════════════════════════════

def _as_zip(r: requests.Response) -> zipfile.ZipFile:
    """政府網站常常回 200 + 一頁錯誤 HTML，光看 status code 會被騙"""
    r.raise_for_status()
    if not r.content.startswith(b"PK"):
        raise ValueError(f"回傳的不是 zip（開頭 {r.content[:24]!r}）")
    return zipfile.ZipFile(io.BytesIO(r.content))


def fetch_zip(season: str | None = None) -> zipfile.ZipFile:
    """
    season=None    → 本期（每月 1/11/21 發布的當期資料）
    season='115S2' → 指定季度，用來一次灌歷史資料
    """
    if season:
        return _as_zip(requests.get(
            "https://plvr.land.moi.gov.tw/DownloadSeason",
            params={"season": season, "type": "zip", "fileName": "lvr_landcsv.zip"},
            timeout=180,
        ))

    try:
        return _as_zip(requests.get(DIRECT_URL, timeout=180))
    except Exception as e:
        print(f"⚠️  直連失敗（{e}），改問 data.gov.tw 現在的網址")
        html = requests.get(DATASET_URL, timeout=60).text
        urls = re.findall(r"https://plvr\.land\.moi\.gov\.tw/\S*?csv\.zip", html)
        if not urls:
            raise RuntimeError("data.gov.tw 也找不到下載連結，官方八成是改版了")
        print(f"→ 改用 {urls[0]}")
        return _as_zip(requests.get(urls[0], timeout=180))


# ══════════════════════════════════════════════
# 2. 讀取 + 七期地段比對
# ══════════════════════════════════════════════

def read_csv(zf: zipfile.ZipFile, name: str) -> pd.DataFrame:
    """實價登錄 CSV 第 2 列是英文欄位名，是資料不是標題，要丟掉"""
    with zf.open(name) as f:
        df = pd.read_csv(f, dtype=str, encoding="utf-8")
    return df.iloc[1:].reset_index(drop=True)


def _first_section(text: str | None) -> str | None:
    m = SECTION_RE.search(text or "")
    return m.group(0) if m else None


def match_sections(zf: zipfile.ZipFile, main: pd.DataFrame) -> pd.Series:
    """
    回傳每筆交易命中的段名（例：「惠國段」），非七期則為 NaN。
    兩路來源：
      (a) 主表「土地位置建物門牌」— 純土地交易會直接寫地段地號
      (b) 子表 *_land.csv「土地位置」— 房地交易顯示門牌，地段要從這裡回填
    """
    sec = main["土地位置建物門牌"].fillna("").map(_first_section)

    land_name = f"{CITY}_lvr_land_a_land.csv"
    if land_name in zf.namelist():
        land = read_csv(zf, land_name)
        # 官方偶有調整欄位名，這裡做模糊比對；跑不動就 print(land.columns) 看一下
        col = next((c for c in land.columns if "位置" in c), None)
        if col:
            land["_sec"] = land[col].fillna("").map(_first_section)
            lookup = (
                land.dropna(subset=["_sec"])
                    .drop_duplicates("編號")
                    .set_index("編號")["_sec"]
            )
            sec = sec.fillna(main["編號"].map(lookup))
    else:
        print(f"⚠️  找不到 {land_name}，房地交易的地段無法回填，會漏案件")

    return sec


# ══════════════════════════════════════════════
# 3. 換算（坪、民國年、扣車位）
# ══════════════════════════════════════════════

def num(s: pd.Series) -> pd.Series:
    return pd.to_numeric(s, errors="coerce").fillna(0)


def transform(df: pd.DataFrame, sections: pd.Series) -> pd.DataFrame:
    out = pd.DataFrame()
    out["編號"] = df["編號"]
    out["地段"] = sections
    out["行政區"] = df["鄉鎮市區"]
    out["地址"] = df["土地位置建物門牌"]
    out["交易標的"] = df["交易標的"]
    out["建物型態"] = df["建物型態"]

    # 民國 1150520 → 2026-05-20
    d = df["交易年月日"].fillna("").str.zfill(7)
    out["交易日"] = pd.to_datetime(
        (pd.to_numeric(d.str[:3], errors="coerce") + 1911).astype("Int64").astype(str)
        + d.str[3:5] + d.str[5:7],
        format="%Y%m%d", errors="coerce",
    ).dt.strftime("%Y-%m-%d")

    total = num(df["總價元"])
    park_price = num(df["車位總價元"])
    build_sqm = num(df["建物移轉總面積平方公尺"])
    park_sqm = num(df.get("車位移轉總面積(平方公尺)", df.get("車位移轉總面積平方公尺")))

    out["總價萬"] = (total / 1e4).round(1)
    out["車位總價萬"] = (park_price / 1e4).round(1)
    out["建物坪"] = (build_sqm * SQM_TO_PING).round(2)
    out["車位坪"] = (park_sqm * SQM_TO_PING).round(2)

    # 官方單價：含車位，在七期會被大幅稀釋（大坪數＋雙車位是常態）
    out["官方單價萬坪"] = (num(df["單價元平方公尺"]) * PRICE_TO_PING / 1e4).round(2)

    # 扣車位後的真實房屋單價 ← 網站上該顯示的是這個
    net_sqm = (build_sqm - park_sqm).where(lambda s: s > 0)
    out["房屋單價萬坪"] = ((total - park_price) / net_sqm * PRICE_TO_PING / 1e4).round(2)

    out["格局"] = (
        df["建物現況格局-房"].fillna("") + "房"
        + df["建物現況格局-廳"].fillna("") + "廳"
        + df["建物現況格局-衛"].fillna("") + "衛"
    )
    out["樓層"] = df["移轉層次"].fillna("") + " / " + df["總樓層數"].fillna("")
    out["備註"] = df["備註"]
    return out


# ══════════════════════════════════════════════
# 4. 入庫（每期累積留存）
# ══════════════════════════════════════════════

SCHEMA = """
CREATE TABLE IF NOT EXISTS deals (
    編號          TEXT PRIMARY KEY,
    地段          TEXT,
    行政區        TEXT,
    地址          TEXT,
    交易標的      TEXT,
    建物型態      TEXT,
    交易日        TEXT,
    總價萬        REAL,
    車位總價萬    REAL,
    建物坪        REAL,
    車位坪        REAL,
    官方單價萬坪  REAL,
    房屋單價萬坪  REAL,
    格局          TEXT,
    樓層          TEXT,
    備註          TEXT,
    first_seen    TEXT,   -- 首次揭露的期別 →「本期新公布」查這欄
    last_seen     TEXT    -- 最後更新的期別（官方會事後更正案件）
);
CREATE INDEX IF NOT EXISTS idx_first_seen ON deals(first_seen);
CREATE INDEX IF NOT EXISTS idx_deal_date  ON deals(交易日);
CREATE INDEX IF NOT EXISTS idx_section    ON deals(地段);
"""


def upsert(df: pd.DataFrame, period: str, db: Path = DB_PATH) -> int:
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)

    cols = list(df.columns)
    placeholders = ",".join("?" * (len(cols) + 2))
    # first_seen 故意不進 UPDATE，保留首次揭露的期別
    updates = ",".join(f"{c}=excluded.{c}" for c in cols if c != "編號")
    sql = (
        f"INSERT INTO deals ({','.join(cols)}, first_seen, last_seen) "
        f"VALUES ({placeholders}) "
        f"ON CONFLICT(編號) DO UPDATE SET {updates}, last_seen=excluded.last_seen"
    )

    clean = df.astype(object).where(pd.notna(df), None)
    rows = [tuple(r) + (period, period) for r in clean.values]

    before = con.execute("SELECT COUNT(*) FROM deals").fetchone()[0]
    con.executemany(sql, rows)
    con.commit()
    after = con.execute("SELECT COUNT(*) FROM deals").fetchone()[0]
    con.close()
    return after - before


# ══════════════════════════════════════════════
# 5. 匯出給前端的 JSON
# ══════════════════════════════════════════════

def export_json(out: Path = JSON_PATH, db: Path = DB_PATH) -> int:
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    rows = [dict(r) for r in con.execute(
        "SELECT * FROM deals WHERE 交易日 IS NOT NULL ORDER BY 交易日 DESC"
    )]
    latest = con.execute("SELECT MAX(first_seen) FROM deals").fetchone()[0]
    con.close()

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "latest_period": latest,
                "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sections": list(QIQI_SECTIONS),
                "deals": rows,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )
    return len(rows)


# ══════════════════════════════════════════════
# 6. 主流程（cron 排 1/11/21 日 12:00）
# ══════════════════════════════════════════════

def run(period: str, season: str | None = None) -> None:
    zf = fetch_zip(season)
    main = read_csv(zf, f"{CITY}_lvr_land_a.csv")  # a=成屋買賣 b=預售屋 c=租賃

    sections = match_sections(zf, main)
    qiqi = main[sections.notna()]
    df = transform(qiqi, sections[sections.notna()])

    # 七期整期 0 筆成交幾乎不可能，比較可能是欄位名或格式變了。
    # 寧可讓 Actions 失敗開 issue 叫人，也不要安靜地把空資料蓋上去。
    if len(df) == 0:
        raise RuntimeError(
            f"七期 0 筆命中（主表 {len(main)} 筆）。八成是欄位或格式變了，"
            f"中止以免污染 DB。主表欄位：{list(main.columns)[:8]}…"
        )

    new = upsert(df, period)
    total = export_json()
    print(f"[{period}] 七期命中 {len(df)} 筆，其中新公布 {new} 筆；JSON 共 {total} 筆")


if __name__ == "__main__":
    season = sys.argv[1] if len(sys.argv) > 1 else None
    run(period=season or date.today().strftime("%Y%m%d"), season=season)
