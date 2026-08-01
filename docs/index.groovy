<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>投資日記 — TradeLog Pro</title>
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=Noto+Sans+TC:wght@300;400;500;700&display=swap" rel="stylesheet">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@latest/tabler-icons.min.css">
<script src="https://www.gstatic.com/firebasejs/10.12.0/firebase-app-compat.js"></script>
<script src="https://www.gstatic.com/firebasejs/10.12.0/firebase-auth-compat.js"></script>
<script src="https://www.gstatic.com/firebasejs/10.12.0/firebase-firestore-compat.js"></script>
<script>
const firebaseConfig = {
  apiKey: "AIzaSyAdirABkWK9l0sRSceoBampnY6HCxFnKWg",
  authDomain: "a23137141-7abcc.firebaseapp.com",
  databaseURL: "https://a23137141-7abcc-default-rtdb.firebaseio.com",
  projectId: "a23137141-7abcc",
  storageBucket: "a23137141-7abcc.firebasestorage.app",
  messagingSenderId: "734833042547",
  appId: "1:734833042547:web:b8ac0882ec0d9d06faf606",
  measurementId: "G-JG893H0G1R"
};
firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db   = firebase.firestore();
const provider = new firebase.auth.GoogleAuthProvider();
const serverTimestamp = firebase.firestore.FieldValue.serverTimestamp;
let currentUser = null;
window.loginGoogle = async () => {
  try { await auth.signInWithPopup(provider); }
  catch(e) {
    if(e.code==='auth/popup-blocked'||e.code==='auth/cancelled-popup-request'){ await auth.signInWithRedirect(provider); }
    else { alert('登入失敗：'+e.message); }
  }
};
window.logoutUser = async () => { await auth.signOut(); };
auth.onAuthStateChanged(async user => {
  currentUser = user;
  if (user) {
    document.getElementById('auth-screen').style.display = 'none';
    document.getElementById('app-screen').style.display  = 'flex';
    const av = document.getElementById('user-avatar');
    const un = document.getElementById('user-name');
    if (av) av.src = user.photoURL || '';
    if (un) un.textContent = user.displayName || user.email;
    await loadAllData();
    // updateStatsCards 和 renderCal 在 Block 6 的 auth listener 裡一起執行
    // （_calCache 必須先載入才能計算統計）
  } else {
    document.getElementById('auth-screen').style.display = 'flex';
    document.getElementById('app-screen').style.display  = 'none';
  }
});
function userCol(col){ return db.collection('users').doc(currentUser.uid).collection(col); }
function userDoc(col,id){ return db.collection('users').doc(currentUser.uid).collection(col).doc(id); }
async function loadAllData(){ await Promise.all([loadInventory(), loadPlaybooks(), loadJournals()]); }
window.saveInventory = async () => {
  const rows = document.querySelectorAll('.inv-row');
  const data = [];
  rows.forEach(row => {
    const inputs = row.querySelectorAll('input');
    data.push({ name:inputs[0]?.value||'', shares:parseFloat(inputs[1]?.value)||0, cost:parseFloat(inputs[2]?.value)||0, price:parseFloat(inputs[3]?.value)||0, pattern:inputs[4]?.value||'' });
  });
  await userDoc('data','inventory').set({ items: data, updatedAt: serverTimestamp() });
  showToast('庫存已儲存 ✓');
};
async function loadInventory(){
  const snap = await userDoc('data','inventory').get();
  if (!snap.exists) return;
  const items = snap.data().items || [];
  const container = document.getElementById('inv-rows');
  if (!container) return;
  container.innerHTML = '';
  let idCount = 0;
  items.forEach(item => { idCount++; addInvRowData(idCount, item); });
  window.invN = idCount;
  // 自動刷新現價
  if(items.length > 0) refreshInvPrices(items);
}

// ── 自動更新庫存現價（FinMind API）────────────────────────
async function refreshInvPrices(items){
  const token = localStorage.getItem('tradelog_finmind') || '';
  if(!token){ return; } // 無 token 就跳過，靜默處理

  const today = new Date().toISOString().slice(0,10);
  const start = new Date(); start.setDate(start.getDate()-5);
  const startStr = start.toISOString().slice(0,10);

  // 批次抓每檔收盤價
  const priceMap = {};
  await Promise.all(items.map(async item => {
    if(!item.name) return;
    // 嘗試從名稱抓代號（純數字或英文大寫）
    const codeMatch = item.name.match(/\b(\d{4,6}|[A-Z]{2,6})\b/);
    const code = codeMatch ? codeMatch[1] : item.name.trim();
    try{
      const params = new URLSearchParams({ dataset:'TaiwanStockPrice', data_id:code, start_date:startStr, end_date:today, token });
      const res  = await fetch(`https://tradelog-proxy.a23137141.workers.dev/finmind?${params}`);
      const json = await res.json();
      if(json.status===200 && json.data && json.data.length > 0){
        const sorted = json.data.sort((a,b)=>b.date.localeCompare(a.date));
        priceMap[item.name] = parseFloat(sorted[0].close);
      }
    } catch(e){ /* 靜默失敗 */ }
  }));

  if(Object.keys(priceMap).length === 0) return;

  // 更新 DOM 輸入欄的現價
  document.querySelectorAll('.inv-row').forEach(row => {
    const nameInp  = row.querySelector('.inv-name');
    const numInputs= row.querySelectorAll('input[type=number]');
    const name = nameInp?.value?.trim();
    if(!name || !priceMap[name]) return;
    const priceInp = numInputs[2]; // 現在價格是第3個 number input
    if(priceInp){
      priceInp.value = priceMap[name].toFixed(1);
      // 觸發損益重算
      const rowId = row.id;
      if(rowId) calcPnl(rowId);
    }
  });

  // 同步寫回 Firestore（更新 price 欄位）
  const snap = await userDoc('data','inventory').get();
  if(!snap.exists) return;
  const savedItems = snap.data().items || [];
  let changed = false;
  savedItems.forEach(item => {
    if(priceMap[item.name]){
      item.price = priceMap[item.name];
      changed = true;
    }
  });
  if(changed){
    await userDoc('data','inventory').set({ items: savedItems, updatedAt: serverTimestamp() }, {merge:true});
    showToast('現價已自動更新 ✓');
  }
}

// 手動觸發刷新
window.refreshPricesNow = async () => {
  const snap = await userDoc('data','inventory').get();
  const items = snap.exists ? snap.data().items || [] : [];
  if(items.length === 0){ showToast('庫存為空'); return; }
  showToast('更新現價中...');
  await refreshInvPrices(items);
};
window.addInvRowData = function(n, item={}) {
  const id='ir-'+n;
  const div=document.createElement('div');
  div.className='inv-row'; div.id=id;
  const pnl=((item.price||0)-(item.cost||0))*(item.shares||0);
  const pct=item.cost>0?((item.price-item.cost)/item.cost)*100:0;
  const pnlStr=(pnl>=0?'+':'')+Math.round(pnl).toLocaleString();
  const pctStr=(pct>=0?'+':'')+pct.toFixed(1)+'%';
  div.innerHTML=`<input class="di inv-name" type="text" value="${item.name||''}" placeholder="股票名稱 / 代號">
    <input class="di inv-num" type="number" value="${item.shares||0}" oninput="calcPnl('${id}')">
    <input class="di inv-num upv" type="number" step="0.5" value="${item.cost||0}" oninput="calcPnl('${id}')">
    <input class="di inv-num" type="number" step="0.5" value="${item.price||0}" oninput="calcPnl('${id}')">
    <div class="di inv-pnl ${pnl>=0?'up':'dn'}" id="${id}-pnl">${pnlStr}</div>
    <div class="di inv-pct ${pct>=0?'up':'dn'}" id="${id}-pct">${pctStr}</div>
    <input class="di inv-pat" type="text" value="${item.pattern||''}" placeholder="型態">
    <button onclick="delInvRow('${id}')" style="background:none;border:none;color:var(--t3);cursor:pointer;font-size:15px;padding:0;"><i class="ti ti-x"></i></button>`;
  document.getElementById('inv-rows').appendChild(div);
};
window.savePlaybook = async (pbId) => {
  const card=document.getElementById(pbId); if(!card) return;
  const num=pbId.replace('pb-','');
  const nameEl=document.getElementById('pb'+num+'-n');
  const typeEl=document.getElementById('pb'+num+'-t');
  const g = id => document.getElementById('pb'+num+'-'+id)?.value?.trim() || '';
  const gf= id => parseFloat(document.getElementById('pb'+num+'-'+id)?.value) || null;
  const data={
    name:             nameEl?.textContent||'',
    type:             typeEl?.className?.includes('rev')?'rev':'trend',
    stocks:           g('stocks'),
    catalyst:         g('catalyst'),
    techSignal:       g('tech'),
    chipSignal:       g('chip'),
    triggerPrice:     gf('trigger-price'),
    triggerVol:       gf('trigger-vol'),
    triggerLogic:     g('trigger-logic'),
    entry:            g('entry'),
    entryPrice:       gf('entry-price'),   // ← 進場價（計算風報比基準）
    stopShort:        g('stop-short'),
    stopWave:         g('stop-wave'),
    stopPrice:        gf('stop'),
    targetPrice:      gf('target'),        // ← 第一目標
    target2Price:     gf('target2'),       // ← 第二目標
    profitTime:       g('profit-time'),
    profitIndicator:  g('profit-indicator'),
    capital:          g('capital'),
    positionSize:     g('position-size'),
    updatedAt:        serverTimestamp()
  };
  await userDoc('playbooks',pbId).set(data,{merge:true});
  showToast('劇本已儲存 ✓');
};
async function loadPlaybooks(){
  const snap=await userCol('playbooks').get(); if(snap.empty) return;
  snap.forEach(docSnap=>{ const data=docSnap.data(); const id=docSnap.id; if(!document.getElementById(id)){ appendPlaybookCard(id,data); } else { const num=id.replace('pb-',''); const n=document.getElementById('pb'+num+'-n'); if(n) n.textContent=data.name; } });
  // Playbook 載入後刷新第一張卡的下拉
  refreshPbSelects();
}

window.addPlaybookCloud = async () => {
  const ref=userCol('playbooks').doc(); const id=ref.id;
  const data={
    name:'新交易劇本', type:'trend', stocks:'',
    catalyst:'', techSignal:'', chipSignal:'',
    triggerPrice:null, triggerVol:null, triggerLogic:'全部符合',
    entry:'', entryPrice:null,
    stopShort:'', stopWave:'',
    stopPrice:null, targetPrice:null, target2Price:null,
    profitTime:'', profitIndicator:'',
    capital:'', positionSize:'',
    updatedAt:serverTimestamp()
  };
  await ref.set(data); appendPlaybookCard(id,data); showToast('新劇本已建立，請填寫後儲存');
};
window.delPlaybookCloud = async (id) => {
  if(!confirm('確定要刪除這個劇本嗎？')) return;
  await userDoc('playbooks',id).delete();
  const el=document.getElementById(id); if(el) el.remove(); showToast('劇本已刪除');
};
window.saveJournal = async () => {
  const dateKey = window._journalDate || new Date().toISOString().slice(0,10);

  // ── 讀取趨勢標籤 ──
  const trendTags = [];
  document.querySelectorAll('#trend-tags-row .chip.active').forEach(btn => {
    trendTags.push(btn.dataset.tag || btn.textContent.trim());
  });

  // ── 讀取趨勢觀察文字（新 id）──
  const trendText = document.getElementById('j-trend-main')?.value ||
                    document.querySelectorAll('#sp0 textarea')[0]?.value || '';
  const fundsText = document.getElementById('j-trend-funds')?.value ||
                    document.querySelectorAll('#sp0 textarea')[1]?.value || '';

  const pnlRaw  = parseFloat(document.getElementById('j-pnl')?.value) || null;
  const pnlSign = document.getElementById('pnl-sign-btn')?.textContent?.trim() === '+' ? 1 : -1;
  const pnlNum  = pnlRaw != null ? Math.round(pnlRaw * pnlSign) : null;

  // ── 2. 讀取進出場交易卡（含已實現損益）──
  const trades = collectTradesFromCards();

  // ── 3. 讀取 IF→THEN 劇本（含對應庫存股票）──
  const plans = [];
  document.querySelectorAll('#ifthen-rows .ift-check').forEach(row => {
    const inputs  = row.querySelectorAll('input[type=text]');
    const cb      = row.querySelector('input[type=checkbox]');
    const stockSel= row.querySelector('select.ift-stock');
    const ifText   = inputs[0]?.value?.trim() || '';
    const thenText = inputs[1]?.value?.trim() || '';
    const stock    = stockSel?.value || '';
    if(ifText || thenText) plans.push({ if: ifText, then: thenText, stock, done: cb?.checked || false });
  });

  // ── 4. 讀取心理標籤 ──
  const tagsClean = [];
  document.querySelectorAll('#sp3 .tchip.on').forEach(el => {
    tagsClean.push(el.textContent.replace(/\s+/g,' ').trim());
  });
  const emoVal = document.querySelector('#sp3 input[type=range]')?.value || '2';

  // ── 5. 組合日記資料（含庫存快照）──
  const invSnapShot = [];
  document.querySelectorAll('#inv-rows .inv-row').forEach(row => {
    const inputs = row.querySelectorAll('input');
    const name   = inputs[0]?.value?.trim();
    const shares = parseFloat(inputs[1]?.value) || 0;
    const cost   = parseFloat(inputs[2]?.value) || 0;
    const price  = parseFloat(inputs[3]?.value) || 0;
    if(name && shares > 0) invSnapShot.push({ name, shares, cost, price });
  });

  const data = {
    date:        dateKey,
    trend:       trendText,
    funds:       fundsText,
    trendTags,
    notes:       document.getElementById('j-notes')?.value   || '',
    summary:     document.getElementById('j-summary')?.value || '',
    pnl:         pnlNum,
    trades,
    plans,
    tags:        tagsClean,
    emotion:     parseInt(emoVal),
    invSnapshot: invSnapShot,
    savedAt:     serverTimestamp()
  };
  await userDoc('journals', dateKey).set(data, {merge:true});

  // ── 自動同步庫存 ──
  if(trades.length > 0) await syncInventoryFromTrades(trades);

  // ── 6. 同步快取 & 刷新 UI ──
  if(!window._calCache) window._calCache = {};
  window._calCache[dateKey] = { ...data, date: dateKey, savedAt: new Date() };
  const now = new Date();
  window._renderCal(now.getFullYear(), now.getMonth());
  await updateStatsCards();

  // ── 7. 更新 Step 4 摘要 ──
  const sp4Pnl = document.getElementById('sp4-pnl');
  if(sp4Pnl){
    if(pnlNum != null){
      sp4Pnl.textContent = (pnlNum>=0?'+':'') + pnlNum.toLocaleString();
      sp4Pnl.style.color = pnlNum>=0?'var(--up)':'var(--dn)';
    } else { sp4Pnl.textContent='未填'; sp4Pnl.style.color='var(--t3)'; }
  }
  const sp4Trades = document.getElementById('sp4-trades');
  if(sp4Trades) sp4Trades.textContent = trades.length + ' 筆';
  const sp4Inv = document.getElementById('sp4-inv');
  const buyTrades  = trades.filter(t=>t.actionClass==='buy'||t.actionClass==='add');
  const sellTrades = trades.filter(t=>t.actionClass==='sell'||t.actionClass==='cut');
  if(sp4Inv) sp4Inv.innerHTML = trades.length===0 ? '無變動'
    : buyTrades.map(t=>`<span style="color:var(--up)">+${t.stockId}</span>`).join(' ')
    + (sellTrades.length>0?' ':'' )
    + sellTrades.map(t=>`<span style="color:var(--dn)">−${t.stockId}</span>`).join(' ');
  const sp4List = document.getElementById('sp4-trades-list');
  if(sp4List && trades.length>0){
    sp4List.innerHTML = '<div style="font-size:10px;color:var(--t3);font-family:var(--mono);margin-bottom:6px;">今日交易紀錄</div>'
    + trades.map(t=>`<div style="display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--border);font-size:12px;">
        <span style="background:${t.actionClass==='buy'||t.actionClass==='add'?'var(--up-d)':'var(--dn-d)'};color:${t.actionClass==='buy'||t.actionClass==='add'?'var(--up)':'var(--dn)'};border-radius:5px;padding:2px 7px;font-size:10px;font-family:var(--mono);">${t.action||t.actionClass}</span>
        <span style="font-weight:600;">${t.stockId||'—'}</span>
        <span style="color:var(--t2);">$${t.price} × ${t.shares}股</span>
        <span style="margin-left:auto;font-family:var(--mono);font-size:11px;color:var(--t3);">${t.dir==='long'?'做多':'做空'}</span>
      </div>`).join('');
  } else if(sp4List){ sp4List.innerHTML=''; }

  showToast('日記已儲存到雲端 ✓');
};

// ── 從進出場卡自動同步庫存 ──────────────────────────────
// ── 手續費與稅費計算 ──────────────────────────────────────
// 手續費率 0.1425%，3折 = 0.04275%
// 買入真實成本 = 成交價 × (1 + 0.0004275) — 四捨五入到分
// 賣出實收     = 成交價 × (1 - 0.0004275 - 0.003) — 扣手續費+證交稅0.3%
const FEE_RATE   = 0.001425 * 0.3;   // 手續費3折
const TAX_RATE   = 0.003;            // 證交稅（賣方才有）
const MIN_FEE    = 1;                 // 最低手續費 $1

function calcBuyCost(price, shares){
  const fee = Math.max(Math.round(price * shares * FEE_RATE), MIN_FEE);
  const totalCost = price * shares + fee;
  // 回傳每股真實成本（含手續費）
  return parseFloat((totalCost / shares).toFixed(2));
}

function calcSellNet(price, shares){
  const fee = Math.max(Math.round(price * shares * FEE_RATE), MIN_FEE);
  const tax = Math.round(price * shares * TAX_RATE);
  const net = price * shares - fee - tax;
  // 回傳每股實收（扣手續費+稅）
  return parseFloat((net / shares).toFixed(2));
}

async function syncInventoryFromTrades(trades){
  // 讀現有庫存
  const snap = await userDoc('data','inventory').get();
  let items = snap.exists ? (snap.data().items || []) : [];

  trades.forEach(t => {
    if(!t.stockId || !t.price || !t.shares) return;
    const existIdx = items.findIndex(i =>
      i.name.replace(/\s/g,'') === t.stockId.replace(/\s/g,'') ||
      i.name.includes(t.stockId) || t.stockId.includes(i.name)
    );

    if(t.actionClass === 'buy' || t.actionClass === 'add'){
      // 買入真實成本（含手續費3折）
      const trueCostPerShare = calcBuyCost(t.price, t.shares);
      if(existIdx >= 0){
        const old = items[existIdx];
        const totalShares = old.shares + t.shares;
        const avgCost = ((old.cost * old.shares) + (trueCostPerShare * t.shares)) / totalShares;
        items[existIdx] = { ...old, shares: totalShares, cost: parseFloat(avgCost.toFixed(2)) };
      } else {
        items.push({ name: t.stockId, shares: t.shares, cost: trueCostPerShare, price: t.price, pattern: '' });
      }
    } else if(t.actionClass === 'sell' || t.actionClass === 'cut'){
      if(existIdx >= 0){
        const remaining = items[existIdx].shares - t.shares;
        if(remaining <= 0){
          items.splice(existIdx, 1);
        } else {
          items[existIdx] = { ...items[existIdx], shares: remaining };
        }
      }
    }
  });

  // 寫回 Firestore
  await userDoc('data','inventory').set({ items, updatedAt: serverTimestamp() });
  // 重新渲染庫存 UI
  const container = document.getElementById('inv-rows');
  if(container){
    container.innerHTML = '';
    let n = 0;
    items.forEach(item => { n++; addInvRowData(n, item); });
    window.invN = n;
  }
}

async function loadJournals(targetDate){
  const dateKey = targetDate || window._journalDate || new Date().toISOString().slice(0,10);
  window._journalDate = dateKey;
  const snap  = await userDoc('journals', dateKey).get();
  // 更新日記頁標題日期
  const jDateEl = document.getElementById('j-date');
  if(jDateEl) jDateEl.textContent = dateKey;

  if (!snap.exists) {
    // 清空趨勢標籤顯示
    document.querySelectorAll('#trend-tags-row .chip').forEach(b=>b.classList.remove('active'));
    updateTrendTagsDisplay([]);
    return;
  }
  const d = snap.data();

  // 恢復趨勢觀察文字
  const ta0 = document.getElementById('j-trend-main') || document.querySelectorAll('#sp0 textarea')[0];
  const ta1 = document.getElementById('j-trend-funds') || document.querySelectorAll('#sp0 textarea')[1];
  if(ta0) ta0.value = d.trend || '';
  if(ta1) ta1.value = d.funds || '';

  // 恢復趨勢標籤
  const savedTags = Array.isArray(d.trendTags) ? d.trendTags : [];
  document.querySelectorAll('#trend-tags-row .chip').forEach(btn => {
    const tag = btn.dataset.tag || btn.textContent.trim();
    btn.classList.toggle('active', savedTags.includes(tag));
  });
  // 恢復自定義標籤（不在預設清單裡的）
  const defaultTags = ['多頭','空頭','盤整','強勢','弱勢','外資買超','外資賣超','投信認養'];
  savedTags.filter(t=>!defaultTags.includes(t)).forEach(t=>{
    addTrendTagChip(t, true);
  });
  updateTrendTagsDisplay(savedTags);

  const jn = document.getElementById('j-notes');
  if(jn) jn.value = d.notes || '';
  const js = document.getElementById('j-summary');
  if(js) js.value = d.summary || '';

  // 恢復損益欄
  if(d.pnl != null){
    const btn = document.getElementById('pnl-sign-btn');
    const inp = document.getElementById('j-pnl');
    if(btn){ btn.textContent=d.pnl>=0?'+':'−'; btn.style.background=d.pnl>=0?'var(--up-d)':'var(--dn-d)'; btn.style.borderColor=d.pnl>=0?'var(--up-b)':'var(--dn-b)'; btn.style.color=d.pnl>=0?'var(--up)':'var(--dn)'; }
    if(inp) inp.value=Math.abs(d.pnl);
    updatePnlPreview();
  }
  // 恢復 IF→THEN 劇本
  if(Array.isArray(d.plans) && d.plans.length > 0){
    const container=document.getElementById('ifthen-rows');
    if(container){ container.innerHTML=''; d.plans.forEach(p=>{ addIfthenData(p); }); }
  }
  await loadYesterdayPlans();
}


// 隔天顯示昨日 IF→THEN 執行情況
async function loadYesterdayPlans(){
  const yest = new Date(); yest.setDate(yest.getDate()-1);
  const yestStr = yest.toISOString().slice(0,10);
  const ySnap = await userDoc('journals', yestStr).get();
  if(!ySnap.exists) return;
  const yData = ySnap.data();
  const plans = yData.plans || [];
  if(plans.length === 0) return;

  // 在 Step2 上方插入昨日劇本回顧
  window._yesterdayPlans = plans;
  window._yesterdayDate  = yestStr;
}

// 向 Step2 注入昨日劇本（在 renderStep2Inventory 裡呼叫）
function renderYesterdayPlansInStep2(){
  const plans = window._yesterdayPlans || [];
  if(plans.length === 0) return;
  const container = document.getElementById('inv-rows-j');
  if(!container) return;

  const yestBox = document.createElement('div');
  yestBox.style.cssText = 'background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);padding:10px 12px;margin-bottom:10px;';
  yestBox.innerHTML = `<div style="font-size:9px;color:var(--amber);font-family:var(--mono);font-weight:600;margin-bottom:6px;">📋 昨日劇本（${window._yesterdayDate}）— 執行對照</div>`
    + plans.map(p => `<div style="font-size:11px;color:var(--t1);padding:2px 0;display:flex;align-items:center;gap:6px;">
        <span style="color:var(--blue);font-weight:600;font-family:var(--mono);font-size:10px;">IF</span>
        <span>${p.if}</span>
        <span style="color:var(--up);font-family:var(--mono);font-size:10px;">→</span>
        <span>${p.then}</span>
        ${p.done ? '<span style="color:var(--dn);font-size:10px;">✓</span>' : '<span style="color:var(--t3);font-size:10px;">○</span>'}
      </div>`).join('');
  container.insertAdjacentElement('beforebegin', yestBox);
}

// addIfthen with data
function addIfthenData(data={}){
  const r=document.createElement('div');
  r.className='ift-check';
  r.style.gridTemplateColumns='90px 1fr 52px 1fr 36px';
  const stockOpts=buildIfthenStockOptions();
  const stockVal=data.stock||'';
  r.innerHTML=`<select class="di ift-stock" style="font-size:10px;padding:5px 4px;cursor:pointer;"><option value="">全部庫存</option>${stockOpts}</select><input class="di" type="text" value="${data.if||''}" placeholder="觸發條件"><div class="ifl then">THEN</div><input class="di" type="text" value="${data.then||''}" placeholder="執行動作"><label class="cbox-wrap"><input type="checkbox" ${data.done?'checked':''} onchange="toggleCbox(this)"><span class="cbox ${data.done?'checked':''}"></span></label>`;
  document.getElementById('ifthen-rows')?.appendChild(r);
  // 恢復選中的股票
  if(stockVal){ const sel=r.querySelector('.ift-stock'); if(sel) sel.value=stockVal; }
}

// 損益欄 UI 輔助
window.togglePnlSign = function(){
  const btn = document.getElementById('pnl-sign-btn');
  if(!btn) return;
  const isPlus = btn.textContent.trim() === '+';
  btn.textContent   = isPlus ? '−' : '+';
  btn.style.background   = isPlus ? 'var(--dn-d)'  : 'var(--up-d)';
  btn.style.borderColor  = isPlus ? 'var(--dn-b)'  : 'var(--up-b)';
  btn.style.color        = isPlus ? 'var(--dn)'    : 'var(--up)';
  updatePnlPreview();
};
window.updatePnlPreview = function(){
  const btn  = document.getElementById('pnl-sign-btn');
  const inp  = document.getElementById('j-pnl');
  const prev = document.getElementById('j-pnl-preview');
  if(!prev) return;
  const val  = parseFloat(inp?.value);
  if(isNaN(val) || val === 0){ prev.textContent = '—'; prev.style.color='var(--t3)'; return; }
  const sign = btn?.textContent.trim() === '+' ? 1 : -1;
  const total = val * sign;
  prev.textContent = (total>=0?'+':'') + total.toLocaleString();
  prev.style.color = total >= 0 ? 'var(--up)' : 'var(--dn)';
};

// 載入所有日記到日曆快取（含所有欄位）
// 日曆格子標籤 HTML（只顯示第一個，避免太擁擠）
function buildCalTagHtml(trendTags){
  if(!Array.isArray(trendTags) || trendTags.length === 0) return '';
  const tag = trendTags[0];
  const [bg, color] = getTrendTagColor(tag);
  const more = trendTags.length > 1 ? `+${trendTags.length-1}` : '';
  return `<span style="background:${bg};color:${color};border-radius:3px;padding:0px 3px;font-size:6px;font-weight:600;display:block;line-height:1.4;margin-top:1px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100%;">${tag}${more}</span>`;
}

function updateSidebarStats(){
  if(!window._calCache) return;
  const now = new Date();
  const ym  = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`;
  let monthPnl=0, win=0, total=0;
  Object.entries(window._calCache).forEach(([date, d]) => {
    if(!date.startsWith(ym) || d.pnl==null) return;
    monthPnl += d.pnl; total++;
    if(d.pnl > 0) win++;
  });
  const pnlEl = document.getElementById('sidebar-pnl');
  const wrEl  = document.getElementById('sidebar-wr');
  if(pnlEl){
    const sign = monthPnl >= 0 ? '+' : '';
    const abs  = Math.abs(monthPnl);
    const disp = abs >= 1000 ? `${sign}$${(monthPnl/1000).toFixed(1)}k` : `${sign}$${monthPnl.toLocaleString()}`;
    pnlEl.textContent = total > 0 ? disp : '—';
    pnlEl.className   = 'stat-mini-val ' + (monthPnl >= 0 ? 'up' : 'dn');
  }
  if(wrEl) wrEl.textContent = total > 0 ? Math.round(win/total*100)+'%' : '—';
}
window.showToast = function(msg){
  let t=document.getElementById('toast');
  if(!t){t=document.createElement('div');t.id='toast';document.body.appendChild(t);}
  t.textContent=msg; t.className='toast show'; setTimeout(()=>t.classList.remove('show'),2500);
};
</script>
<style>/* ═══════════════════════════════════════════════════════════
   TradeLog Pro — 黑金精品風格
   靈感：永豐大戶頭 × 彭博終端機 × 精品資產管理
═══════════════════════════════════════════════════════════ */

@import url('https://fonts.googleapis.com/css2?family=Noto+Sans+TC:wght@300;400;500;600;700&family=IBM+Plex+Mono:wght@300;400;500;600&display=swap');

:root{
  /* ── 背景層次 ── */
  --bg0:#080a0e;
  --bg1:#0e1016;
  --bg2:#12151e;
  --bg3:#181c28;
  --bg4:#1e2333;
  --bg-card:#141720;

  /* ── 邊框 ── */
  --border:rgba(255,255,255,.07);
  --border2:rgba(255,255,255,.13);
  --border-gold:rgba(196,155,74,.3);

  /* ── 香檳金 主強調色 ── */
  --gold:#c49b4a;
  --gold-l:#e8c97a;
  --gold-d:rgba(196,155,74,.1);
  --gold-b:rgba(196,155,74,.25);

  /* ── 文字 ── */
  --t0:#f0ede8;
  --t1:#c8c4bb;
  --t2:#888077;
  --t3:#504a42;

  /* ── 藍色（CTA）── */
  --blue:#2d7fff;
  --blue-d:rgba(45,127,255,.1);
  --blue-b:rgba(45,127,255,.25);

  /* ── 台股漲紅 ── */
  --up:#ff4d4d;
  --up-d:rgba(255,77,77,.1);
  --up-b:rgba(255,77,77,.22);

  /* ── 台股跌綠 ── */
  --dn:#00d48a;
  --dn-d:rgba(0,212,138,.08);
  --dn-b:rgba(0,212,138,.2);

  /* ── 琥珀 ── */
  --amber:#f59e0b;
  --amber-d:rgba(245,158,11,.1);
  --amber-b:rgba(245,158,11,.22);

  /* ── 紫 ── */
  --purple:#8b5cf6;
  --purple-d:rgba(139,92,246,.1);
  --purple-b:rgba(139,92,246,.22);

  /* ── 字體 ── */
  --mono:'IBM Plex Mono',monospace;
  --sans:'Noto Sans TC',sans-serif;

  /* ── 圓角 ── */
  --r:12px;--rl:16px;

  /* ── 黑金漸層 ── */
  --grad-gold:linear-gradient(135deg,#c49b4a 0%,#e8c97a 50%,#c49b4a 100%);
  --grad-card:linear-gradient(160deg,#1a1e2e 0%,#12151e 100%);
  --grad-dark:linear-gradient(180deg,#0e1016 0%,#080a0e 100%);
}

*,*::before,*::after{box-sizing:border-box;margin:0;padding:0;}
html,body{height:100%;overflow:hidden;}
body{
  background:var(--bg0);
  color:var(--t0);
  font-family:var(--sans);
  font-size:14px;
  display:flex;
  flex-direction:column;
  -webkit-font-smoothing:antialiased;
}

/* ── 捲軸 ── */
::-webkit-scrollbar{width:3px;height:3px;}
::-webkit-scrollbar-track{background:transparent;}
::-webkit-scrollbar-thumb{background:var(--border-gold);border-radius:2px;}

/* ════════════════════════════════════
   登入頁 — 大理石精品風
════════════════════════════════════ */
#auth-screen{
  position:fixed;inset:0;
  background:var(--bg0);
  background-image:
    radial-gradient(ellipse 80% 50% at 50% 0%, rgba(196,155,74,.08) 0%, transparent 60%),
    radial-gradient(ellipse 50% 30% at 80% 100%, rgba(45,127,255,.05) 0%, transparent 50%);
  display:flex;align-items:center;justify-content:center;
  z-index:1000;flex-direction:column;
}
.auth-card{
  background:linear-gradient(160deg,rgba(24,28,40,.95) 0%,rgba(14,16,22,.98) 100%);
  border:1px solid var(--border-gold);
  border-radius:24px;
  padding:48px 52px;
  max-width:420px;width:90%;
  box-shadow:
    0 0 0 1px rgba(196,155,74,.1),
    0 40px 80px rgba(0,0,0,.8),
    0 0 120px rgba(196,155,74,.04);
  text-align:center;
  position:relative;
  overflow:hidden;
}
.auth-card::before{
  content:'';position:absolute;top:0;left:0;right:0;height:1px;
  background:linear-gradient(90deg,transparent,rgba(196,155,74,.6),transparent);
}
.auth-logo{
  width:60px;height:60px;
  background:var(--gold-d);
  border:1px solid var(--border-gold);
  border-radius:18px;
  display:flex;align-items:center;justify-content:center;
  margin:0 auto 24px;
  box-shadow:0 0 30px rgba(196,155,74,.15);
}
.auth-logo i{font-size:28px;color:var(--gold);}
.auth-title{
  font-size:26px;font-weight:700;
  background:var(--grad-gold);
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;
  background-clip:text;
  margin-bottom:8px;letter-spacing:-.02em;
}
.auth-sub{font-size:13px;color:var(--t2);line-height:1.7;margin-bottom:32px;}
.auth-features{display:flex;flex-direction:column;gap:12px;margin-bottom:32px;text-align:left;}
.auth-feat{display:flex;align-items:center;gap:12px;font-size:13px;color:var(--t1);}
.auth-feat i{font-size:16px;color:var(--gold);flex-shrink:0;}
.google-btn{
  width:100%;padding:15px 20px;
  background:rgba(255,255,255,.04);
  border:1px solid var(--border2);
  border-radius:12px;
  font-size:14px;font-weight:500;color:var(--t0);
  cursor:pointer;
  display:flex;align-items:center;justify-content:center;gap:12px;
  transition:all .25s;font-family:var(--sans);
  position:relative;overflow:hidden;
}
.google-btn::after{
  content:'';position:absolute;inset:0;
  background:linear-gradient(135deg,rgba(196,155,74,.05),transparent);
  opacity:0;transition:opacity .25s;
}
.google-btn:hover{border-color:var(--border-gold);box-shadow:0 0 20px rgba(196,155,74,.1);}
.google-btn:hover::after{opacity:1;}
.google-btn img{width:20px;height:20px;}
.auth-note{font-size:11px;color:var(--t3);margin-top:18px;line-height:1.6;}

/* ════════════════════════════════════
   App 框架
════════════════════════════════════ */
#app-screen{display:none;flex-direction:column;height:100vh;overflow:hidden;min-height:0;}

/* ── 頂欄 ── */
.topbar{
  background:rgba(8,10,14,.95);
  backdrop-filter:blur(20px);
  border-bottom:1px solid var(--border);
  padding:0 24px;height:56px;
  display:flex;align-items:center;justify-content:space-between;
  flex-shrink:0;
  position:relative;
}
.topbar::after{
  content:'';position:absolute;bottom:0;left:0;right:0;height:1px;
  background:linear-gradient(90deg,transparent,rgba(196,155,74,.3),transparent);
}
.logo{display:flex;align-items:center;gap:12px;}
.logo-icon{
  width:34px;height:34px;
  background:var(--gold-d);
  border:1px solid var(--border-gold);
  border-radius:10px;
  display:flex;align-items:center;justify-content:center;
  box-shadow:0 0 12px rgba(196,155,74,.15);
}
.logo-icon i{font-size:17px;color:var(--gold);}
.logo-text{font-size:16px;font-weight:700;color:var(--t0);}
.logo-text span{
  font-family:var(--mono);
  background:var(--grad-gold);
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;
  background-clip:text;
}
.topbar-right{display:flex;align-items:center;gap:10px;}
.tb-date{
  font-family:var(--mono);font-size:11px;color:var(--t2);
  padding:4px 10px;
  background:rgba(255,255,255,.04);
  border:1px solid var(--border);border-radius:6px;
  letter-spacing:.03em;
}
.user-pill{
  display:flex;align-items:center;gap:8px;
  background:rgba(255,255,255,.04);
  border:1px solid var(--border);
  border-radius:20px;padding:3px 14px 3px 5px;
  cursor:pointer;transition:all .2s;
}
.user-pill:hover{border-color:var(--border-gold);}
.user-pill img{width:27px;height:27px;border-radius:50%;object-fit:cover;border:1px solid var(--border-gold);}
.user-pill span{font-size:12px;color:var(--t1);max-width:100px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.user-pill i{font-size:14px;color:var(--t3);}
.tb-btn{
  background:var(--gold-d);
  border:1px solid var(--border-gold);
  border-radius:8px;padding:6px 14px;
  color:var(--gold);font-size:11px;
  cursor:pointer;display:flex;align-items:center;gap:6px;
  font-family:var(--sans);font-weight:500;
  transition:all .2s;
}
.tb-btn:hover{background:rgba(196,155,74,.18);}

/* ════════════════════════════════════
   版面
════════════════════════════════════ */
.main{display:flex;flex:1;min-height:0;overflow:hidden;}

/* ── 側邊欄 ── */
.sidebar{
  width:210px;
  background:linear-gradient(180deg,rgba(14,16,22,1) 0%,rgba(8,10,14,1) 100%);
  border-right:1px solid var(--border);
  display:flex;flex-direction:column;flex-shrink:0;
  padding:16px 0;
  position:relative;
}
.sidebar::after{
  content:'';position:absolute;top:0;right:0;bottom:0;width:1px;
  background:linear-gradient(180deg,transparent,rgba(196,155,74,.2),transparent);
}
.nav-label{
  font-size:9px;color:var(--t3);
  letter-spacing:.15em;text-transform:uppercase;
  padding:10px 18px 5px;font-family:var(--mono);
}
.nav-item{
  display:flex;align-items:center;gap:12px;
  padding:10px 14px;margin:0 10px 3px;
  border-radius:10px;color:var(--t2);
  cursor:pointer;transition:all .18s;
  position:relative;
}
.nav-item i{font-size:18px;flex-shrink:0;}
.nav-item span{font-size:14px;font-weight:400;}
.nav-item:hover{background:rgba(255,255,255,.05);color:var(--t1);}
.nav-item.active{
  background:linear-gradient(135deg,rgba(196,155,74,.12),rgba(196,155,74,.05));
  border:1px solid var(--border-gold);
  color:var(--gold);
}
.nav-item.active i{filter:drop-shadow(0 0 6px rgba(196,155,74,.5));}
.nav-divider{height:1px;background:var(--border);margin:10px 14px;}
.sidebar-bottom{padding:14px;margin-top:auto;}
.stat-mini{
  background:rgba(196,155,74,.04);
  border:1px solid var(--border-gold);
  border-radius:12px;padding:14px 16px;margin-bottom:10px;
  position:relative;overflow:hidden;
}
.stat-mini::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(196,155,74,.4),transparent);}
.stat-mini-lbl{font-size:11px;color:var(--t2);margin-bottom:8px;font-family:var(--mono);letter-spacing:.06em;text-transform:uppercase;font-weight:500;}
.stat-mini-val{font-size:26px;font-weight:700;color:var(--t0);font-family:var(--mono);}
.stat-mini-val.up{color:var(--up);text-shadow:0 0 20px rgba(255,77,77,.4);}
.stat-mini-val.dn{color:var(--dn);text-shadow:0 0 20px rgba(0,212,138,.4);}

/* ── 內容區 ── */
.content{
  flex:1;
  overflow-y:auto;
  min-height:0;
  padding:22px;
  display:none;flex-direction:column;gap:16px;
  background:var(--bg0);
  background-image:radial-gradient(ellipse 60% 40% at 70% 0%,rgba(196,155,74,.03) 0%,transparent 60%);
}
.content.active{display:flex;}

/* ════════════════════════════════════
   卡片 — 核心設計元素
════════════════════════════════════ */
.card{
  background:var(--grad-card);
  border:1px solid var(--border);
  border-radius:var(--rl);
  padding:20px 22px;
  box-shadow:0 4px 24px rgba(0,0,0,.4);
  position:relative;overflow:hidden;
}
.card::before{
  content:'';position:absolute;top:0;left:0;right:0;height:1px;
  background:linear-gradient(90deg,transparent,rgba(196,155,74,.2),transparent);
}
.card-head{display:flex;align-items:center;gap:12px;margin-bottom:18px;}
.cicon{
  width:34px;height:34px;border-radius:10px;
  display:flex;align-items:center;justify-content:center;flex-shrink:0;
}
.cicon i{font-size:16px;}
.cicon.blue{background:var(--blue-d);border:1px solid var(--blue-b);}.cicon.blue i{color:var(--blue);}
.cicon.green{background:var(--up-d);border:1px solid var(--up-b);}.cicon.green i{color:var(--up);}
.cicon.amber{background:var(--amber-d);border:1px solid var(--amber-b);}.cicon.amber i{color:var(--amber);}
.cicon.purple{background:var(--purple-d);border:1px solid var(--purple-b);}.cicon.purple i{color:var(--purple);}
.cicon.dn{background:var(--dn-d);border:1px solid var(--dn-b);}.cicon.dn i{color:var(--dn);}
.ctitle{font-size:14px;font-weight:600;color:var(--t0);letter-spacing:.01em;}
.csub{font-size:10px;color:var(--t3);margin-left:auto;font-family:var(--mono);}

/* ════════════════════════════════════
   統計卡 — 永豐大戶頭風格
════════════════════════════════════ */
.mgrid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;}
.mc{
  background:linear-gradient(160deg,rgba(30,35,51,.9),rgba(20,23,32,.95));
  border:1px solid var(--border);
  border-radius:14px;padding:20px 22px;
  position:relative;overflow:hidden;
  transition:all .2s;
}
.mc:hover{border-color:var(--border-gold);transform:translateY(-1px);box-shadow:0 8px 30px rgba(0,0,0,.3);}
.mc::before{content:'';position:absolute;top:0;left:0;right:0;height:2px;background:var(--grad-gold);opacity:.7;}
.ml{
  font-size:12px;color:var(--t1);
  margin-bottom:12px;font-family:var(--mono);
  letter-spacing:.06em;text-transform:uppercase;
  font-weight:500;
}
.mv{
  font-size:32px;font-weight:700;
  font-family:var(--mono);color:var(--t0);
  line-height:1;letter-spacing:-.02em;
}
.mv.up{color:var(--up);text-shadow:0 0 24px rgba(255,77,77,.35);}
.mv.dn{color:var(--dn);text-shadow:0 0 24px rgba(0,212,138,.35);}
.mv.nu{color:var(--t0);}
.md{font-size:12px;color:var(--t1);margin-top:8px;font-family:var(--mono);}

/* ════════════════════════════════════
   日曆
════════════════════════════════════ */
.cal-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px;}
.cal-nav{display:flex;gap:5px;}
.cal-nav button{
  background:rgba(255,255,255,.04);
  border:1px solid var(--border);
  border-radius:8px;width:30px;height:30px;
  color:var(--t2);cursor:pointer;
  display:flex;align-items:center;justify-content:center;font-size:14px;
  transition:all .15s;
}
.cal-nav button:hover{border-color:var(--border-gold);color:var(--gold);}
.cal-dows{display:grid;grid-template-columns:repeat(7,1fr);gap:4px;margin-bottom:6px;}
.cdow{font-size:12px;color:var(--t1);text-align:center;padding:5px 0;font-family:var(--mono);font-weight:500;}
.cal-grid{display:grid;grid-template-columns:repeat(7,1fr);gap:5px;}
.cday{
  aspect-ratio:1;border-radius:10px;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;border:1px solid transparent;
  transition:all .15s;
}
.cday:hover{border-color:var(--border-gold);background:var(--gold-d);}
.cday .dn{font-size:15px;color:var(--t1);line-height:1;font-family:var(--mono);font-weight:600;}
.cday .pv{font-size:9px;margin-top:4px;line-height:1;font-family:var(--mono);font-weight:700;}
.cday.profit{
  background:rgba(255,77,77,.1);
  border-color:rgba(255,77,77,.2);
}
.cday.profit .dn{color:var(--up);font-weight:700;}
.cday.profit .pv{color:var(--up);text-shadow:0 0 8px rgba(255,77,77,.5);}
.cday.loss{
  background:rgba(0,212,138,.08);
  border-color:rgba(0,212,138,.18);
}
.cday.loss .dn{color:var(--dn);font-weight:700;}
.cday.loss .pv{color:var(--dn);text-shadow:0 0 8px rgba(0,212,138,.5);}
.cday.flat{background:rgba(255,255,255,.04);}
.cday.flat .dn{color:var(--t1);}
.cday.empty{background:transparent;cursor:default;opacity:.2;}
.cday.empty:hover{border-color:transparent;background:transparent;}
.cday.selected{
  border-color:var(--gold)!important;
  background:var(--gold-d)!important;
  box-shadow:0 0 14px rgba(196,155,74,.2);
}
.cday.selected .dn{color:var(--gold)!important;}

/* ════════════════════════════════════
   輸入欄位
════════════════════════════════════ */
.fg{margin-bottom:14px;}.fg:last-child{margin-bottom:0;}
.fl{
  font-size:10px;color:var(--t3);
  margin-bottom:6px;letter-spacing:.08em;
  font-family:var(--mono);text-transform:uppercase;
}
.di{
  background:rgba(255,255,255,.04);
  border:1px solid var(--border);
  border-radius:10px;padding:10px 14px;
  color:var(--t0);font-size:13px;width:100%;outline:none;
  line-height:1.5;transition:all .18s;font-family:var(--sans);
}
.di::placeholder{color:var(--t3);}
.di:focus{
  border-color:var(--gold);
  background:rgba(196,155,74,.04);
  box-shadow:0 0 0 3px rgba(196,155,74,.1);
}
.di.tall{min-height:80px;resize:vertical;}
.di.upv{color:var(--up);}.di.dnv{color:var(--dn);}
.r2{display:grid;grid-template-columns:1fr 1fr;gap:12px;}
.r3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;}
.r4{display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:12px;}

/* ════════════════════════════════════
   Chips
════════════════════════════════════ */
.frow{display:flex;gap:7px;flex-wrap:wrap;}
.chip{
  padding:6px 14px;border-radius:20px;font-size:12px;
  border:1px solid var(--border);
  background:rgba(255,255,255,.04);
  color:var(--t2);cursor:pointer;white-space:nowrap;
  font-family:var(--sans);transition:all .15s;
}
.chip:hover{border-color:var(--border2);color:var(--t1);}
.chip.active{
  background:var(--gold-d);
  color:var(--gold);border-color:var(--border-gold);
}

/* ── 交易動作 chips ── */
.achips{display:flex;gap:7px;flex-wrap:wrap;}
.achip{
  padding:8px 16px;border-radius:20px;font-size:13px;
  border:1px solid var(--border);
  background:rgba(255,255,255,.04);
  color:var(--t2);cursor:pointer;
  display:flex;align-items:center;gap:6px;transition:all .15s;font-family:var(--sans);
}
.achip i{font-size:14px;}
.achip.buy.on{background:var(--up-d);color:var(--up);border-color:var(--up-b);}
.achip.sell.on{background:var(--dn-d);color:var(--dn);border-color:var(--dn-b);}
.achip.add.on{background:var(--blue-d);color:var(--blue);border-color:var(--blue-b);}
.achip.cut.on{background:var(--amber-d);color:var(--amber);border-color:var(--amber-b);}

/* ── 心理 chips ── */
.tags-sec{display:flex;flex-direction:column;gap:12px;}
.tags-row{display:flex;gap:7px;flex-wrap:wrap;}
.tgl{font-size:10px;margin-bottom:6px;font-family:var(--mono);letter-spacing:.08em;text-transform:uppercase;}
.tgl.pos{color:var(--dn);}.tgl.neg{color:var(--up);}
.tchip{
  padding:7px 14px;border-radius:20px;font-size:12px;
  border:1px solid var(--border);
  background:rgba(255,255,255,.04);
  color:var(--t2);cursor:pointer;
  display:flex;align-items:center;gap:5px;transition:all .15s;
  user-select:none;font-family:var(--sans);
}
.tchip i{font-size:13px;}
.tchip.g.on{background:var(--dn-d);color:var(--dn);border-color:var(--dn-b);}
.tchip.b.on{background:var(--up-d);color:var(--up);border-color:var(--up-b);}
.tchip.w.on{background:var(--amber-d);color:var(--amber);border-color:var(--amber-b);}

/* ════════════════════════════════════
   Stepper
════════════════════════════════════ */
.stepper{display:flex;align-items:flex-start;gap:0;padding:6px 0 20px;}
.step{display:flex;flex-direction:column;align-items:center;flex:1;position:relative;cursor:pointer;}
.step-connector{height:2px;background:var(--border);position:absolute;top:18px;left:50%;right:-50%;z-index:0;}
.step:last-child .step-connector{display:none;}
.step-circle{
  width:38px;height:38px;border-radius:50%;
  border:2px solid var(--border);
  background:var(--bg2);
  display:flex;align-items:center;justify-content:center;
  font-size:13px;font-weight:700;color:var(--t3);
  font-family:var(--mono);position:relative;z-index:1;
  transition:all .2s;flex-shrink:0;
}
.step-lbl{font-size:10px;color:var(--t3);margin-top:7px;text-align:center;font-family:var(--mono);}
.step.done .step-circle{background:var(--dn-d);border-color:var(--dn-b);color:var(--dn);box-shadow:0 0 12px rgba(0,212,138,.2);}
.step.done .step-lbl{color:var(--dn);}
.step.done .step-connector{background:linear-gradient(90deg,var(--dn-b),var(--border));}
.step.curr .step-circle{
  background:var(--gold-d);border-color:var(--gold);color:var(--gold);
  box-shadow:0 0 0 4px rgba(196,155,74,.12),0 0 20px rgba(196,155,74,.2);
}
.step.curr .step-lbl{color:var(--gold);}

/* ── 導覽 badges ── */
.badge{font-size:9px;padding:3px 9px;border-radius:20px;font-family:var(--mono);white-space:nowrap;}
.bdone{background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);}
.bpend{background:var(--amber-d);color:var(--amber);border:1px solid var(--amber-b);}
.btodo{background:rgba(255,255,255,.04);color:var(--t3);border:1px solid var(--border);}
.bcurr{background:var(--gold-d);color:var(--gold);border:1px solid var(--border-gold);}

/* ════════════════════════════════════
   交易卡
════════════════════════════════════ */
.trade-card{
  background:linear-gradient(160deg,rgba(26,30,44,.8),rgba(18,21,30,.9));
  border:1px solid var(--border);
  border-radius:14px;padding:18px;margin-bottom:12px;
  position:relative;overflow:hidden;
}
.trade-card::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(196,155,74,.15),transparent);}
.tc-head{display:flex;align-items:center;gap:10px;margin-bottom:16px;padding-bottom:14px;border-bottom:1px solid var(--border);}
.tc-num{
  width:26px;height:26px;border-radius:50%;
  background:var(--gold-d);border:1px solid var(--border-gold);
  display:flex;align-items:center;justify-content:center;
  font-size:12px;font-weight:700;color:var(--gold);
  font-family:var(--mono);flex-shrink:0;
}
.tc-title{font-size:13px;font-weight:500;color:var(--t1);}
.tc-rm{margin-left:auto;background:transparent;border:none;color:var(--t3);cursor:pointer;font-size:17px;padding:0;transition:color .15s;}
.tc-rm:hover{color:var(--up);}
.dir-toggle{display:flex;border-radius:12px;overflow:hidden;border:1px solid var(--border);margin-bottom:14px;}
.dir-b{
  flex:1;padding:10px;border:none;
  background:rgba(255,255,255,.03);color:var(--t2);font-size:13px;
  cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;
  font-family:var(--sans);transition:all .15s;
}
.dir-b.long.on{background:var(--up-d);color:var(--up);}
.dir-b.short.on{background:var(--dn-d);color:var(--dn);}
.add-tc-btn{
  width:100%;background:transparent;
  border:1px dashed rgba(196,155,74,.2);
  border-radius:12px;padding:13px;color:var(--t3);font-size:13px;
  cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;
  font-family:var(--sans);transition:all .18s;margin-top:12px;
}
.add-tc-btn:hover{border-color:var(--gold);color:var(--gold);}

/* ════════════════════════════════════
   步驟導覽按鈕
════════════════════════════════════ */
.step-nav{display:flex;gap:10px;margin-top:18px;}
.s-prev{
  flex:1;background:rgba(255,255,255,.04);
  border:1px solid var(--border);border-radius:12px;
  padding:12px;color:var(--t2);font-size:13px;
  cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;
  font-family:var(--sans);transition:all .15s;
}
.s-prev:hover{border-color:var(--border2);color:var(--t1);}
.s-next{
  flex:2;
  background:linear-gradient(135deg,#2563eb,#1d4ed8);
  border:none;border-radius:12px;padding:12px;
  color:#fff;font-size:14px;font-weight:600;
  cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;
  font-family:var(--sans);
  box-shadow:0 4px 20px rgba(37,99,235,.35);
  transition:all .2s;
}
.s-next:hover{box-shadow:0 6px 28px rgba(37,99,235,.5);transform:translateY(-1px);}
.s-next.fin{
  background:linear-gradient(135deg,var(--gold),#b8860b);
  box-shadow:0 4px 20px rgba(196,155,74,.35);
}
.s-next.fin:hover{box-shadow:0 6px 28px rgba(196,155,74,.5);}
.sec-page{display:none;}.sec-page.active{display:block;}

/* ════════════════════════════════════
   庫存表格
════════════════════════════════════ */
.inv-row{
  display:grid;
  grid-template-columns:1.4fr 0.8fr 0.8fr 0.8fr 0.8fr 0.7fr 0.7fr 26px;
  gap:6px;align-items:center;padding:6px 0;
  border-bottom:1px solid var(--border);
}
.inv-row:last-child{border-bottom:none;}
.inv-name{font-size:13px !important;padding:7px 9px !important;color:var(--t0) !important;font-weight:500 !important;}
.inv-num{font-size:12px !important;padding:7px 9px !important;text-align:right !important;color:var(--t1) !important;}
.inv-pat{font-size:11px !important;padding:6px 8px !important;color:var(--t2) !important;}
.inv-pnl,.inv-pct{font-size:13px !important;font-weight:700 !important;font-family:var(--mono) !important;text-align:right !important;padding:7px 9px !important;pointer-events:none;background:rgba(255,255,255,.03) !important;border-color:var(--border) !important;}
.inv-pnl.up,.inv-pct.up{color:var(--up) !important;text-shadow:0 0 12px rgba(255,77,77,.3) !important;}
.inv-pnl.dn,.inv-pct.dn{color:var(--dn) !important;text-shadow:0 0 12px rgba(0,212,138,.3) !important;}

/* ════════════════════════════════════
   Playbook
════════════════════════════════════ */
.pbc{
  background:linear-gradient(160deg,rgba(24,28,40,.8),rgba(16,19,28,.9));
  border:1px solid var(--border);border-radius:14px;
  padding:18px 20px;margin-bottom:12px;
  position:relative;overflow:hidden;
}
.pbc::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(196,155,74,.2),transparent);}
.pbh{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:12px;}
.pbn{font-size:15px;font-weight:600;color:var(--t0);line-height:1.4;max-width:340px;}
.pbtag{font-size:10px;padding:3px 11px;border-radius:20px;white-space:nowrap;font-family:var(--mono);}
.pbtag.trend{background:var(--blue-d);color:var(--blue);border:1px solid var(--blue-b);}
.pbtag.rev{background:var(--amber-d);color:var(--amber);border:1px solid var(--amber-b);}
.pbstocks{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px;}
.pbstock{background:rgba(196,155,74,.06);border:1px solid var(--border-gold);border-radius:7px;padding:3px 10px;font-size:11px;color:var(--gold);font-family:var(--mono);}
.pbrule{font-size:13px;color:var(--t1);margin-top:7px;display:flex;gap:9px;align-items:flex-start;line-height:1.5;}
.pbrule i{font-size:15px;margin-top:1px;flex-shrink:0;}
.pbrule i.e{color:var(--dn);}.pbrule i.x{color:var(--up);}.pbrule i.r{color:var(--t2);}
.pb-exec{
  width:100%;margin-top:14px;
  background:var(--gold-d);border:1px solid var(--border-gold);
  border-radius:10px;padding:11px;
  color:var(--gold);font-size:13px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;gap:7px;
  font-family:var(--sans);font-weight:500;transition:all .2s;
}
.pb-exec:hover{background:rgba(196,155,74,.18);box-shadow:0 0 16px rgba(196,155,74,.15);}

/* ════════════════════════════════════
   IF→THEN
════════════════════════════════════ */
.ifthen{display:flex;flex-direction:column;gap:7px;}
.ift-check{display:grid;grid-template-columns:90px 1fr 52px 1fr 36px;gap:7px;align-items:center;margin-bottom:5px;}
.ifl{font-size:11px;font-weight:700;padding:10px 0;text-align:center;border-radius:8px;font-family:var(--mono);}
.ifl.if{background:var(--blue-d);color:var(--blue);border:1px solid var(--blue-b);}
.ifl.then{background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);}
.cbox-wrap{display:flex;align-items:center;justify-content:center;cursor:pointer;}
.cbox-wrap input[type=checkbox]{display:none;}
.cbox{width:24px;height:24px;border-radius:7px;border:1.5px solid var(--border2);background:rgba(255,255,255,.04);display:flex;align-items:center;justify-content:center;transition:all .15s;flex-shrink:0;}
.cbox.checked{background:var(--gold);border-color:var(--gold);}
.cbox.checked::after{content:'✓';font-size:13px;font-weight:700;color:#000;line-height:1;}

/* ════════════════════════════════════
   按鈕
════════════════════════════════════ */
.btn-p{
  background:linear-gradient(135deg,#2563eb,#1d4ed8);
  border:none;border-radius:12px;padding:13px 22px;
  color:#fff;font-size:14px;font-weight:600;
  cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;
  font-family:var(--sans);
  box-shadow:0 4px 20px rgba(37,99,235,.3);
  transition:all .2s;
}
.btn-p:hover{box-shadow:0 6px 28px rgba(37,99,235,.5);transform:translateY(-1px);}
.btn-s{
  background:var(--purple-d);border:1px solid var(--purple-b);
  border-radius:12px;padding:13px 18px;color:var(--purple);
  font-size:13px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;gap:6px;
  font-family:var(--sans);transition:all .2s;
}
.btn-g{
  background:rgba(255,255,255,.05);
  border:1px solid var(--border);border-radius:9px;
  padding:7px 14px;color:var(--t1);font-size:12px;
  cursor:pointer;display:flex;align-items:center;gap:6px;
  font-family:var(--sans);transition:all .15s;
}
.btn-g:hover{border-color:var(--border2);color:var(--t0);}

/* ════════════════════════════════════
   AI 區塊
════════════════════════════════════ */
.aibox{
  background:var(--purple-d);border:1px solid var(--purple-b);
  border-radius:12px;padding:16px 18px;
  display:flex;gap:13px;align-items:flex-start;
}
.aibox i{font-size:17px;color:var(--purple);flex-shrink:0;margin-top:1px;}
.aitxt{font-size:13px;color:rgba(139,92,246,.9);line-height:1.8;}
.aitxt b{color:var(--t0);font-weight:500;}

/* ── save bar ── */
.save-bar{
  display:flex;align-items:center;gap:10px;
  background:var(--gold-d);border:1px solid var(--border-gold);
  border-radius:12px;padding:12px 16px;margin-bottom:5px;
}
.save-bar i{font-size:17px;color:var(--gold);flex-shrink:0;}
.save-bar span{font-size:13px;color:var(--gold);flex:1;}
.save-bar button{
  background:var(--gold);border:none;border-radius:8px;
  padding:7px 16px;color:#000;font-size:12px;font-weight:700;
  cursor:pointer;font-family:var(--sans);
}

/* ════════════════════════════════════
   個股分析
════════════════════════════════════ */
.sa-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-bottom:16px;}
.sa-mc{
  background:rgba(255,255,255,.03);
  border:1px solid var(--border);border-radius:12px;padding:12px 14px;
}
.sa-ml{font-size:9px;color:var(--t3);margin-bottom:5px;font-family:var(--mono);letter-spacing:.08em;text-transform:uppercase;}
.sa-mv{font-size:18px;font-weight:700;}.sa-mv.up{color:var(--up);}.sa-mv.dn{color:var(--dn);}.sa-mv.nu{color:var(--t0);}
.sa-md{font-size:10px;color:var(--t2);margin-top:3px;font-family:var(--mono);}
.inst-grid2{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;}
.inst-card2{background:rgba(255,255,255,.03);border:1px solid var(--border);border-radius:12px;padding:12px 14px;}
.inst-name2{font-size:10px;color:var(--t2);margin-bottom:6px;font-family:var(--mono);}
.inst-val2{font-size:15px;font-weight:700;font-family:var(--mono);}
.inst-val2.up{color:var(--up);}.inst-val2.dn{color:var(--dn);}
.inst-bar2{height:3px;border-radius:2px;margin-top:6px;background:var(--border);}
.inst-bar2-fill{height:100%;border-radius:2px;}
.sa-table{width:100%;border-collapse:collapse;font-size:12px;font-family:var(--mono);}
.sa-table th{background:rgba(255,255,255,.04);color:var(--t2);padding:8px 10px;text-align:right;font-weight:500;border-bottom:1px solid var(--border);}
.sa-table th:first-child{text-align:left;}
.sa-table td{padding:8px 10px;text-align:right;border-bottom:0.5px solid var(--border);color:var(--t0);}
.sa-table td:first-child{text-align:left;color:var(--t2);}
.sa-table tr:last-child td{border-bottom:none;}
.td-up{color:var(--up)!important;font-weight:700;}.td-dn{color:var(--dn)!important;font-weight:700;}
.rsi-badge{display:inline-block;padding:3px 9px;border-radius:20px;font-size:10px;font-family:var(--mono);font-weight:700;}
.rsi-ob{background:var(--up-d);color:var(--up);border:1px solid var(--up-b);}
.rsi-os{background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);}
.rsi-ok{background:var(--blue-d);color:var(--blue);border:1px solid var(--blue-b);}
.tc-analysis{margin-top:14px;border-top:1px solid var(--border);padding-top:14px;display:none;}
.tc-analysis.show{display:block;}
.tc-analysis-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;}
.tc-analysis-mini{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:12px;}
.tc-analysis-mc{background:rgba(255,255,255,.03);border:1px solid var(--border);border-radius:10px;padding:10px 12px;}
.tc-analysis-lbl{font-size:9px;color:var(--t3);margin-bottom:4px;font-family:var(--mono);letter-spacing:.06em;}
.tc-analysis-val{font-size:16px;font-weight:700;font-family:var(--mono);}
.tc-analysis-val.up{color:var(--up);}.tc-analysis-val.dn{color:var(--dn);}.tc-analysis-val.nu{color:var(--t0);}

/* ── AI dots ── */
.ai-dots2{display:flex;gap:5px;}.ai-dots2 span{width:6px;height:6px;border-radius:50%;background:var(--purple);animation:bounce 1.2s infinite ease-in-out;}.ai-dots2 span:nth-child(2){animation-delay:.2s;}.ai-dots2 span:nth-child(3){animation-delay:.4s;}
.ai-loading-dots{display:flex;justify-content:center;gap:7px;}.ai-loading-dots span{width:9px;height:9px;border-radius:50%;background:var(--purple);animation:dot-bounce 1.2s infinite ease-in-out;}.ai-loading-dots span:nth-child(2){animation-delay:.2s;}.ai-loading-dots span:nth-child(3){animation-delay:.4s;}
@keyframes dot-bounce{0%,80%,100%{transform:scale(0.6);opacity:.4}40%{transform:scale(1);opacity:1}}
@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}
.ai-insight-block{background:var(--purple-d);border:1px solid var(--purple-b);border-radius:12px;padding:15px 17px;margin-bottom:12px;display:flex;gap:12px;align-items:flex-start;}
.ai-insight-block i{font-size:17px;color:var(--purple);flex-shrink:0;margin-top:1px;}
.ai-insight-block p{font-size:13px;color:rgba(139,92,246,.85);line-height:1.8;margin:0;}

/* ════════════════════════════════════
   Toast — 黑金版
════════════════════════════════════ */
.toast{
  position:fixed;bottom:28px;left:50%;
  transform:translateX(-50%) translateY(20px);
  background:var(--bg4);
  border:1px solid var(--border-gold);
  color:var(--t0);padding:12px 24px;border-radius:12px;
  font-size:13px;opacity:0;transition:all .3s;
  z-index:9999;pointer-events:none;
  box-shadow:0 8px 30px rgba(0,0,0,.5),0 0 20px rgba(196,155,74,.1);
}
.toast.show{opacity:1;transform:translateX(-50%) translateY(0);}

/* ── Range slider ── */
input[type=range]{width:100%;height:4px;border-radius:2px;background:var(--border);outline:none;cursor:pointer;-webkit-appearance:none;accent-color:var(--gold);}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:18px;height:18px;border-radius:50%;background:var(--gold);border:2px solid var(--bg2);cursor:pointer;box-shadow:0 0 8px rgba(196,155,74,.4);}

/* ── 版面格 ── */
.two-col{display:grid;grid-template-columns:1fr 1fr;gap:16px;}
.three-col{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px;}
.brow{display:flex;align-items:center;gap:12px;margin-bottom:10px;}
.blbl{font-size:12px;color:var(--t1);width:100px;text-align:right;flex-shrink:0;font-family:var(--mono);}
.btrack{flex:1;background:var(--bg3);border-radius:4px;height:22px;border:1px solid var(--border);}
.bfill{height:100%;border-radius:4px;display:flex;align-items:center;padding-right:9px;justify-content:flex-end;}
.bpct{font-size:11px;font-weight:700;font-family:var(--mono);}
.trade-item{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);}
.trade-item:last-child{border-bottom:none;}
.tbadge{width:30px;height:30px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;flex-shrink:0;font-family:var(--mono);}
.tbadge.buy{background:var(--up-d);color:var(--up);border:1px solid var(--up-b);}
.tbadge.sell{background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);}
.tinfo{flex:1;}.tname{font-size:14px;font-weight:500;color:var(--t0);}
.tmeta{font-size:11px;color:var(--t2);margin-top:2px;font-family:var(--mono);}
.tpnl{text-align:right;}.tpnl-v{font-size:14px;font-weight:700;font-family:var(--mono);}
.tpnl-v.up{color:var(--up);}.tpnl-v.dn{color:var(--dn);}
.srow{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--border);}
.srow:last-child{border-bottom:none;}
.sname{font-size:14px;font-weight:500;color:var(--t0);flex:1;}
.spat{font-size:10px;color:var(--t2);background:rgba(255,255,255,.04);border:1px solid var(--border);border-radius:6px;padding:3px 9px;font-family:var(--mono);}
.spnl{font-size:14px;font-weight:700;font-family:var(--mono);}.spnl.up{color:var(--up);}.spnl.dn{color:var(--dn);}

/* ════════════════════════════════════
   RWD
════════════════════════════════════ */
@media(max-width:900px){
  .sidebar{width:58px;}
  .nav-item span,.nav-label,.sidebar-bottom,.logo-text{display:none;}
  .nav-item{justify-content:center;padding:11px;}
  .mgrid{grid-template-columns:1fr 1fr;}
  .two-col{grid-template-columns:1fr;}
  .three-col{grid-template-columns:1fr 1fr;}
  .r4{grid-template-columns:1fr 1fr;}
  .inv-row{grid-template-columns:1.2fr 0.6fr 0.6fr 0.6fr 0.7fr 0.6fr 26px;}
}

</style>
<script src="https://cdn.jsdelivr.net/npm/lightweight-charts@4.1.3/dist/lightweight-charts.standalone.production.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
</head>
<body>
<div id="auth-screen">
  <div class="auth-card">
    <div class="auth-logo"><i class="ti ti-trending-up"></i></div>
    <div class="auth-title">TradeLog Pro</div>
    <div class="auth-sub">你的專屬投資日記<br>記錄每一筆交易，累積每一分成長</div>
    <div class="auth-features">
      <div class="auth-feat"><i class="ti ti-calendar-stats"></i><span>日曆總覽 — 紅綠一目瞭然</span></div>
      <div class="auth-feat"><i class="ti ti-notebook"></i><span>每日日記 — 趨勢、進出場、心理全記錄</span></div>
      <div class="auth-feat"><i class="ti ti-chess"></i><span>Playbook — 盤前劇本，紀律進出</span></div>
      <div class="auth-feat"><i class="ti ti-cloud"></i><span>雲端同步 — 帳號登入，資料永遠不丟</span></div>
    </div>
    <button class="google-btn" onclick="loginGoogle()">
      <img src="https://www.gstatic.com/firebasejs/ui/2.0.0/images/auth/google.svg" alt="Google">
      使用 Google 帳號登入
    </button>
    <div class="auth-note">登入即代表你同意服務條款。你的資料只有你自己能看，不會分享給任何人。</div>
  </div>
</div>

<div id="app-screen">
  <div class="topbar">
    <div class="logo">
      <div class="logo-icon"><i class="ti ti-trending-up"></i></div>
      <div class="logo-text">Trade<span>Log</span></div>
    </div>
    <div class="topbar-right">
      <div class="tb-date" id="topbar-date"></div>
      <button class="tb-btn" onclick="showPage('stats',document.getElementById('nav-stats'))"><i class="ti ti-sparkles"></i>AI 週報</button>
      <div class="user-pill" onclick="logoutUser()" title="點擊登出">
        <img id="user-avatar" src="" alt="avatar">
        <span id="user-name"></span>
        <i class="ti ti-logout"></i>
      </div>
    </div>
  </div>
  <div class="main">
    <div class="sidebar">
      <div>
        <div class="nav-label">主選單</div>
        <div class="nav-item active" onclick="showPage('dashboard',this)" id="nav-dashboard"><i class="ti ti-calendar-stats"></i><span>日曆總覽</span></div>
        <div class="nav-item" onclick="openJournalDate(new Date().toISOString().slice(0,10));showPage('journal',this)" id="nav-journal"><i class="ti ti-notebook"></i><span>每日日記</span></div>
        <div class="nav-item" onclick="showPage('playbook',this)" id="nav-playbook"><i class="ti ti-chess"></i><span>Playbook</span></div>
        <div class="nav-item" onclick="showPage('stock',this)" id="nav-stock"><i class="ti ti-chart-candle"></i><span>個股分析</span></div>
        <div class="nav-item" onclick="showPage('chip',this)" id="nav-chip"><i class="ti ti-dna"></i><span>籌碼診斷</span></div>
        <div class="nav-item" onclick="showPage('news',this)" id="nav-news"><i class="ti ti-news"></i><span>新聞情緒</span></div>
        <div class="nav-divider"></div>
        <div class="nav-item" onclick="showPage('stats',this)" id="nav-stats"><i class="ti ti-chart-bar"></i><span>統計 &amp; AI</span></div>
      </div>
      <div class="sidebar-bottom">
        <div class="stat-mini"><div class="stat-mini-lbl">月淨損益</div><div class="stat-mini-val up" id="sidebar-pnl">—</div></div>
        <div class="stat-mini"><div class="stat-mini-lbl">勝率</div><div class="stat-mini-val" id="sidebar-wr">—</div></div>
      </div>
    </div>

    <!-- DASHBOARD -->
    <div class="content active" id="page-dashboard">
      <div class="mgrid">
        <div class="mc" id="dash-mc-pnl"><div class="ml">月淨損益</div><div class="mv up" id="dash-pnl">—</div><div class="md" id="dash-pnl-sub">本月</div></div>
        <div class="mc" id="dash-mc-wr"><div class="ml">整體勝率</div><div class="mv nu" id="dash-wr">—</div><div class="md" id="dash-wr-sub">含損益天數</div></div>
        <div class="mc"><div class="ml">按計畫勝率</div><div class="mv up" id="dash-plan-wr">—</div><div class="md" id="dash-plan-sub">正面標籤+獲利</div></div>
        <div class="mc"><div class="ml">最大單日虧損</div><div class="mv dn" id="dash-max-loss">—</div><div class="md" id="dash-max-loss-sub">尚無虧損紀錄</div></div>
      </div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start;">
        <div class="card">
          <div class="cal-header">
            <span style="font-size:18px;font-weight:700;font-family:var(--mono);color:var(--t0);letter-spacing:.02em;" id="cal-month-lbl">2025 年 5 月</span>
            <div class="cal-nav"><button onclick="calNav(-1)"><i class="ti ti-chevron-left"></i></button><button onclick="calNav(1)"><i class="ti ti-chevron-right"></i></button></div>
          </div>
          <div style="display:flex;gap:12px;font-size:12px;font-family:var(--mono);margin-bottom:12px;font-weight:500;"><span style="color:var(--up)">■ 獲利</span><span style="color:var(--dn)">■ 虧損</span><span style="color:var(--t2)">■ 休市</span></div>
          <div class="cal-dows"><div class="cdow">日</div><div class="cdow">一</div><div class="cdow">二</div><div class="cdow">三</div><div class="cdow">四</div><div class="cdow">五</div><div class="cdow">六</div></div>
          <div class="cal-grid" id="cal-grid"></div>
        </div>
        <div class="card" style="display:flex;flex-direction:column;">
          <!-- 日期標題 + 標籤 -->
          <div style="padding-bottom:10px;border-bottom:1px solid var(--border);margin-bottom:10px;">
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;">
              <div class="cicon green" style="flex-shrink:0;"><i class="ti ti-calendar-event"></i></div>
              <span class="ctitle" id="detail-title" style="font-size:14px;font-weight:600;">點擊日期查看明細</span>
            </div>
            <div id="detail-tags" style="display:flex;flex-wrap:wrap;gap:4px;margin-left:38px;min-height:0;"></div>
          </div>
          <div id="detail-body" style="font-size:14px;color:var(--t2);padding:20px 0;text-align:center;">← 點擊左側日曆的日期</div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <div class="cicon amber"><i class="ti ti-package"></i></div>
          <span class="ctitle">目前庫存</span>
          <span class="csub">截至今日</span>
          <button class="btn-g" style="margin-left:6px;" onclick="addInvRow()"><i class="ti ti-plus"></i>新增</button>
          <button class="btn-g" style="margin-left:6px;" onclick="refreshPricesNow()" title="用 FinMind API 更新現價"><i class="ti ti-refresh"></i>更新現價</button>
          <button class="btn-g" style="margin-left:auto;background:var(--up-d);border-color:var(--up-b);color:var(--up);" onclick="saveInventory()"><i class="ti ti-cloud-upload"></i>儲存到雲端</button>
        </div>
        <div style="display:grid;grid-template-columns:1.4fr 0.8fr 0.8fr 0.8fr 0.8fr 0.7fr 0.7fr 26px;gap:6px;padding:0 2px 8px;border-bottom:1px solid var(--border);margin-bottom:4px;">
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);font-weight:500;">股票名稱</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">股數</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">買入成本</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">現在價格</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">損益金額</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">損益%</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);text-align:center;font-weight:500;">型態</div>
          <div></div>
        </div>
        <div id="inv-rows"></div>
        <div style="display:grid;grid-template-columns:1.4fr 0.8fr 0.8fr 0.8fr 0.8fr 0.7fr 0.7fr 26px;gap:6px;padding:8px 2px 0;border-top:1px solid var(--border);margin-top:8px;">
          <div style="font-size:11px;font-weight:500;color:var(--t0);">合計</div>
          <div></div><div></div><div></div>
          <div class="di inv-pnl up" id="inv-total-pnl" style="font-weight:700;">$0</div>
          <div></div><div></div><div></div>
        </div>
      </div>
    </div>

    <!-- JOURNAL -->
    <div class="content" id="page-journal">
      <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px;">
        <div>
          <div style="font-size:16px;font-weight:700;">每日戰情日記</div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);margin-top:3px;" id="j-date">載入中...</div>
          <!-- 趨勢標籤顯示 -->
          <div id="j-trend-tags-display" style="display:flex;flex-wrap:wrap;gap:4px;margin-top:5px;"></div>
        </div>
        <div style="display:flex;gap:5px;">
          <span class="badge bdone" id="sb0"><i class="ti ti-check" style="font-size:9px"></i> 趨勢</span>
          <span class="badge bcurr" id="sb1">進出場</span>
          <span class="badge btodo" id="sb2">庫存劇本</span>
          <span class="badge btodo" id="sb3">心理</span>
          <span class="badge btodo" id="sb4">完成</span>
        </div>
      </div>
      <div class="stepper">
        <div class="step done" onclick="gotoStep(0)" id="st0"><div class="step-connector"></div><div class="step-circle" id="sc0"><i class="ti ti-check" style="font-size:14px"></i></div><div class="step-lbl">趨勢觀察</div></div>
        <div class="step curr" onclick="gotoStep(1)" id="st1"><div class="step-connector"></div><div class="step-circle" id="sc1">2</div><div class="step-lbl">進出場</div></div>
        <div class="step" onclick="gotoStep(2)" id="st2"><div class="step-connector"></div><div class="step-circle" id="sc2">3</div><div class="step-lbl">庫存劇本</div></div>
        <div class="step" onclick="gotoStep(3)" id="st3"><div class="step-connector"></div><div class="step-circle" id="sc3">4</div><div class="step-lbl">心理檢討</div></div>
        <div class="step" onclick="gotoStep(4)" id="st4"><div class="step-connector"></div><div class="step-circle" id="sc4">5</div><div class="step-lbl">完成 &amp; 儲存</div></div>
      </div>
      <div class="sec-page" id="sp0">
        <div class="card">
          <div class="card-head"><div class="cicon blue"><i class="ti ti-wave-sine"></i></div><span class="ctitle">一、趨勢觀察</span></div>

          <!-- 趨勢標籤（自訂，顯示在日曆和日記標題） -->
          <div class="fg" style="margin-bottom:10px;">
            <div class="fl" style="display:flex;align-items:center;justify-content:space-between;">
              今日市場標籤
              <button onclick="openTrendTagModal()" style="background:none;border:1px dashed var(--border2);border-radius:5px;padding:1px 8px;font-size:9px;color:var(--t3);cursor:pointer;font-family:var(--sans);">+ 新增標籤</button>
            </div>
            <div id="trend-tags-row" style="display:flex;flex-wrap:wrap;gap:6px;min-height:28px;padding:4px 0;">
              <!-- 預設標籤 -->
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="多頭">多頭</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="空頭">空頭</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="盤整">盤整</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="強勢">強勢</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="弱勢">弱勢</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="外資買超">外資買超</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="外資賣超">外資賣超</button>
              <button class="chip" onclick="toggleTrendTag(this)" data-tag="投信認養">投信認養</button>
            </div>
          </div>

          <div class="r2">
            <div class="fg"><div class="fl">大盤 / 櫃買走勢</div><textarea class="di tall" id="j-trend-main" placeholder="加權指數走勢、外資動向..."></textarea></div>
            <div class="fg"><div class="fl">資金流向與族群</div><textarea class="di tall" id="j-trend-funds" placeholder="強勢族群、資金輪動..."></textarea></div>
          </div>
          <div class="step-nav"><button class="s-next" onclick="gotoStep(1)"><i class="ti ti-arrow-right"></i>下一步：進出場</button></div>
        </div>
      </div>
      <div class="sec-page active" id="sp1">
        <div class="card">
          <div class="card-head"><div class="cicon green"><i class="ti ti-list-check"></i></div><span class="ctitle">二、進出場紀錄</span></div>
          <div id="trade-cards">
            <div class="trade-card" id="tc-1">
              <div class="tc-head"><div class="tc-num">1</div><span class="tc-title">第 1 筆交易</span><button class="tc-rm" onclick="rmCard('tc-1')"><i class="ti ti-x"></i></button></div>
              <div class="dir-toggle"><button class="dir-b long on" onclick="setDir(this)"><i class="ti ti-trending-up"></i>做多</button><button class="dir-b short" onclick="setDir(this)"><i class="ti ti-trending-down"></i>做空</button></div>
              <div class="fg" style="margin-bottom:8px;">
                <div class="fl" style="display:flex;align-items:center;justify-content:space-between;">
                  交易分類
                  <button onclick="openAddTagModal('tc1-cats')" style="background:none;border:1px dashed var(--border2);border-radius:5px;padding:1px 7px;font-size:9px;color:var(--t3);cursor:pointer;font-family:var(--sans);">+ 新增標籤</button>
                </div>
                <div class="frow" id="tc1-cats">
                  <button class="chip active" onclick="setCat(this,'tc1-cats')">現股</button>
                  <button class="chip" onclick="setCat(this,'tc1-cats')">當沖</button>
                  <button class="chip" onclick="setCat(this,'tc1-cats')">波段</button>
                  <button class="chip" onclick="setCat(this,'tc1-cats')">AI供應鏈</button>
                  <button class="chip" onclick="setCat(this,'tc1-cats')">半導體</button>
                </div>
              </div>
              <div class="r2" style="margin-bottom:10px;">
                <div class="fg" style="margin-bottom:0">
                  <div class="fl" style="display:flex;align-items:center;justify-content:space-between;">股票代號<span style="font-size:9px;color:var(--blue);font-family:var(--mono);" id="tc1-auto-hint"></span></div>
                  <input class="di" type="text" placeholder="2330 / TSMC" id="tc1-stock-input" oninput="onStockInput(this,'tc-1')" onblur="if(this.value.trim()) triggerStockAnalysis(this.value.trim(),'tc-1')">
                </div>
                <div class="fg" style="margin-bottom:0">
                  <div class="fl">套用 Playbook</div>
                  <select class="di" id="tc1-pb-select" style="cursor:pointer" onchange="applyPbToCard('tc-1',this.value)">
                    <option value="">不套用</option>
                  </select>
                </div>
              </div>
              <div class="fg"><div class="fl">交易動作</div>
                <div class="achips">
                  <span class="achip buy on" onclick="togAchip(this)"><i class="ti ti-trending-up"></i>買進</span>
                  <span class="achip sell" onclick="togAchip(this)"><i class="ti ti-trending-down"></i>賣出</span>
                  <span class="achip add" onclick="togAchip(this)"><i class="ti ti-plus"></i>加碼</span>
                  <span class="achip cut" onclick="togAchip(this)"><i class="ti ti-minus"></i>減碼</span>
                </div>
              </div>
              <div class="r4" style="margin-bottom:4px;">
                <div class="fg" style="margin-bottom:0"><div class="fl">成交價</div><input class="di" type="number" placeholder="0" id="tc1-price" oninput="calcTradeFee('tc-1');updateCardRRFromInputs('tc-1')"></div>
                <div class="fg" style="margin-bottom:0"><div class="fl">股數</div><input class="di" type="number" placeholder="0" id="tc1-shares" oninput="calcTradeFee('tc-1')"></div>
                <div class="fg" style="margin-bottom:0"><div class="fl">停損價</div><input class="di dnv" type="number" placeholder="0" id="tc1-stop" oninput="updateCardRRFromInputs('tc-1')"></div>
                <div class="fg" style="margin-bottom:0"><div class="fl">目標價</div><input class="di upv" type="number" placeholder="0" id="tc1-target" oninput="updateCardRRFromInputs('tc-1')"></div>
              </div>
              <!-- 風報比即時顯示 -->
              <div id="tc-1-rr" style="font-size:11px;font-family:var(--mono);padding:4px 2px 6px;display:none;"></div>
              <!-- 手續費試算提示 -->
              <div id="tc1-fee-hint" style="font-size:10px;color:var(--blue);font-family:var(--mono);padding:4px 2px 8px;display:none;"></div>
              <div class="fg"><div class="fl">交易邏輯 / 進場理由</div><textarea class="di tall" placeholder="帶量突破、回測均線..."></textarea></div>
              <div class="fg" style="margin-top:10px;"><div class="fl">此筆心理標籤</div>
                <div class="tags-sec">
                  <div><div class="tgl pos">正向</div><div class="tags-row"><span class="tchip g on" onclick="this.classList.toggle('on')"><i class="ti ti-check"></i>按計畫執行</span><span class="tchip g on" onclick="this.classList.toggle('on')"><i class="ti ti-shield"></i>嚴守紀律</span><span class="tchip g" onclick="this.classList.toggle('on')"><i class="ti ti-bulb"></i>理性分析</span></div></div>
                  <div><div class="tgl neg">負向</div><div class="tags-row"><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-flame"></i>FOMO</span><span class="tchip w" onclick="this.classList.toggle('on')"><i class="ti ti-alert-triangle"></i>盲目跟風</span><span class="tchip w" onclick="this.classList.toggle('on')"><i class="ti ti-refresh"></i>過度交易</span><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-mood-angry"></i>報復交易</span></div></div>
                </div>
              </div>
              <div class="tc-analysis" id="tc-1-analysis">
                <div class="tc-analysis-header">
                  <div style="display:flex;align-items:center;gap:8px;"><i class="ti ti-chart-candle" style="font-size:16px;color:var(--blue)"></i><span style="font-size:12px;font-weight:500;" id="tc-1-analysis-title">個股即時分析</span></div>
                  <button onclick="showPage('stock',document.getElementById('nav-stock'))" style="font-size:10px;background:var(--blue-d);border:1px solid var(--blue-b);border-radius:6px;padding:4px 10px;color:var(--blue);cursor:pointer;font-family:var(--sans);display:flex;align-items:center;gap:4px;"><i class="ti ti-external-link" style="font-size:12px"></i>完整分析頁</button>
                </div>
                <div class="tc-analysis-mini" id="tc-1-metrics">
                  <div class="tc-analysis-mc"><div class="tc-analysis-lbl">最新收盤</div><div class="tc-analysis-val nu" id="tc-1-close">—</div></div>
                  <div class="tc-analysis-mc"><div class="tc-analysis-lbl">RSI(14)</div><div class="tc-analysis-val nu" id="tc-1-rsi">—</div></div>
                  <div class="tc-analysis-mc"><div class="tc-analysis-lbl">法人合計</div><div class="tc-analysis-val nu" id="tc-1-inst">—</div></div>
                  <div class="tc-analysis-mc"><div class="tc-analysis-lbl">融資變化</div><div class="tc-analysis-val nu" id="tc-1-margin">—</div></div>
                </div>
                <div id="tc-1-ai-summary" style="background:var(--purple-d);border:1px solid var(--purple-b);border-radius:var(--r);padding:10px 12px;font-size:11px;color:#5a3080;line-height:1.7;"><div style="display:flex;align-items:center;gap:6px;margin-bottom:6px;"><div class="ai-dots2"><span></span><span></span><span></span></div><span style="font-size:10px;color:var(--purple);">Claude 分析中...</span></div></div>
              </div>
            </div>
          </div>
          <button class="add-tc-btn" onclick="addCard()"><i class="ti ti-plus"></i>新增一筆交易</button>
          <div class="step-nav">
            <button class="s-prev" onclick="gotoStep(0)"><i class="ti ti-arrow-left"></i>趨勢</button>
            <button class="s-next" onclick="gotoStep(2)"><i class="ti ti-arrow-right"></i>庫存劇本</button>
          </div>
        </div>
      </div>
      <div class="sec-page" id="sp2">
        <div class="card">
          <div class="card-head"><div class="cicon amber"><i class="ti ti-package"></i></div><span class="ctitle">三、庫存管理與明日劇本</span></div>

          <!-- 今日進出場摘要 -->
          <div id="sp2-trade-summary" style="display:none;margin-bottom:14px;background:var(--blue-d);border:1px solid var(--blue-b);border-radius:var(--r);padding:12px 14px;">
            <div style="font-size:10px;color:var(--blue);font-family:var(--mono);font-weight:600;margin-bottom:8px;">📋 今日進出場摘要</div>
            <div id="sp2-trade-list" style="font-size:12px;color:var(--t1);line-height:1.9;"></div>
          </div>

          <!-- 目前庫存（自動載入） -->
          <div class="fg">
            <div class="fl" style="display:flex;align-items:center;justify-content:space-between;">
              目前庫存狀態
              <span style="font-size:9px;color:var(--t3);font-family:var(--mono);" id="sp2-inv-time">—</span>
            </div>
            <div id="inv-rows-j">
              <div style="color:var(--t3);font-size:11px;padding:8px 0;">載入中...</div>
            </div>
          </div>

          <!-- 明日劇本 IF→THEN -->
          <div class="fg">
            <div class="fl" style="display:flex;align-items:center;justify-content:space-between;">
              明日操作 IF → THEN 劇本
              <span style="font-size:9px;color:var(--t3);font-family:var(--mono);">可針對每檔庫存股票設定</span>
            </div>
            <div class="ifthen" id="ifthen-rows">
              <div class="ift-check" style="grid-template-columns:80px 1fr 52px 1fr 36px;">
                <select class="di ift-stock" style="font-size:11px;padding:6px 6px;cursor:pointer;" title="選擇對應庫存"><option value="">全部</option></select>
                <input class="di" type="text" placeholder="觸發條件（如：突破 $120）">
                <div class="ifl then">THEN</div>
                <input class="di" type="text" placeholder="執行動作（如：出清）">
                <label class="cbox-wrap"><input type="checkbox" onchange="toggleCbox(this)"><span class="cbox"></span></label>
              </div>
            </div>
            <button class="add-tc-btn" style="margin-top:8px;" onclick="addIfthen()"><i class="ti ti-plus"></i>新增條件</button>
          </div>

          <div class="step-nav">
            <button class="s-prev" onclick="gotoStep(1)"><i class="ti ti-arrow-left"></i>進出場</button>
            <button class="s-next" onclick="gotoStep(3)"><i class="ti ti-arrow-right"></i>心理檢討</button>
          </div>
        </div>
      </div>
      <div class="sec-page" id="sp3">
        <div class="card">
          <div class="card-head"><div class="cicon purple"><i class="ti ti-brain"></i></div><span class="ctitle">四、交易心理與紀律檢討</span></div>
          <div class="fg"><div class="fl">今日整體情緒狀態</div>
            <input type="range" min="0" max="4" value="2" step="1" oninput="updEmo(this.value)">
            <div style="display:flex;justify-content:space-between;font-size:9px;color:var(--t3);margin-top:5px;font-family:var(--mono);"><span>極度焦慮</span><span>有點焦慮</span><span id="emo-lbl" style="color:var(--blue);font-weight:500">平靜</span><span>過度自信</span><span>衝動</span></div>
          </div>
          <div class="fg"><div class="fl">今日整體心理標籤</div>
            <div class="tags-sec">
              <div><div class="tgl pos">正向</div><div class="tags-row"><span class="tchip g on" onclick="this.classList.toggle('on')"><i class="ti ti-check"></i>按計畫執行</span><span class="tchip g on" onclick="this.classList.toggle('on')"><i class="ti ti-shield"></i>嚴守紀律</span><span class="tchip g" onclick="this.classList.toggle('on')"><i class="ti ti-bulb"></i>理性分析</span><span class="tchip g" onclick="this.classList.toggle('on')"><i class="ti ti-heart"></i>自律達成</span></div></div>
              <div><div class="tgl neg">負向</div><div class="tags-row"><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-flame"></i>FOMO</span><span class="tchip w" onclick="this.classList.toggle('on')"><i class="ti ti-alert-triangle"></i>盲目跟風</span><span class="tchip w" onclick="this.classList.toggle('on')"><i class="ti ti-refresh"></i>過度交易</span><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-mood-angry"></i>報復交易</span></div></div>
            </div>
          </div>
          <!-- 今日損益輸入（直接填，會同步到日曆） -->
          <div class="fg" style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:var(--r);padding:12px 14px;">
            <div class="fl" style="color:var(--blue);margin-bottom:8px;font-weight:500;">📊 今日已實現損益（填入後顯示於日曆）</div>
            <div style="display:flex;align-items:center;gap:10px;">
              <div style="display:flex;gap:6px;flex-shrink:0;">
                <button id="pnl-sign-btn" onclick="togglePnlSign()" style="background:var(--up-d);border:1.5px solid var(--up-b);border-radius:8px;padding:8px 14px;color:var(--up);font-size:14px;font-weight:700;cursor:pointer;font-family:var(--mono);min-width:44px;" title="點擊切換正負">+</button>
              </div>
              <input id="j-pnl" class="di" type="number" min="0" placeholder="例如 3500（不含符號）" style="flex:1;font-size:14px;font-weight:600;font-family:var(--mono);" oninput="updatePnlPreview()">
              <div id="j-pnl-preview" style="font-size:16px;font-weight:800;font-family:var(--mono);color:var(--up);min-width:80px;text-align:right;">—</div>
            </div>
            <div style="font-size:10px;color:var(--blue);margin-top:6px;">留空 = 本日無交易紀錄</div>
          </div>
          <div class="r2">
            <div class="fg" style="margin-bottom:0"><div class="fl">紀律執行 / 筆記</div><textarea id="j-notes" class="di tall" placeholder="今天是否按照原本的計畫執行？有什麼值得記錄的..."></textarea></div>
            <div class="fg" style="margin-bottom:0"><div class="fl">今日總結</div><textarea id="j-summary" class="di tall" placeholder="今天最大的收穫或需要改進的地方..."></textarea></div>
          </div>
          <div class="step-nav" style="margin-top:16px;">
            <button class="s-prev" onclick="gotoStep(2)"><i class="ti ti-arrow-left"></i>庫存劇本</button>
            <button class="s-next fin" onclick="this.disabled=true;this.innerHTML='<i class=\'ti ti-loader-2\' style=\'animation:spin 1s linear infinite\'></i> 儲存中...';saveJournal().then(()=>{gotoStep(4);this.disabled=false;this.innerHTML='<i class=\'ti ti-cloud-upload\'></i>儲存到雲端 &amp; 完成';})"><i class="ti ti-cloud-upload"></i>儲存到雲端 &amp; 完成</button>
          </div>
        </div>
      </div>
      <div class="sec-page" id="sp4">
        <div class="card">
          <div class="card-head"><div class="cicon green"><i class="ti ti-circle-check"></i></div><span class="ctitle">日記已完成並儲存</span><span class="badge bdone" style="margin-left:auto">已儲存 ✓</span></div>
          <!-- 儲存結果摘要 -->
          <div id="sp4-summary" style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;margin-bottom:14px;">
            <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:12px;text-align:center;">
              <div style="font-size:9px;color:var(--t3);font-family:var(--mono);margin-bottom:6px;">今日損益</div>
              <div id="sp4-pnl" style="font-size:22px;font-weight:800;font-family:var(--mono);color:var(--t3);">—</div>
            </div>
            <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:12px;text-align:center;">
              <div style="font-size:9px;color:var(--t3);font-family:var(--mono);margin-bottom:6px;">交易筆數</div>
              <div id="sp4-trades" style="font-size:22px;font-weight:800;font-family:var(--mono);color:var(--t0);">—</div>
            </div>
            <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:12px;text-align:center;">
              <div style="font-size:9px;color:var(--t3);font-family:var(--mono);margin-bottom:6px;">庫存變動</div>
              <div id="sp4-inv" style="font-size:14px;font-weight:700;color:var(--t0);">—</div>
            </div>
          </div>
          <!-- 今日交易摘要 -->
          <div id="sp4-trades-list" style="margin-bottom:14px;"></div>
          <div class="aibox" style="margin-bottom:12px;"><i class="ti ti-sparkles"></i><div class="aitxt">日記已成功儲存到雲端，進出場交易已自動同步到庫存。下次登入即可繼續查看歷史紀錄。</div></div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            <button class="btn-p" style="flex:2" onclick="gotoStep(0)"><i class="ti ti-refresh"></i>重新填寫</button>
            <button class="btn-s" onclick="showPage('stats',document.getElementById('nav-stats'))"><i class="ti ti-chart-bar"></i>查看統計</button>
            <button class="btn-g" style="color:var(--dn);border-color:var(--dn-b);background:var(--dn-d);" onclick="confirmDeleteJournal(window._journalDate||new Date().toISOString().slice(0,10));showPage('cal',document.getElementById('nav-cal'))"><i class="ti ti-trash"></i>刪除此日記</button>
          </div>
        </div>
      </div>
    </div>

    <!-- PLAYBOOK -->
    <div class="content" id="page-playbook">
      <div style="display:flex;align-items:center;justify-content:space-between;">
        <div style="font-size:16px;font-weight:700;">交易劇本 Playbook</div>
        <button class="btn-g" onclick="addPlaybookCloud()"><i class="ti ti-plus"></i>新增劇本</button>
      </div>
      <div class="save-bar"><i class="ti ti-cloud"></i><span>劇本編輯完後請點各卡片的「儲存」按鈕，資料會上傳到你的帳號</span></div>
      <div id="pb-list"></div>
    </div>

    <!-- STOCK ANALYSIS -->
    <div class="content" id="page-stock">
      <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin-bottom:4px;">
        <div style="font-size:16px;font-weight:700;">個股 AI 技術分析</div>
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
          <input class="di" id="sa-stock-input" type="text" style="width:120px;font-size:12px;padding:7px 10px;" placeholder="股票代號 2330">
          <select class="di" id="sa-days" style="width:100px;font-size:11px;padding:7px 10px;cursor:pointer;"><option value="60">近 60 日</option><option value="90" selected>近 90 日</option><option value="120">近 120 日</option></select>
          <button onclick="saRunAnalysis()" style="background:var(--blue);border:none;border-radius:8px;padding:8px 16px;color:#fff;font-size:12px;font-weight:500;cursor:pointer;display:flex;align-items:center;gap:6px;font-family:var(--sans);"><i class="ti ti-chart-bar"></i>分析</button>
          <div style="display:flex;align-items:center;gap:6px;">
            <input class="di" id="sa-finmind-token" type="password" style="width:140px;font-size:11px;padding:7px 10px;" placeholder="FinMind Token">
            <button onclick="sasSaveKeys()" class="btn-g" style="font-size:10px;padding:6px 10px;white-space:nowrap;"><i class="ti ti-key"></i>儲存</button>
          </div>
        </div>
      </div>
      <div id="sa-empty" style="text-align:center;padding:60px 20px;color:var(--t2);">
        <i class="ti ti-chart-candle" style="font-size:48px;display:block;margin-bottom:14px;color:var(--t3);"></i>
        <div style="font-size:15px;font-weight:500;color:var(--t1);margin-bottom:6px;">輸入台股代號開始分析</div>
        <div style="font-size:12px;line-height:1.7;">K 線圖 · MA5/10/20/60 · RSI · 法人買賣超 · 融資融券 · AI 深度分析</div>
      </div>
      <div id="sa-results" style="display:none;flex-direction:column;gap:14px;">
        <div class="card">
          <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;margin-bottom:12px;">
            <div><div style="font-size:18px;font-weight:700;" id="sa-title">—</div><div style="font-size:11px;color:var(--t2);font-family:var(--mono);margin-top:2px;" id="sa-period">—</div></div>
            <div id="sa-rsi-badge"></div>
          </div>
          <div class="sa-grid">
            <div class="sa-mc"><div class="sa-ml">開始價格</div><div class="sa-mv nu" id="sa-m-start">—</div></div>
            <div class="sa-mc"><div class="sa-ml">最新收盤</div><div class="sa-mv nu" id="sa-m-end">—</div></div>
            <div class="sa-mc"><div class="sa-ml">期間漲跌</div><div class="sa-mv nu" id="sa-m-chg">—</div><div class="sa-md" id="sa-m-chg-abs">—</div></div>
            <div class="sa-mc"><div class="sa-ml">當前 RSI(14)</div><div class="sa-mv nu" id="sa-m-rsi">—</div><div class="sa-md" id="sa-m-rsi-st">—</div></div>
            <div class="sa-mc"><div class="sa-ml">最高 / 最低</div><div class="sa-mv nu" id="sa-m-hl" style="font-size:13px;">—</div></div>
          </div>
          <div id="sa-rsi-warn" style="display:none;margin-top:8px;"></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon blue"><i class="ti ti-chart-candle"></i></div><span class="ctitle" id="sa-chart-title">K 線圖</span><span class="csub" id="sa-chart-sub">—</span></div>
          <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:8px;">
            <div style="display:flex;align-items:center;gap:4px;font-size:10px;color:var(--t2);font-family:var(--mono);"><div style="width:12px;height:3px;border-radius:2px;background:#e8c84a;"></div>MA5</div>
            <div style="display:flex;align-items:center;gap:4px;font-size:10px;color:var(--t2);font-family:var(--mono);"><div style="width:12px;height:3px;border-radius:2px;background:#4a90e2;"></div>MA10</div>
            <div style="display:flex;align-items:center;gap:4px;font-size:10px;color:var(--t2);font-family:var(--mono);"><div style="width:12px;height:3px;border-radius:2px;background:#e24a8f;"></div>MA20</div>
            <div style="display:flex;align-items:center;gap:4px;font-size:10px;color:var(--t2);font-family:var(--mono);"><div style="width:12px;height:3px;border-radius:2px;background:#4ae28f;"></div>MA60</div>
          </div>
          <div id="sa-chart" style="width:100%;height:360px;border-radius:8px;overflow:hidden;"></div>
          <div style="font-size:11px;color:var(--t2);font-family:var(--mono);margin:8px 0 4px;">RSI（14 日）</div>
          <div id="sa-rsi-chart" style="width:100%;height:140px;border-radius:8px;overflow:hidden;"></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon amber"><i class="ti ti-building-bank"></i></div><span class="ctitle">法人買賣超（近 10 日）</span><span class="csub" id="sa-inst-date">—</span></div>
          <div class="inst-grid2">
            <div class="inst-card2"><div class="inst-name2">外資</div><div class="inst-val2" id="sa-inst-f">—</div><div class="inst-bar2"><div class="inst-bar2-fill" id="sa-inst-f-bar" style="width:0%"></div></div></div>
            <div class="inst-card2"><div class="inst-name2">投信</div><div class="inst-val2" id="sa-inst-t">—</div><div class="inst-bar2"><div class="inst-bar2-fill" id="sa-inst-t-bar" style="width:0%"></div></div></div>
            <div class="inst-card2"><div class="inst-name2">自營商</div><div class="inst-val2" id="sa-inst-d">—</div><div class="inst-bar2"><div class="inst-bar2-fill" id="sa-inst-d-bar" style="width:0%"></div></div></div>
          </div>
          <div style="overflow-x:auto;margin-top:12px;"><table class="sa-table"><thead><tr><th style="text-align:left">日期</th><th>外資</th><th>投信</th><th>自營商</th><th>合計</th></tr></thead><tbody id="sa-inst-tbody"></tbody></table></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon dn"><i class="ti ti-scale"></i></div><span class="ctitle">融資融券</span><span class="csub" id="sa-margin-date">—</span></div>
          <div class="sa-grid" style="grid-template-columns:repeat(4,1fr);">
            <div class="sa-mc"><div class="sa-ml">融資餘額</div><div class="sa-mv nu" id="sa-mg-loan">—</div></div>
            <div class="sa-mc"><div class="sa-ml">融資變化</div><div class="sa-mv nu" id="sa-mg-loan-d">—</div></div>
            <div class="sa-mc"><div class="sa-ml">融券餘額</div><div class="sa-mv nu" id="sa-mg-short">—</div></div>
            <div class="sa-mc"><div class="sa-ml">融券變化</div><div class="sa-mv nu" id="sa-mg-short-d">—</div></div>
          </div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon purple"><i class="ti ti-sparkles"></i></div><span class="ctitle">AI 深度技術分析</span><span class="badge" id="sa-ai-badge" style="margin-left:auto;font-size:9px;padding:2px 8px;border-radius:20px;background:var(--bg3);color:var(--t3);border:1px solid var(--border);font-family:var(--mono);">分析中...</span></div>
          <div id="sa-ai-content"><div style="text-align:center;padding:24px;"><div class="ai-dots2" style="justify-content:center;margin-bottom:10px;"><span></span><span></span><span></span></div><div style="font-size:12px;color:var(--purple);">Claude 正在分析技術數據...</div></div></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon blue"><i class="ti ti-table"></i></div><span class="ctitle">歷史數據（含技術指標）</span><span class="csub">近 15 筆</span></div>
          <div style="overflow-x:auto;"><table class="sa-table"><thead><tr><th style="text-align:left">日期</th><th>開盤</th><th>最高</th><th>最低</th><th>收盤</th><th>成交量</th><th>MA5</th><th>MA20</th><th>RSI</th><th>狀態</th></tr></thead><tbody id="sa-data-tbody"></tbody></table></div>
        </div>
        <div style="background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);padding:10px 14px;font-size:11px;color:var(--amber);">⚠️ 本分析僅供教育研究用途，不構成投資建議。</div>
      </div>
    </div>

    <!-- CHIP ANALYSIS -->
    <div class="content" id="page-chip">
      <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin-bottom:4px;">
        <div><div style="font-size:16px;font-weight:700;">籌碼診斷</div><div style="font-size:11px;color:var(--t2);font-family:var(--mono);margin-top:2px;">五階段籌碼面量化分析</div></div>
        <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
          <input id="chip-stock-id" class="di" type="text" placeholder="股票代號 如 2330" style="width:140px;font-size:12px;padding:7px 10px;">
          <select id="chip-range" class="di" style="width:110px;font-size:12px;padding:7px 10px;cursor:pointer;"><option value="1">近 1 個月</option><option value="3" selected>近 3 個月</option><option value="6">近 6 個月</option><option value="12">近 1 年</option></select>
          <button class="btn-p" onclick="runChipAnalysis()" id="chip-btn" style="padding:8px 16px;font-size:12px;"><i class="ti ti-dna"></i>分析籌碼</button>
        </div>
      </div>
      <div id="chip-empty" style="text-align:center;padding:64px 20px;color:var(--t2);">
        <i class="ti ti-dna" style="font-size:48px;display:block;margin-bottom:14px;color:var(--t3);"></i>
        <div style="font-size:15px;font-weight:500;color:var(--t1);margin-bottom:6px;">輸入股票代號開始籌碼診斷</div>
        <div style="font-size:12px;line-height:1.8;">三大法人 · 大戶散戶 · 融資融券 · 周轉率 · AI 籌碼解讀</div>
      </div>
      <div id="chip-results" style="display:none;flex-direction:column;gap:14px;">
        <div class="card" id="chip-header-card">
          <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;">
            <div><div style="font-size:18px;font-weight:700;" id="chip-company-name">—</div><div style="font-size:11px;color:var(--t2);font-family:var(--mono);margin-top:3px;" id="chip-company-info">—</div></div>
            <div style="display:flex;gap:10px;flex-wrap:wrap;">
              <div class="mc" style="min-width:110px;padding:10px 14px;"><div class="ml">最新收盤</div><div class="mv nu" id="chip-price">—</div><div class="md" id="chip-price-chg">—</div></div>
              <div class="mc" style="min-width:110px;padding:10px 14px;"><div class="ml">發行總張數</div><div class="mv nu" id="chip-total-shares" style="font-size:16px;">—</div></div>
              <div class="mc" style="min-width:110px;padding:10px 14px;"><div class="ml">近5日均量</div><div class="mv nu" id="chip-avg-vol" style="font-size:16px;">—</div></div>
            </div>
          </div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon purple"><i class="ti ti-gauge"></i></div><span class="ctitle">籌碼綜合評分 Chip Score</span><span id="chip-score-badge" class="badge btodo" style="margin-left:auto;font-size:11px;padding:4px 12px;">計算中...</span></div>
          <div style="display:flex;align-items:center;gap:20px;flex-wrap:wrap;">
            <div style="text-align:center;flex-shrink:0;"><div id="chip-score-num" style="font-size:52px;font-weight:800;font-family:var(--mono);line-height:1;">—</div><div style="font-size:12px;color:var(--t2);margin-top:4px;">/ 10 分</div></div>
            <div style="flex:1;min-width:200px;"><div id="chip-score-breakdown" style="display:flex;flex-direction:column;gap:6px;"></div></div>
            <div style="flex:1;min-width:160px;">
              <div style="background:linear-gradient(to right,#ff4444,#ffaa00,#ffdd00,#88dd00,#22cc44);height:14px;border-radius:7px;position:relative;margin-bottom:6px;">
                <div id="chip-score-pointer" style="position:absolute;top:-3px;width:20px;height:20px;background:#fff;border:2px solid var(--t0);border-radius:50%;transform:translateX(-50%);transition:left .5s;left:50%;"></div>
              </div>
              <div style="display:flex;justify-content:space-between;font-size:9px;color:var(--t3);font-family:var(--mono);"><span>0 籌碼渙散</span><span>5 中性</span><span>10 高度集中</span></div>
            </div>
          </div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">
          <div class="card"><div class="card-head"><div class="cicon blue"><i class="ti ti-building-bank"></i></div><span class="ctitle">① 三大法人動向</span></div><div id="chip-inst-content" style="font-size:12px;color:var(--t1);line-height:1.8;"></div><canvas id="chip-inst-chart" height="120" style="margin-top:12px;"></canvas></div>
          <div class="card"><div class="card-head"><div class="cicon amber"><i class="ti ti-users"></i></div><span class="ctitle">② 大戶 vs 散戶流向</span></div><div id="chip-holder-content" style="font-size:12px;color:var(--t1);line-height:1.8;"></div><canvas id="chip-holder-chart" height="120" style="margin-top:12px;"></canvas></div>
          <div class="card"><div class="card-head"><div class="cicon dn"><i class="ti ti-scale"></i></div><span class="ctitle">③ 融資券與軋空風險</span></div><div id="chip-margin-content" style="font-size:12px;color:var(--t1);line-height:1.8;"></div><canvas id="chip-margin-chart" height="120" style="margin-top:12px;"></canvas></div>
          <div class="card"><div class="card-head"><div class="cicon blue"><i class="ti ti-rotate"></i></div><span class="ctitle">④ 周轉率與投信認養</span></div><div id="chip-turnover-content" style="font-size:12px;color:var(--t1);line-height:1.8;"></div><canvas id="chip-turnover-chart" height="120" style="margin-top:12px;"></canvas></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon purple"><i class="ti ti-sparkles"></i></div><span class="ctitle">⑤ AI 籌碼深度解讀</span><span id="chip-ai-badge" class="badge btodo" style="margin-left:auto;font-family:var(--mono);">分析中...</span></div>
          <div id="chip-ai-content" style="font-size:12px;color:var(--t1);line-height:1.9;"><div style="text-align:center;padding:24px;"><div class="ai-loading-dots"><span></span><span></span><span></span></div><div style="font-size:12px;color:var(--purple);margin-top:10px;">Claude 正在解讀籌碼結構...</div></div></div>
        </div>
        <div style="background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);padding:10px 14px;font-size:11px;color:var(--amber);">⚠️ 本分析僅供學術研究與教育用途，不構成任何買賣建議。</div>
      </div>
    </div>

    <!-- NEWS (placeholder) -->
    <div class="content" id="page-news">
      <div style="font-size:16px;font-weight:700;margin-bottom:16px;">新聞情緒</div>
      <div class="card" style="text-align:center;padding:48px 20px;color:var(--t2);">
        <i class="ti ti-news" style="font-size:48px;display:block;margin-bottom:14px;color:var(--t3);"></i>
        <div style="font-size:15px;font-weight:500;color:var(--t1);margin-bottom:6px;">新聞情緒分析</div>
        <div style="font-size:12px;">即將推出 — 整合新聞 API 進行情緒評分</div>
      </div>
    </div>

    <!-- STATS -->
    <div class="content" id="page-stats">
      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:4px;">
        <div style="font-size:16px;font-weight:700;">統計與 AI 教練</div>
        <div style="display:flex;gap:8px;align-items:center;">
          <div style="font-size:10px;color:var(--t2);font-family:var(--mono);">API Key：</div>
          <input id="api-key-input" type="password" class="di" style="width:180px;font-size:11px;padding:5px 10px;" placeholder="sk-ant-api03-...">
          <button class="btn-g" onclick="saveApiKey()"><i class="ti ti-key"></i>儲存</button>
        </div>
      </div>
      <div class="mgrid" id="stats-grid">
        <div class="mc"><div class="ml">總交易筆數</div><div class="mv nu" id="stat-total">-</div><div class="md">累計</div></div>
        <div class="mc"><div class="ml">正向標籤佔比</div><div class="mv up" id="stat-pos">-</div><div class="md">按計畫 / 嚴守紀律</div></div>
        <div class="mc"><div class="ml">負向標籤佔比</div><div class="mv dn" id="stat-neg">-</div><div class="md">FOMO / 盲目跟風</div></div>
        <div class="mc"><div class="ml">日記完成天數</div><div class="mv nu" id="stat-days">-</div><div class="md">有記錄的天數</div></div>
      </div>
      <div class="two-col">
        <div class="card">
          <div class="card-head"><div class="cicon blue"><i class="ti ti-chart-bar"></i></div><span class="ctitle">心理標籤分析</span><span class="csub" id="stats-range">載入中...</span></div>
          <div id="tag-bars"><div style="text-align:center;padding:24px;color:var(--t2);font-size:12px;">點擊「生成 AI 分析」載入資料</div></div>
        </div>
        <div class="card">
          <div class="card-head"><div class="cicon purple"><i class="ti ti-sparkles"></i></div><span class="ctitle">AI 教練分析</span><span class="badge" id="ai-status-badge" style="margin-left:auto;background:var(--bg3);color:var(--t3);border:1px solid var(--border);">尚未生成</span></div>
          <div style="display:flex;gap:6px;margin-bottom:12px;flex-wrap:wrap;">
            <button class="chip active" onclick="setAiRange(this,'week')">本週</button>
            <button class="chip" onclick="setAiRange(this,'month')">本月</button>
            <button class="chip" onclick="setAiRange(this,'all')">全部</button>
          </div>
          <div id="ai-report-content"><div style="text-align:center;padding:32px 0;color:var(--t2);"><i class="ti ti-robot" style="font-size:36px;display:block;margin-bottom:12px;color:var(--purple);"></i><div style="font-size:13px;margin-bottom:6px;">AI 教練尚未分析</div><div style="font-size:11px;color:var(--t3);">填寫幾天日記後，AI 會根據你的真實資料給出個人化建議</div></div></div>
          <div id="ai-loading" style="display:none;text-align:center;padding:24px 0;"><div class="ai-loading-dots"><span></span><span></span><span></span></div><div style="font-size:12px;color:var(--purple);margin-top:10px;">Claude 正在分析...</div></div>
          <div style="display:flex;gap:8px;margin-top:14px;">
            <button class="btn-p" style="flex:1;" onclick="generateAiReport()"><i class="ti ti-sparkles"></i>生成 AI 分析</button>
            <button class="btn-s" onclick="generateFullReport()" style="flex:1;"><i class="ti ti-file-text"></i>完整覆盤報告</button>
          </div>
        </div>
      </div>
      <div class="card" id="full-report-card" style="display:none;">
        <div class="card-head"><div class="cicon purple"><i class="ti ti-file-analytics"></i></div><span class="ctitle">完整覆盤報告</span><button onclick="copyReport()" class="btn-g" style="margin-left:auto;"><i class="ti ti-copy"></i>複製</button></div>
        <div id="full-report-content" style="font-size:13px;color:var(--t1);line-height:1.9;white-space:pre-wrap;"></div>
      </div>
    </div>

  </div>
</div>
<script>
function appendPlaybookCard(id, data){
  const num       = id.replace('pb-','');
  const typeClass = data.type==='rev' ? 'rev' : 'trend';
  const typeLabel = data.type==='rev' ? '逆勢反彈' : '趨勢跟隨';
  const stop      = parseFloat(data.stopPrice)    || 0;
  const entry_p   = parseFloat(data.entryPrice)   || 0;
  const target    = parseFloat(data.targetPrice)  || 0;
  const target2   = parseFloat(data.target2Price) || 0;
  // 風報比公式：(目標-進場) / (進場-停損) → 1:N
  const calcRR = (t, e, s) => {
    if(!t||!e||!s||e<=s) return null;
    const r = (t-e)/(e-s);
    return r > 0 ? r.toFixed(2) : null;
  };
  const rr1 = calcRR(target,  entry_p, stop);
  const rr2 = calcRR(target2, entry_p, stop);
  const mkBadge = (t, rr, label) => {
    if(!stop||!t) return '';
    const isUp = t > stop;
    const rrTxt = rr ? ` (1:${rr})` : '';
    return `<span style="background:${isUp?'var(--dn-d)':'var(--amber-d)'};color:${isUp?'var(--dn)':'var(--amber)'};border:1px solid ${isUp?'var(--dn-b)':'var(--amber-b)'};border-radius:5px;padding:2px 8px;font-size:10px;font-family:var(--mono);">${label} $${t}${rrTxt}</span>`;
  };
  const stopBadge = stop ? `<span style="background:var(--up-d);color:var(--up);border:1px solid var(--up-b);border-radius:5px;padding:2px 8px;font-size:10px;font-family:var(--mono);">停損 $${stop}</span>` : '';
  const rrBadge = stopBadge + mkBadge(target,'','T1 $'.replace('T1 $','') || calcRR(target,entry_p,stop), '🎯T1') + mkBadge(target2, rr2, '🎯T2');
  // 重新定義：停損badge + T1 badge + T2 badge
  const rrBadgeFull = [
    stop   ? `<span style="background:var(--up-d);color:var(--up);border:1px solid var(--up-b);border-radius:5px;padding:2px 8px;font-size:10px;font-family:var(--mono);">停損 $${stop}${entry_p?' | 進場 $'+entry_p:''}</span>` : '',
    target ? `<span style="background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);border-radius:5px;padding:2px 8px;font-size:10px;font-family:var(--mono);">🎯T1 $${target}${rr1?' (1:'+rr1+')':''}</span>` : '',
    target2? `<span style="background:var(--dn-d);color:var(--dn);border:1px solid var(--dn-b);border-radius:5px;padding:2px 8px;font-size:10px;font-family:var(--mono);">🎯T2 $${target2}${rr2?' (1:'+rr2+')':''}</span>` : '',
  ].filter(Boolean).join(' ');

  // ── view 模式：六段摘要 ─────────────────
  const mkSection = (icon, color, label, val) => val
    ? `<div style="display:flex;align-items:flex-start;gap:7px;padding:5px 0;border-bottom:1px solid var(--border);">
        <i class="ti ${icon}" style="font-size:13px;color:${color};flex-shrink:0;margin-top:1px;"></i>
        <div><div style="font-size:9px;color:var(--t3);font-family:var(--mono);margin-bottom:1px;">${label}</div>
        <div style="font-size:11px;color:var(--t1);line-height:1.6;">${val}</div></div>
       </div>` : '';

  const mkInput = (num, fid, label, icon, color, val, ph, type='text', extra='') =>
    `<div style="display:flex;align-items:flex-start;gap:7px;margin-bottom:7px;">
      <i class="ti ${icon}" style="font-size:13px;color:${color};flex-shrink:0;margin-top:9px;"></i>
      <div style="flex:1;">
        <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:5px;">${label}</div>
        <${type==='textarea'?'textarea class="di" rows="2"':'input class="di" type="'+type+'"'} id="pb${num}-${fid}" placeholder="${ph}" style="font-size:11px;${type==='textarea'?'resize:vertical;min-height:48px;':'width:100%;'}" ${extra}>${type==='textarea'?(val||'')+(val?'</textarea>':'</textarea>'):`value="${val||''}">`}
      </div>
     </div>`;

  const div=document.createElement('div'); div.className='pbc'; div.id=id;
  div.innerHTML=`
    <div class="pbh" style="align-items:flex-start;">
      <div style="flex:1;min-width:0;">
        <!-- VIEW 標題列 -->
        <div id="pb${num}-view" style="margin-bottom:10px;">
          <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px;">
            <div class="pbn" id="pb${num}-n">${data.name}</div>
            <span class="pbtag ${typeClass}" id="pb${num}-t">${typeLabel}</span>
            ${rrBadgeFull}
          </div>
          <!-- 觀察名單 -->
          <div id="pb${num}-stocks" class="pbstocks" style="margin-bottom:8px;">${(data.stocks||'').split(',').map(s=>s.trim()).filter(Boolean).map(s=>`<span class="pbstock">${s}</span>`).join('')}</div>
          <!-- 六段摘要 -->
          <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:8px 12px;">
            ${mkSection('ti-bolt','var(--amber)',   '① 事件題材',   data.catalyst)}
            ${mkSection('ti-chart-candle','var(--blue)','② 技術面訊號', data.techSignal)}
            ${mkSection('ti-dna','var(--purple)',   '③ 籌碼面訊號', data.chipSignal)}
            ${(data.triggerPrice||data.triggerVol)?mkSection('ti-radar','var(--up)','④ 觸發條件',`成交價 ≥ $${data.triggerPrice||'—'} ｜ 量能 ≥ ${data.triggerVol||'—'} 張 ｜ ${data.triggerLogic||'全部符合'}`):''}
            ${mkSection('ti-login','var(--up)',     '⑤ 進場方式',   data.entry)}
            ${(data.stopShort||data.stopWave)?mkSection('ti-shield','var(--dn)','⑤ 停損防線',`短線：${data.stopShort||'—'}　｜　波段：${data.stopWave||'—'}`):''}
            ${(data.profitTime||data.profitIndicator)?mkSection('ti-target','var(--dn)','⑤ 停利策略',`${data.profitTime||''}　${data.profitIndicator||''}`):''}
            ${mkSection('ti-wallet','var(--t2)',    '⑥ 資金管理',   [data.capital,data.positionSize].filter(Boolean).join('　|　'))}
          </div>
        </div>

        <!-- EDIT 模式 -->
        <div id="pb${num}-edit" style="display:none;">
          <!-- 劇本名稱 & 類型 -->
          <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap;">
            <input class="di" id="pb${num}-name-inp" style="font-size:13px;font-weight:600;flex:1;min-width:160px;" type="text" value="${data.name}" placeholder="劇本名稱" oninput="document.getElementById('pb${num}-n').textContent=this.value">
            <select class="di" style="width:auto;font-size:11px;cursor:pointer;" onchange="updPbType('pb${num}-t',this.value)">
              <option value="trend" ${data.type!=='rev'?'selected':''}>趨勢跟隨</option>
              <option value="rev"   ${data.type==='rev'?'selected':''}>逆勢反彈</option>
            </select>
          </div>
          <!-- 觀察股票 -->
          <div style="margin-bottom:10px;">
            <div style="font-size:9px;color:var(--t3);font-family:var(--mono);margin-bottom:4px;">觀察名單（逗號分隔）</div>
            <input class="di" id="pb${num}-stocks" type="text" value="${data.stocks||''}" placeholder="2359, 2330..." style="font-size:11px;">
          </div>

          <!-- ① 事件題材 -->
          <div style="background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--amber);font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-bolt"></i>① 事件題材 / 最強東風</div>
            <textarea class="di" id="pb${num}-catalyst" rows="3" placeholder="驅動事件（如：Meta 收購機器人 AI 新創 / NVIDIA GTC Taipei 6月）..." style="font-size:11px;resize:vertical;">${data.catalyst||''}</textarea>
          </div>

          <!-- ② 技術面 -->
          <div style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--blue);font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-chart-candle"></i>② 技術面訊號</div>
            <textarea class="di" id="pb${num}-tech" rows="3" placeholder="如：日K帶量突破均線糾結，KD黃金交叉，MACD柱狀體綠翻紅..." style="font-size:11px;resize:vertical;">${data.techSignal||''}</textarea>
          </div>

          <!-- ③ 籌碼面 -->
          <div style="background:var(--purple-d);border:1px solid var(--purple-b);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--purple);font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-dna"></i>③ 籌碼面訊號</div>
            <textarea class="di" id="pb${num}-chip" rows="2" placeholder="如：120-121元區間大量短沖客或主力成本，越過此區代表大戶吃貨..." style="font-size:11px;resize:vertical;">${data.chipSignal||''}</textarea>
          </div>

          <!-- ④ 觸發條件 -->
          <div style="background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--up);font-weight:600;margin-bottom:8px;display:flex;align-items:center;gap:5px;"><i class="ti ti-radar"></i>④ 觸發條件（智慧單）</div>
            <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:6px;">
              <div style="flex:1;min-width:100px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">成交價 ≥</div>
                <div style="display:flex;align-items:center;gap:4px;"><span style="font-size:11px;color:var(--t2);">$</span><input class="di" id="pb${num}-trigger-price" type="number" step="0.5" value="${data.triggerPrice||''}" placeholder="120.00" style="font-size:12px;font-weight:600;"></div>
              </div>
              <div style="flex:1;min-width:100px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">總量 ≥</div>
                <div style="display:flex;align-items:center;gap:4px;"><input class="di" id="pb${num}-trigger-vol" type="number" value="${data.triggerVol||''}" placeholder="800" style="font-size:12px;font-weight:600;"><span style="font-size:11px;color:var(--t2);">張</span></div>
              </div>
              <div style="flex:1;min-width:100px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">觸發邏輯</div>
                <select class="di" id="pb${num}-trigger-logic" style="font-size:11px;cursor:pointer;">
                  <option ${(data.triggerLogic||'全部符合')==='全部符合'?'selected':''}>全部符合</option>
                  <option ${data.triggerLogic==='任一符合'?'selected':''}>任一符合</option>
                </select>
              </div>
            </div>
            <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">進場說明</div>
            <input class="di" id="pb${num}-entry" type="text" value="${data.entry||''}" placeholder="如：市價或 120.5~121 元送出買單" style="font-size:11px;">
          </div>

          <!-- ⑤ 停損停利 -->
          <div style="background:var(--dn-d);border:1px solid var(--dn-b);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--dn);font-weight:600;margin-bottom:8px;display:flex;align-items:center;gap:5px;"><i class="ti ti-shield"></i>⑤ 停損防線</div>
            <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">短線防線（第一道）</div>
            <input class="di" id="pb${num}-stop-short" type="text" value="${data.stopShort||''}" placeholder="如：進場後當天/隔天收盤跌回 $119 以下 → 退場觀望" style="font-size:11px;margin-bottom:6px;">
            <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">波段底線（最終防線）</div>
            <input class="di" id="pb${num}-stop-wave" type="text" value="${data.stopWave||''}" placeholder="如：收盤帶量跌破5日線（約$114）→ 無條件停損出場" style="font-size:11px;margin-bottom:8px;">
            <!-- 進場價 + 停損 -->
            <div style="display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap;">
              <div style="flex:1;min-width:100px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">進場價（計算基準）</div>
                <div style="display:flex;align-items:center;gap:4px;"><span style="font-size:11px;color:var(--t2);">$</span><input class="di" id="pb${num}-entry-price" type="number" step="0.5" value="${data.entryPrice||''}" placeholder="120" style="font-size:13px;font-weight:700;color:var(--blue);" oninput="calcPbRR('${num}')"></div>
              </div>
              <div style="flex:1;min-width:100px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">停損價位</div>
                <div style="display:flex;align-items:center;gap:4px;"><span style="font-size:11px;color:var(--t2);">$</span><input class="di dnv" id="pb${num}-stop" type="number" step="0.5" value="${data.stopPrice||''}" placeholder="114" style="font-size:13px;font-weight:700;" oninput="calcPbRR('${num}')"></div>
              </div>
            </div>
            <!-- 第一目標 + 第二目標 -->
            <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:flex-start;">
              <div style="flex:1;min-width:120px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">🎯 第一目標價</div>
                <div style="display:flex;align-items:center;gap:4px;"><span style="font-size:11px;color:var(--t2);">$</span><input class="di upv" id="pb${num}-target" type="number" step="0.5" value="${data.targetPrice||''}" placeholder="130" style="font-size:13px;font-weight:700;" oninput="calcPbRR('${num}')"></div>
                <div id="pb${num}-rr1" style="font-size:11px;font-family:var(--mono);font-weight:700;color:var(--dn);margin-top:4px;min-height:16px;"></div>
              </div>
              <div style="flex:1;min-width:120px;">
                <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">🎯 第二目標價</div>
                <div style="display:flex;align-items:center;gap:4px;"><span style="font-size:11px;color:var(--t2);">$</span><input class="di upv" id="pb${num}-target2" type="number" step="0.5" value="${data.target2Price||''}" placeholder="140" style="font-size:13px;font-weight:700;" oninput="calcPbRR('${num}')"></div>
                <div id="pb${num}-rr2" style="font-size:11px;font-family:var(--mono);font-weight:700;color:var(--dn);margin-top:4px;min-height:16px;"></div>
              </div>
            </div>
          </div>

          <!-- 停利策略 -->
          <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--t2);font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-target" style="color:var(--dn)"></i>停利策略</div>
            <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">時間節點</div>
            <input class="di" id="pb${num}-profit-time" type="text" value="${data.profitTime||''}" placeholder="如：5月底/6月初 GTC 大會前後，買在預期賣在實現" style="font-size:11px;margin-bottom:6px;">
            <div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">指標條件（以下成立才走）</div>
            <textarea class="di" id="pb${num}-profit-indicator" rows="2" placeholder="如：MACD紅柱未縮，KD>80高檔鈍化，股價沿5日線向上 → 抱緊；高檔爆天量收黑K或利多全面見報 → 分批出清" style="font-size:11px;resize:vertical;">${data.profitIndicator||''}</textarea>
          </div>

          <!-- ⑥ 資金管理 -->
          <div style="background:var(--bg3);border:1px solid var(--border);border-radius:var(--r);padding:10px 12px;margin-bottom:8px;">
            <div style="font-size:10px;color:var(--t2);font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-wallet"></i>⑥ 資金管理</div>
            <div style="display:flex;gap:8px;">
              <div style="flex:1;"><div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">部位大小</div><input class="di" id="pb${num}-position-size" type="text" value="${data.positionSize||''}" placeholder="如：總資金 20%，不超過 3 張" style="font-size:11px;"></div>
              <div style="flex:1;"><div style="font-size:9px;color:var(--t3);margin-bottom:3px;font-family:var(--mono);">其他說明</div><input class="di" id="pb${num}-capital" type="text" value="${data.capital||''}" placeholder="如：突破失敗立刻出場，嚴守紀律" style="font-size:11px;"></div>
            </div>
          </div>
        </div>
      </div>

      <!-- 操作按鈕 -->
      <div style="display:flex;flex-direction:column;gap:5px;flex-shrink:0;margin-left:8px;">
        <button class="btn-g" style="font-size:10px;padding:4px 9px;" onclick="togglePbCard('${id}')"><i class="ti ti-edit" id="pb${num}-btn-icon"></i><span id="pb${num}-btn-txt">編輯</span></button>
        <button class="btn-g" style="font-size:10px;padding:4px 9px;background:var(--up-d);border-color:var(--up-b);color:var(--up);" onclick="savePlaybook('${id}')"><i class="ti ti-cloud-upload"></i>儲存</button>
        <button style="background:none;border:none;color:var(--t3);cursor:pointer;font-size:16px;padding:2px;" onclick="delPlaybookCloud('${id}')"><i class="ti ti-trash"></i></button>
      </div>
    </div>
    <button class="pb-exec" onclick="applyPlaybookToJournal('${id}')"><i class="ti ti-bolt"></i>執行此劇本 → 自動帶入日記</button>`;
  document.getElementById('pb-list').appendChild(div);
  // 若已有停損/目標則初始化風報比
  if(stop>0 && target>0){ setTimeout(()=>calcPbRR(num), 50); }
}

// ══ UI HELPERS ══
const wd=['日','一','二','三','四','五','六'];
const nd=new Date();
const de=document.getElementById('topbar-date');
if(de) de.textContent=`${nd.getFullYear()} / ${String(nd.getMonth()+1).padStart(2,'0')} / ${String(nd.getDate()).padStart(2,'0')} · 週${wd[nd.getDay()]}`;
const jd=document.getElementById('j-date');
if(jd) jd.textContent=de?.textContent||'';
// 當前日記日期（預設今天，點日曆可切換）
window._journalDate = new Date().toISOString().slice(0,10);

function showPage(id,el){
  document.querySelectorAll('.content').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n=>n.classList.remove('active'));
  const pg=document.getElementById('page-'+id); if(pg) pg.classList.add('active');
  if(el) el.classList.add('active');
  if(id==='stats'&&window.currentUser) updateStatsCards();
  if(id==='stock') saLoadKeys();
}

// ── 動態日曆（從 Firestore journals 讀取）──────────────
let curYear = new Date().getFullYear();
let curMonth = new Date().getMonth(); // 0-indexed

const MONTH_NAMES = ['一月','二月','三月','四月','五月','六月','七月','八月','九月','十月','十一月','十二月'];

function getDaysInMonth(y, m){ return new Date(y, m+1, 0).getDate(); }
function getFirstDow(y, m){ return new Date(y, m, 1).getDay(); }

function renderCal(y, m){
  curYear = y; curMonth = m;
  const label = `${y} 年 ${m+1} 月`;
  document.getElementById('cal-month-lbl').textContent = label;

  const grid = document.getElementById('cal-grid');
  grid.innerHTML = '';

  const days     = getDaysInMonth(y, m);
  const startDow = getFirstDow(y, m);
  const todayStr = new Date().toISOString().slice(0,10);
  const cache    = window._calCache || {};

  // 填空白格（月初前）
  for(let i = 0; i < startDow; i++) grid.innerHTML += '<div class="cday empty"></div>';

  for(let day = 1; day <= days; day++){
    const dateStr  = `${y}-${String(m+1).padStart(2,'0')}-${String(day).padStart(2,'0')}`;
    const isFuture = dateStr > todayStr;
    const entry    = cache[dateStr];
    const isToday  = dateStr === todayStr;

    if(isFuture){
      grid.innerHTML += `<div class="cday flat" style="opacity:.4;cursor:pointer;" onclick="selectCalDay(this,'${dateStr}')"><span class="dn" style="color:var(--t3)">${day}</span></div>`;
    } else if(entry && entry.pnl != null){
      // 有損益 → 紅/綠格
      const pnl  = entry.pnl;
      const isUp = pnl >= 0;
      const abs  = Math.abs(pnl);
      const disp = abs >= 1000 ? `${isUp?'+':'−'}${(abs/1000).toFixed(1)}k` : `${isUp?'+':'−'}${abs.toLocaleString()}`;
      const hasTags = Array.isArray(entry.trendTags) && entry.trendTags.length > 0;
      const dot = hasTags ? `<span style="width:4px;height:4px;border-radius:50%;background:var(--blue);display:block;margin:0 auto 1px;"></span>` : '';
      grid.innerHTML += `<div class="cday ${isUp?'profit':'loss'}" onclick="selectCalDay(this,'${dateStr}')"><span class="dn">${day}</span>${dot}<span class="pv">${disp}</span></div>`;
    } else if(entry){
      // 有日記但沒損益 → 藍色格子（明顯顯示）
      const hasTags = Array.isArray(entry.trendTags) && entry.trendTags.length > 0;
      const tagDots = hasTags
        ? entry.trendTags.slice(0,2).map(t=>{ const[bg,c]=getTrendTagColor(t); return `<span style="background:${c};width:4px;height:4px;border-radius:50%;display:inline-block;margin:0 1px;"></span>`; }).join('')
        : '<span style="width:4px;height:4px;border-radius:50%;background:var(--blue);display:inline-block;"></span>';
      grid.innerHTML += `<div class="cday" style="background:var(--blue-d);border:1px solid var(--blue-b);" onclick="selectCalDay(this,'${dateStr}')"><span class="dn" style="color:var(--blue);">${day}</span><span class="pv" style="display:flex;justify-content:center;gap:2px;margin-top:2px;">${tagDots}</span></div>`;
    } else {
      // 空白天
      const todayStyle = isToday ? ' style="border:2px solid var(--blue)"' : '';
      grid.innerHTML += `<div class="cday flat"${todayStyle} onclick="selectCalDay(this,'${dateStr}')"><span class="dn" ${isToday?'style="color:var(--blue);font-weight:700"':'style="color:var(--t3)"'}>${day}</span></div>`;
    }
  }

  updateSidebarStats();
}

// ══ 趨勢標籤系統 ══════════════════════════════════════════
const TREND_TAG_COLORS = {
  '多頭':['#fdf0ef','#c0392b'],'空頭':['#eaf5f0','#1a7a4a'],'盤整':['#fef3e2','#b45309'],
  '強勢':['#fdf0ef','#c0392b'],'弱勢':['#eaf5f0','#1a7a4a'],'外資買超':['#e8f0fc','#1a5fd4'],
  '外資賣超':['#fdf0ef','#c0392b'],'投信認養':['#f3effe','#6b3fa0'],
};
function getTrendTagColor(tag){ return TREND_TAG_COLORS[tag]||['#f5f2ed','#7a7268']; }

function toggleTrendTag(btn){
  btn.classList.toggle('active');
  const activeTags=[...document.querySelectorAll('#trend-tags-row .chip.active')].map(b=>b.dataset.tag||b.textContent.trim());
  updateTrendTagsDisplay(activeTags);
}
function updateTrendTagsDisplay(tags){
  const el=document.getElementById('j-trend-tags-display'); if(!el) return;
  el.innerHTML=tags.map(t=>{const[bg,c]=getTrendTagColor(t);return`<span style="background:${bg};color:${c};border-radius:20px;padding:2px 8px;font-size:10px;font-weight:500;">${t}</span>`;}).join('');
}
function addTrendTagChip(tag,active=false){
  const row=document.getElementById('trend-tags-row'); if(!row) return;
  if([...row.querySelectorAll('.chip')].some(b=>(b.dataset.tag||b.textContent.trim())===tag)) return;
  const btn=document.createElement('button'); btn.className='chip'+(active?' active':''); btn.dataset.tag=tag;
  btn.onclick=()=>toggleTrendTag(btn);
  btn.innerHTML=`${tag} <span onclick="event.stopPropagation();removeCustomTrendTag('${tag}')" style="margin-left:3px;opacity:.5;font-size:9px;cursor:pointer;">×</span>`;
  row.appendChild(btn);
  if(active) updateTrendTagsDisplay([...document.querySelectorAll('#trend-tags-row .chip.active')].map(b=>b.dataset.tag||b.textContent.trim()));
}
function removeCustomTrendTag(tag){
  document.getElementById('trend-tags-row')?.querySelectorAll('.chip').forEach(btn=>{ if((btn.dataset.tag||btn.textContent.trim()).startsWith(tag)) btn.remove(); });
  const custom=getCustomTrendTags().filter(t=>t!==tag); localStorage.setItem('tradelog_trend_tags',JSON.stringify(custom));
}
function getCustomTrendTags(){ try{return JSON.parse(localStorage.getItem('tradelog_trend_tags')||'[]');}catch(e){return[];} }
function openTrendTagModal(){
  document.getElementById('trend-tag-modal')?.remove();
  const m=document.createElement('div'); m.id='trend-tag-modal';
  m.style.cssText='position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:9000;display:flex;align-items:center;justify-content:center;';
  m.innerHTML=`<div style="background:#fff;border-radius:14px;padding:24px;width:320px;box-shadow:0 8px 40px rgba(0,0,0,.2);">
    <div style="font-size:14px;font-weight:700;margin-bottom:4px;">新增趨勢標籤</div>
    <div style="font-size:11px;color:var(--t3);margin-bottom:12px;">標籤會顯示在日記標題旁和日曆格子上</div>
    <input id="trend-tag-input" class="di" type="text" placeholder="如：AI族群、電池鏈..." style="margin-bottom:12px;" autofocus>
    <div style="display:flex;gap:8px;"><button onclick="document.getElementById('trend-tag-modal').remove()" class="s-prev" style="flex:1;padding:9px;">取消</button><button onclick="confirmAddTrendTag()" class="s-next" style="flex:2;padding:9px;">新增</button></div>
  </div>`;
  document.body.appendChild(m);
  m.addEventListener('click',e=>{if(e.target===m)m.remove();});
  document.getElementById('trend-tag-input').focus();
  document.getElementById('trend-tag-input').addEventListener('keydown',e=>{if(e.key==='Enter')confirmAddTrendTag();});
}
function confirmAddTrendTag(){
  const val=document.getElementById('trend-tag-input')?.value?.trim(); if(!val) return;
  const custom=getCustomTrendTags(); if(!custom.includes(val)) custom.push(val);
  localStorage.setItem('tradelog_trend_tags',JSON.stringify(custom));
  addTrendTagChip(val,true); document.getElementById('trend-tag-modal')?.remove();
  showToast(`標籤「${val}」已新增`);
}
function initTrendTags(){ getCustomTrendTags().forEach(t=>addTrendTagChip(t,false)); }

// ══ 統一日曆格點擊處理
function selectCalDay(el, dateStr){
  document.querySelectorAll('#cal-grid .cday').forEach(c=>c.classList.remove('selected'));
  el.classList.add('selected');
  const entry = (window._calCache||{})[dateStr];
  if(entry && entry.pnl != null){
    renderDayDetail(dateStr, entry);
  } else {
    renderDayDetailEmpty(dateStr, entry);
  }
}

function calNav(dir){
  let m = curMonth + dir, y = curYear;
  if(m > 11){ m = 0; y++; }
  if(m < 0){ m = 11; y--; }
  renderCal(y, m);
}

// 有損益的日期詳情
function selDay(el, dateStr, pnl){ selectCalDay(el, dateStr); }
function selDayNote(el, dateStr){ selectCalDay(el, dateStr); }

function renderDayDetail(dateStr, entry){
  const pnlNum = entry.pnl || 0;
  const isUp   = pnlNum >= 0;
  const trades = Array.isArray(entry.trades) ? entry.trades : [];
  const plans  = Array.isArray(entry.plans)  ? entry.plans  : [];
  const tags   = Array.isArray(entry.tags)   ? entry.tags   : [];
  const invSnap= Array.isArray(entry.invSnapshot) ? entry.invSnapshot : [];

  document.getElementById('detail-title').textContent = dateStr + ' 日記';

  // 趨勢標籤 → 寫入 #detail-tags（日期正下方）
  const trendTags = Array.isArray(entry.trendTags) ? entry.trendTags : [];
  const detailTagsEl = document.getElementById('detail-tags');
  if(detailTagsEl){
    detailTagsEl.innerHTML = trendTags.map(t=>{
      const[bg,col]=getTrendTagColor(t);
      return`<span style="background:${bg};color:${col};border-radius:20px;padding:3px 10px;font-size:11px;font-weight:500;">${t}</span>`;
    }).join('');
  }
  const trendTagHtml = ''; // 不再放在 body 裡

  // ① 每日收益
  const pnlHTML = `<div style="display:flex;align-items:baseline;gap:8px;padding:8px 0 10px;border-bottom:1px solid var(--border);">
    <div style="font-size:32px;font-weight:800;color:${isUp?'var(--up)':'var(--dn)'};font-family:var(--mono);">${isUp?'+':''}${pnlNum.toLocaleString()}</div>
    <div style="font-size:12px;color:var(--t2);font-family:var(--mono);">已實現損益</div>
  </div>`;

  // ② 趨勢觀察（簡要）
  const trendHTML = entry.trend ? `<div style="margin-top:10px;">
    <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:6px;">📊 趨勢觀察</div>
    <div style="font-size:11px;color:var(--t1);line-height:1.6;background:var(--bg3);border-radius:7px;padding:7px 10px;">${entry.trend.length>120?entry.trend.slice(0,120)+'…':entry.trend}</div>
  </div>` : '';

  // ③ 進出場明細（含成本）
  const tradeHTML = trades.length > 0 ? `<div style="margin-top:10px;">
    <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:6px;">📋 進出場 & 成本</div>
    ${trades.map(t => {
      const isBuy = t.actionClass==='buy'||t.actionClass==='add';
      const col   = isBuy ? 'var(--up)' : 'var(--dn)';
      const realPnl = t.realizedPnl != null ? `<span style="font-family:var(--mono);font-weight:700;font-size:11px;color:${t.realizedPnl>=0?'var(--up)':'var(--dn)'};">${t.realizedPnl>=0?'+':''}${Math.round(t.realizedPnl).toLocaleString()}</span>` : '';
      const costInfo = isBuy && t.price ? `<span style="font-size:9px;color:var(--t3);font-family:var(--mono);">成本/股 $${(t.price*(1+0.001425*0.3)).toFixed(2)}</span>` : '';
      return `<div style="display:flex;align-items:center;gap:6px;padding:4px 0;border-bottom:0.5px solid var(--border);">
        <span style="background:${isBuy?'var(--up-d)':'var(--dn-d)'};color:${col};border-radius:4px;padding:1px 6px;font-size:10px;font-weight:700;">${isBuy?'買':'賣'}</span>
        <span style="font-weight:600;font-size:12px;color:var(--t0);">${t.stockId||'—'}</span>
        <span style="font-size:11px;color:var(--t2);">$${t.price}×${t.shares}股</span>
        <span style="margin-left:auto;display:flex;flex-direction:column;align-items:flex-end;gap:1px;">${realPnl}${costInfo}</span>
      </div>`;
    }).join('')}
  </div>` : '';

  // ④ 庫存快照（含損益）
  const invHTML = invSnap.length > 0 ? `<div style="margin-top:12px;border-top:1px solid var(--border);padding-top:10px;">
    <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:8px;">📦 當日庫存</div>
    <div style="display:grid;grid-template-columns:1fr 60px 80px 80px;gap:4px;margin-bottom:4px;padding-bottom:4px;border-bottom:1px solid var(--border);">
      <span style="font-size:10px;color:var(--t3);font-family:var(--mono);">股票</span>
      <span style="font-size:10px;color:var(--t3);font-family:var(--mono);text-align:right;">股數</span>
      <span style="font-size:10px;color:var(--t3);font-family:var(--mono);text-align:right;">成本/股</span>
      <span style="font-size:10px;color:var(--t3);font-family:var(--mono);text-align:right;">損益</span>
    </div>
    ${invSnap.map(i => {
      const p = ((i.price||0)-(i.cost||0))*(i.shares||0);
      const isP = p >= 0;
      return `<div style="display:grid;grid-template-columns:1fr 60px 80px 80px;gap:4px;padding:5px 0;border-bottom:0.5px solid rgba(255,255,255,.05);align-items:center;">
        <span style="font-size:13px;font-weight:600;color:var(--t0);">${i.name}</span>
        <span style="font-size:12px;color:var(--t1);font-family:var(--mono);text-align:right;">${i.shares}</span>
        <span style="font-size:12px;color:var(--gold);font-family:var(--mono);text-align:right;">$${(i.cost||0).toFixed(1)}</span>
        <span style="font-size:13px;font-family:var(--mono);font-weight:700;color:${isP?'var(--up)':'var(--dn)'};text-align:right;">${isP?'+':''}${Math.round(p).toLocaleString()}</span>
      </div>`;
    }).join('')}
  </div>` : '';

  // ⑤ 心理檢討
  const posTags = ['按計畫執行','嚴守紀律','理性分析','自律達成'];
  const posT = tags.filter(t=>posTags.some(k=>t.includes(k)));
  const negT = tags.filter(t=>!posTags.some(k=>t.includes(k)));
  const tagHTML = tags.length > 0 ? `<div style="margin-top:10px;">
    <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:6px;">🧠 心理檢討</div>
    <div style="display:flex;flex-wrap:wrap;gap:4px;margin-bottom:${entry.notes?'4px':'0'};">
      ${tags.map(t=>{ const ip=posTags.some(k=>t.includes(k)); return `<span style="background:${ip?'var(--up-d)':'var(--dn-d)'};color:${ip?'var(--up)':'var(--dn)'};border-radius:20px;padding:2px 8px;font-size:10px;">${t}</span>`; }).join('')}
    </div>
    ${entry.notes?`<div style="font-size:13px;color:var(--t1);line-height:1.6;background:var(--bg3);border-radius:9px;padding:9px 12px;margin-top:4px;">${entry.notes.length>100?entry.notes.slice(0,100)+'…':entry.notes}</div>`:''}
  </div>` : (entry.notes ? `<div style="margin-top:10px;"><div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:6px;">🧠 心理筆記</div><div style="font-size:13px;color:var(--t1);line-height:1.6;background:var(--bg3);border-radius:9px;padding:9px 12px;">${entry.notes.length>100?entry.notes.slice(0,100)+'…':entry.notes}</div></div>` : '');

  // ⑥ IF→THEN 明日劇本（依股票分組）
  const planByStock = {};
  plans.forEach(p => { const k=p.stock||'全部'; if(!planByStock[k]) planByStock[k]=[]; planByStock[k].push(p); });
  const planHTML = plans.length > 0 ? `<div style="margin-top:10px;">
    <div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:6px;">🎯 明日IF→THEN劇本</div>
    ${Object.entries(planByStock).map(([stock,ps])=>`
      ${stock!=='全部'?`<div style="font-size:9px;background:var(--blue-d);color:var(--blue);border-radius:4px;padding:1px 7px;display:inline-block;margin-bottom:3px;font-family:var(--mono);">📌 ${stock}</div>`:''}
      ${ps.map(p=>`<div style="font-size:13px;color:var(--t0);padding:3px 0;display:flex;gap:6px;align-items:flex-start;">
        <span style="color:var(--blue);font-weight:700;font-size:9px;font-family:var(--mono);margin-top:1px;flex-shrink:0;">IF</span>
        <span style="flex:1;">${p.if} <span style="color:var(--up);font-family:var(--mono);">→</span> ${p.then}</span>
        <span>${p.done?'✅':'○'}</span>
      </div>`).join('')}
    `).join('')}
  </div>` : '';

  document.getElementById('detail-body').innerHTML =
    trendTagHtml + pnlHTML + trendHTML + tradeHTML + invHTML + tagHTML + planHTML +
    `<div style="margin-top:14px;display:flex;gap:6px;">
      <button class="btn-g" style="flex:2;justify-content:center;" onclick="openJournalDate('${dateStr}')"><i class="ti ti-edit"></i>編輯此日日記</button>
      <button class="btn-g" style="flex:1;justify-content:center;background:var(--dn-d);border-color:var(--dn-b);color:var(--dn);" onclick="confirmDeleteJournal('${dateStr}')"><i class="ti ti-trash"></i>刪除</button>
    </div>`;
}

function renderDayDetailEmpty(dateStr, entry){
  const trades = Array.isArray(entry?.trades) ? entry.trades : [];
  const plans  = Array.isArray(entry?.plans)  ? entry.plans  : [];
  const tags   = Array.isArray(entry?.tags)   ? entry.tags   : [];
  const todayStr = new Date().toISOString().slice(0,10);
  const isFuture = dateStr > todayStr;
  const isToday  = dateStr === todayStr;

  document.getElementById('detail-title').textContent = dateStr + ' 日記';
  // 清空標籤列
  const dte = document.getElementById('detail-tags');
  if(dte){
    const etags = Array.isArray(entry?.trendTags) ? entry.trendTags : [];
    dte.innerHTML = etags.map(t=>{
      const[bg,col]=getTrendTagColor(t);
      return`<span style="background:${bg};color:${col};border-radius:20px;padding:3px 10px;font-size:11px;font-weight:500;">${t}</span>`;
    }).join('');
  }

  let statusBadge = '';
  if(isFuture){
    statusBadge = `<div style="text-align:center;padding:12px 0;"><span style="background:var(--bg3);color:var(--t3);border-radius:20px;padding:4px 14px;font-size:11px;">尚未到來的交易日</span></div>`;
  } else if(entry){
    statusBadge = `<div style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:8px;padding:8px 12px;font-size:11px;color:var(--blue);margin-bottom:8px;">● 有日記記錄，損益未填入</div>`;
  } else {
    statusBadge = `<div style="background:var(--bg3);border:1px solid var(--border);border-radius:8px;padding:8px 12px;font-size:13px;color:var(--t2);margin-bottom:10px;">○ 尚無日記記錄</div>`;
  }

  const trendHTML = entry?.trend ? `<div style="margin-top:8px;"><div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:5px;">📊 趨勢觀察</div><div style="font-size:11px;color:var(--t1);line-height:1.6;background:var(--bg3);border-radius:7px;padding:7px 10px;">${entry.trend.length>120?entry.trend.slice(0,120)+'…':entry.trend}</div></div>` : '';

  const tradeHint = trades.length > 0 ? `<div style="margin-top:8px;font-size:11px;color:var(--t2);">交易：${trades.map(t=>`<span style="color:${t.actionClass==='buy'||t.actionClass==='add'?'var(--up)':'var(--dn)'};">${t.stockId||'—'}</span>`).join('、')}</div>` : '';

  const tagHTML = tags.length > 0 ? `<div style="margin-top:8px;display:flex;flex-wrap:wrap;gap:4px;">${tags.map(t=>{ const ip=['按計畫執行','嚴守紀律','理性分析','自律達成'].some(k=>t.includes(k)); return `<span style="background:${ip?'var(--up-d)':'var(--dn-d)'};color:${ip?'var(--up)':'var(--dn)'};border-radius:20px;padding:2px 8px;font-size:10px;">${t}</span>`; }).join('')}</div>` : '';

  const planHTML = plans.length > 0 ? `<div style="margin-top:8px;"><div style="font-size:12px;color:var(--t1);font-family:var(--mono);font-weight:600;margin-bottom:5px;">🎯 明日劇本</div>${plans.map(p=>`<div style="font-size:11px;color:var(--t1);padding:1px 0;display:flex;gap:4px;"><span style="color:var(--blue);font-family:var(--mono);font-size:9px;font-weight:700;">IF</span><span>${p.if} → ${p.then}</span><span>${p.done?'✅':'○'}</span></div>`).join('')}</div>` : '';

  const hintText = !entry ? `<div style="font-size:13px;color:var(--t2);margin-top:10px;text-align:center;">點擊下方按鈕開始記錄這天的日記</div>` : '';

  document.getElementById('detail-body').innerHTML =
    statusBadge + trendHTML + tradeHint + tagHTML + planHTML + hintText +
    `<div style="margin-top:12px;">
      <button class="btn-g" style="width:100%;justify-content:center;" onclick="openJournalDate('${dateStr}')">
        <i class="ti ti-${entry?'edit':'plus'}"></i>${entry?'編輯此日日記':'新增此日日記'}
      </button>
    </div>`;
}



// ── 日曆資料載入（與 renderCal 同 scope，確保 curYear/curMonth 可存取）──
async function loadCalendarData(user){
  const u = user || window.currentUser;
  if(!u) return;
  if(window._calListener){ window._calListener(); window._calListener = null; }
  return new Promise((resolve) => {
    let first = true;
    window._calListener = db.collection('users').doc(u.uid)
      .collection('journals')
      .onSnapshot(snapshot => {
        if(!window._calCache) window._calCache = {};
        snapshot.docChanges().forEach(change => {
          if(change.type === 'removed'){
            delete window._calCache[change.doc.id];
          } else {
            window._calCache[change.doc.id] = { ...change.doc.data(), date: change.doc.id };
          }
        });
        // renderCal 在同一 scope，curYear/curMonth 直接可用
        renderCal(curYear, curMonth);
        updateSidebarStats();
        if(first){ first = false; resolve(); }
      }, err => { console.error('snapshot err', err); resolve(); });
  });
}

window._initCalendar = async function(){
  await loadCalendarData();
};

window._renderCal = renderCal;

// ── 登入後初始化日曆（在 Block 6 執行，與 renderCal/curYear 同 scope）──
auth.onAuthStateChanged(async user => {
  if(!user) return;
  await loadCalendarData(user);
  // _calCache 現在有資料，可以正確計算統計
  if(typeof updateStatsCards === 'function') await updateStatsCards();
});
// 點日曆開啟特定日期的日記（可讀歷史/編輯今天）
function openJournalDate(dateStr){
  window._journalDate = dateStr;
  showPage('journal', document.getElementById('nav-journal'));
  gotoStep(0);
  clearJournalForm();
  loadJournals(dateStr);
  const jDateEl = document.getElementById('j-date');
  if(jDateEl) jDateEl.textContent = dateStr;
  const today = new Date().toISOString().slice(0,10);
  const titleEl = document.querySelector('#page-journal [style*="font-size:16px"]');
  if(titleEl) titleEl.textContent = dateStr === today ? '今日戰情日記' : dateStr + ' 日記';
}

// ── 刪除日記 ─────────────────────────────────────────────
function confirmDeleteJournal(dateStr){
  // 彈出確認 modal
  const m = document.createElement('div');
  m.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;';
  m.innerHTML = `<div style="background:#fff;border-radius:14px;padding:28px 24px;width:300px;box-shadow:0 8px 40px rgba(0,0,0,.2);text-align:center;">
    <div style="font-size:36px;margin-bottom:10px;">🗑️</div>
    <div style="font-size:15px;font-weight:700;margin-bottom:6px;">刪除 ${dateStr} 日記？</div>
    <div style="font-size:12px;color:var(--t3);margin-bottom:20px;">此操作無法復原，日記內容將永久刪除。</div>
    <div style="display:flex;gap:8px;">
      <button onclick="this.closest('div[style*=fixed]').remove()" class="s-prev" style="flex:1;padding:10px;font-size:13px;">取消</button>
      <button onclick="deleteJournal('${dateStr}');this.closest('div[style*=fixed]').remove()" style="flex:1;padding:10px;font-size:13px;background:var(--dn);color:#fff;border:none;border-radius:8px;cursor:pointer;font-weight:600;">確認刪除</button>
    </div>
  </div>`;
  document.body.appendChild(m);
  m.addEventListener('click', e => { if(e.target === m) m.remove(); });
}

async function deleteJournal(dateStr){
  try{
    await userDoc('journals', dateStr).delete();
    // 從快取移除
    if(window._calCache) delete window._calCache[dateStr];
    // 重新渲染日曆
    renderCal(curYear, curMonth);
    // 清空右側詳情
    document.getElementById('detail-title').textContent = '點擊日期查看明細';
    document.getElementById('detail-tags').innerHTML = '';
    document.getElementById('detail-body').innerHTML = '<div style="color:var(--t2);padding:20px 0;text-align:center;">← 點擊左側日曆的日期</div>';
    showToast('日記已刪除');
  } catch(e){
    showToast('刪除失敗：' + e.message);
  }
}

// 清空日記表單（切換日期前先清）
function clearJournalForm(){
  const tas = document.querySelectorAll('#sp0 textarea');
  if(tas[0]) tas[0].value=''; if(tas[1]) tas[1].value='';
  const jn=document.getElementById('j-notes'); if(jn) jn.value='';
  const js=document.getElementById('j-summary'); if(js) js.value='';
  const inp=document.getElementById('j-pnl'); if(inp) inp.value='';
  const btn=document.getElementById('pnl-sign-btn');
  if(btn){ btn.textContent='+'; btn.style.background='var(--up-d)'; btn.style.borderColor='var(--up-b)'; btn.style.color='var(--up)'; }
  const prev=document.getElementById('j-pnl-preview'); if(prev){ prev.textContent='—'; prev.style.color='var(--t3)'; }
  // 清空交易卡（保留第一張）
  const cards=document.querySelectorAll('#trade-cards .trade-card');
  cards.forEach((card,i)=>{ if(i>0) card.remove(); });
  // 清空第一張交易卡的輸入
  const firstCard=document.getElementById('tc-1');
  if(firstCard){ firstCard.querySelectorAll('input[type=number]').forEach(i=>i.value=''); firstCard.querySelectorAll('textarea').forEach(t=>t.value=''); firstCard.querySelectorAll('.achip').forEach(a=>{ a.classList.remove('on'); }); firstCard.querySelector('.achip.buy')?.classList.add('on'); }
  // 清空 IF→THEN
  const ifthen=document.getElementById('ifthen-rows'); if(ifthen) ifthen.innerHTML='';
  addIfthen(); // 加一個空白行
  tcN=1;
}


// Stepper
let curStep=1;
const SN=5;
function gotoStep(s){
  curStep=s;
  for(let i=0;i<SN;i++){
    const st=document.getElementById('st'+i),sc=document.getElementById('sc'+i),sb=document.getElementById('sb'+i),sp=document.getElementById('sp'+i);
    if(st){st.className='step';if(i<s)st.classList.add('done');else if(i===s)st.classList.add('curr');}
    if(sc) sc.innerHTML=i<s?'<i class="ti ti-check" style="font-size:14px"></i>':(i+1);
    if(sb){sb.className='badge';sb.classList.add(i<s?'bdone':i===s?'bcurr':'btodo');}
    if(sp) sp.classList.toggle('active',i===s);
  }
  const pg=document.getElementById('page-journal'); if(pg) pg.scrollTop=0;

  // Step 2：自動載入庫存 + 顯示今日交易摘要
  if(s === 2){
    renderStep2Inventory();
    renderStep2TradeSummary();
    setTimeout(refreshIfthenStockSelects, 300); // 等庫存載入後刷新
  }
  // Step 3：自動從賣出卡計算損益
  if(s === 3){
    autoCalcSellPnl();
  }
}

// ── Step2：自動載入庫存到日記中 ────────────────────────
async function renderStep2Inventory(){
  const container = document.getElementById('inv-rows-j');
  const timeEl    = document.getElementById('sp2-inv-time');
  if(!container) return;
  container.innerHTML = '<div style="color:var(--t3);font-size:11px;padding:6px 0;">載入中...</div>';

  // 讀 Firestore 庫存
  const snap = await userDoc('data','inventory').get();
  const items = snap.exists ? (snap.data().items || []) : [];

  if(timeEl){
    const upd = snap.exists ? snap.data().updatedAt?.toDate?.()?.toLocaleString('zh-TW') || '' : '';
    timeEl.textContent = upd ? `最後更新 ${upd}` : '尚無庫存';
  }

  if(items.length === 0){
    container.innerHTML = '<div style="color:var(--t3);font-size:11px;padding:8px 0;">目前無持倉</div>';
    renderYesterdayPlansInStep2();
    return;
  }

  // 建立庫存表（顯示用，不可編輯）
  container.innerHTML = `
    <div style="display:grid;grid-template-columns:1.4fr 0.7fr 0.7fr 0.7fr 0.7fr 0.7fr;gap:6px;padding:4px 2px 6px;border-bottom:1px solid var(--border);margin-bottom:2px;">
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);">股票</div>
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);text-align:right">持股數</div>
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);text-align:right">成本</div>
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);text-align:right">現價</div>
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);text-align:right">損益</div>
      <div style="font-size:9px;color:var(--t3);font-family:var(--mono);text-align:right">損益%</div>
    </div>` +
    items.map(item => {
      const pnl = ((item.price||0) - (item.cost||0)) * (item.shares||0);
      const pct = item.cost > 0 ? ((item.price - item.cost) / item.cost * 100) : 0;
      const isUp = pnl >= 0;
      return `<div style="display:grid;grid-template-columns:1.4fr 0.7fr 0.7fr 0.7fr 0.7fr 0.7fr;gap:6px;align-items:center;padding:5px 2px;border-bottom:1px solid var(--border);">
        <div style="font-size:12px;font-weight:500;color:var(--t0);">${item.name}</div>
        <div style="font-size:11px;font-family:var(--mono);text-align:right;color:var(--t1);">${(item.shares||0).toLocaleString()}</div>
        <div style="font-size:11px;font-family:var(--mono);text-align:right;color:var(--up);">$${(item.cost||0).toFixed(1)}</div>
        <div style="font-size:11px;font-family:var(--mono);text-align:right;color:var(--t1);">$${(item.price||0).toFixed(1)}</div>
        <div style="font-size:11px;font-family:var(--mono);text-align:right;font-weight:700;color:${isUp?'var(--up)':'var(--dn)'};">${isUp?'+':''}${Math.round(pnl).toLocaleString()}</div>
        <div style="font-size:11px;font-family:var(--mono);text-align:right;font-weight:700;color:${isUp?'var(--up)':'var(--dn)'};">${isUp?'+':''}${pct.toFixed(1)}%</div>
      </div>`;
    }).join('');
  // 渲染昨日劇本
  renderYesterdayPlansInStep2();
}

// ── Step2：顯示今日進出場摘要 ───────────────────────────
function renderStep2TradeSummary(){
  const trades = collectTradesFromCards();
  const summaryEl = document.getElementById('sp2-trade-summary');
  const listEl    = document.getElementById('sp2-trade-list');
  if(!summaryEl || !listEl) return;

  if(trades.length === 0){
    summaryEl.style.display = 'none';
    return;
  }
  summaryEl.style.display = 'block';
  listEl.innerHTML = trades.map(t => {
    const isBuy  = t.actionClass==='buy' || t.actionClass==='add';
    const color  = isBuy ? 'var(--up)' : 'var(--dn)';
    const badge  = isBuy ? '▲ 買進' : '▼ 賣出';
    const cost   = t.price * t.shares;
    return `<div style="display:flex;align-items:center;gap:8px;padding:3px 0;">
      <span style="color:${color};font-weight:700;font-size:11px;font-family:var(--mono);min-width:44px;">${badge}</span>
      <span style="font-weight:600;color:var(--t0);">${t.stockId||'—'}</span>
      <span style="color:var(--t2);font-size:11px;">$${t.price} × ${t.shares}股</span>
      <span style="margin-left:auto;font-family:var(--mono);font-size:11px;color:var(--t2);">≈ $${Math.round(cost).toLocaleString()}</span>
      ${t.realizedPnl != null ? `<span style="font-family:var(--mono);font-size:11px;font-weight:700;color:${t.realizedPnl>=0?'var(--up)':'var(--dn)'};">${t.realizedPnl>=0?'+':''}${Math.round(t.realizedPnl).toLocaleString()}</span>` : ''}
    </div>`;
  }).join('');
}

// ── 從交易卡收集所有交易（含已實現損益計算）─────────────
function collectTradesFromCards(){
  const trades = [];
  // 先讀庫存成本（同步方式，用已載入的 DOM）
  const invItems = [];
  document.querySelectorAll('#inv-rows .inv-row').forEach(row => {
    const inputs = row.querySelectorAll('input');
    const name   = inputs[0]?.value?.trim();
    const shares = parseFloat(inputs[1]?.value) || 0;
    const cost   = parseFloat(inputs[2]?.value) || 0;
    if(name) invItems.push({ name, shares, cost });
  });

  document.querySelectorAll('#trade-cards .trade-card').forEach(card => {
    const dirBtn      = card.querySelector('.dir-b.on');
    const dir         = dirBtn?.classList.contains('long') ? 'long' : 'short';
    const actBtn      = card.querySelector('.achip.on');
    const action      = actBtn ? actBtn.textContent.replace(/\s+/g,' ').trim() : '';
    const actionClass = actBtn?.classList.contains('buy')  ? 'buy'
                      : actBtn?.classList.contains('sell') ? 'sell'
                      : actBtn?.classList.contains('add')  ? 'add'
                      : actBtn?.classList.contains('cut')  ? 'cut' : '';
    const stockInput  = card.querySelector('input[type=text]');
    const stockId     = stockInput?.value?.trim() || '';
    const numInputs   = card.querySelectorAll('input[type=number]');
    const price       = parseFloat(numInputs[0]?.value) || 0;
    const shares      = parseFloat(numInputs[1]?.value) || 0;
    const stop        = parseFloat(numInputs[2]?.value) || 0;
    const target      = parseFloat(numInputs[3]?.value) || 0;
    const logic       = card.querySelector('textarea')?.value || '';

    // 賣出時計算已實現損益
    let realizedPnl = null;
    if((actionClass === 'sell' || actionClass === 'cut') && stockId && price && shares){
      const match = invItems.find(i =>
        i.name.replace(/\s/g,'') === stockId.replace(/\s/g,'') ||
        i.name.includes(stockId) || stockId.includes(i.name)
      );
      if(match && match.cost > 0){
        // 賣出實收（扣手續費3折+證交稅）- 買入真實成本（含手續費）
        const netPerShare = calcSellNet(price, shares);
        realizedPnl = (netPerShare - match.cost) * shares;
      }
    }

    if(stockId || price || shares){
      trades.push({ dir, action, actionClass, stockId, price, shares, stop, target, logic, realizedPnl });
    }
  });
  return trades;
}

// ── Step3：自動計算賣出損益並填入損益欄 ─────────────────
function autoCalcSellPnl(){
  const trades = collectTradesFromCards();
  const sells  = trades.filter(t => t.realizedPnl != null);
  if(sells.length === 0) return;

  const totalPnl = sells.reduce((sum, t) => sum + t.realizedPnl, 0);
  const pnlInp   = document.getElementById('j-pnl');
  const pnlBtn   = document.getElementById('pnl-sign-btn');
  if(!pnlInp || !pnlBtn) return;

  // 只有損益欄是空的才自動填（不覆蓋使用者已填的）
  if(!pnlInp.value){
    pnlInp.value = Math.abs(Math.round(totalPnl));
    pnlBtn.textContent  = totalPnl >= 0 ? '+' : '−';
    pnlBtn.style.background  = totalPnl >= 0 ? 'var(--up-d)' : 'var(--dn-d)';
    pnlBtn.style.borderColor = totalPnl >= 0 ? 'var(--up-b)' : 'var(--dn-b)';
    pnlBtn.style.color       = totalPnl >= 0 ? 'var(--up)'   : 'var(--dn)';
    updatePnlPreview();
    // 提示
    const hint = document.createElement('div');
    hint.style.cssText = 'font-size:10px;color:var(--blue);margin-top:4px;';
    hint.textContent = `✦ 已從賣出交易自動計算損益（${sells.length} 筆）`;
    const pnlBox = pnlInp.closest('.fg') || pnlInp.parentElement;
    const old = pnlBox?.querySelector('.auto-pnl-hint');
    if(old) old.remove();
    hint.className = 'auto-pnl-hint';
    pnlBox?.appendChild(hint);
  }
}

// ── 交易卡手續費試算提示 ──────────────────────────────────
function calcTradeFee(cardId){
  const card = document.getElementById(cardId);
  if(!card) return;
  const numInputs = card.querySelectorAll('input[type=number]');
  const price  = parseFloat(numInputs[0]?.value) || 0;
  const shares = parseFloat(numInputs[1]?.value) || 0;
  const hintEl = document.getElementById(cardId.replace('-','')+'-fee-hint') ||
                 document.getElementById(cardId + '-fee-hint') ||
                 card.querySelector('[id$="-fee-hint"]');
  if(!hintEl || !price || !shares){ if(hintEl) hintEl.style.display='none'; return; }

  const isBuy = card.querySelector('.achip.buy.on') || !card.querySelector('.achip.sell.on');
  const fee   = Math.max(Math.round(price * shares * FEE_RATE), 1);
  const tax   = isBuy ? 0 : Math.round(price * shares * TAX_RATE);
  const total = price * shares;
  const trueCost = isBuy
    ? calcBuyCost(price, shares)
    : calcSellNet(price, shares);

  hintEl.style.display = 'block';
  if(isBuy){
    hintEl.innerHTML = `買入 ${shares}股 × $${price} = $${total.toLocaleString()} ｜ 手續費(3折) $${fee} ｜ <b>真實成本每股 $${trueCost}</b>`;
  } else {
    hintEl.innerHTML = `賣出 ${shares}股 × $${price} = $${total.toLocaleString()} ｜ 手續費 $${fee} ｜ 證交稅 $${tax} ｜ <b>實收每股 $${trueCost}</b>`;
  }
}

// Emotion
const eL=['極度焦慮','有點焦慮','平靜','過度自信','衝動'];
const eC=['#c0392b','#b45309','#1a5fd4','#b45309','#c0392b'];
function updEmo(v){const e=document.getElementById('emo-lbl');if(e){e.textContent=eL[v];e.style.color=eC[v];}}

// Trade cards
let tcN=1;
function addCard(){
  tcN++;const id='tc-'+tcN;const catId='tc'+tcN+'-cats';
  const d=document.createElement('div');d.className='trade-card';d.id=id;
  const pbOptions=buildPbOptions();
  const customCats=buildCustomCatChips(catId);
  d.innerHTML=`<div class="tc-head"><div class="tc-num">${tcN}</div><span class="tc-title">第 ${tcN} 筆交易</span><button class="tc-rm" onclick="rmCard('${id}')"><i class="ti ti-x"></i></button></div>
  <div class="dir-toggle"><button class="dir-b long" onclick="setDir(this)"><i class="ti ti-trending-up"></i>做多</button><button class="dir-b short" onclick="setDir(this)"><i class="ti ti-trending-down"></i>做空</button></div>
  <div class="fg" style="margin-bottom:8px;"><div class="fl" style="display:flex;align-items:center;justify-content:space-between;">交易分類<button onclick="openAddTagModal('${catId}')" style="background:none;border:1px dashed var(--border2);border-radius:5px;padding:1px 7px;font-size:9px;color:var(--t3);cursor:pointer;font-family:var(--sans);">+ 新增標籤</button></div><div class="frow" id="${catId}">${customCats}</div></div>
  <div class="r2" style="margin-bottom:10px;"><div class="fg" style="margin-bottom:0"><div class="fl">股票代號</div><input class="di" type="text" placeholder="代號"></div><div class="fg" style="margin-bottom:0"><div class="fl">套用 Playbook</div><select class="di" style="cursor:pointer" onchange="applyPbToCard('${id}',this.value)">${pbOptions}</select></div></div>
  <div class="fg"><div class="fl">交易動作</div><div class="achips"><span class="achip buy" onclick="togAchip(this)"><i class="ti ti-trending-up"></i>買進</span><span class="achip sell" onclick="togAchip(this)"><i class="ti ti-trending-down"></i>賣出</span><span class="achip add" onclick="togAchip(this)"><i class="ti ti-plus"></i>加碼</span><span class="achip cut" onclick="togAchip(this)"><i class="ti ti-minus"></i>減碼</span></div></div>
  <div class="r4" style="margin-bottom:4px;"><div class="fg" style="margin-bottom:0"><div class="fl">成交價</div><input class="di" type="number" placeholder="0" oninput="updateCardRRFromInputs('${id}')"></div><div class="fg" style="margin-bottom:0"><div class="fl">股數</div><input class="di" type="number" placeholder="0"></div><div class="fg" style="margin-bottom:0"><div class="fl">停損價</div><input class="di dnv" type="number" placeholder="0" oninput="updateCardRRFromInputs('${id}')"></div><div class="fg" style="margin-bottom:0"><div class="fl">目標價</div><input class="di upv" type="number" placeholder="0" oninput="updateCardRRFromInputs('${id}')"></div></div>
  <div id="${id}-rr" style="font-size:11px;font-family:var(--mono);padding:4px 2px 6px;display:none;"></div>
  <div class="fg"><div class="fl">交易邏輯</div><textarea class="di tall" placeholder="帶量突破、回測均線..."></textarea></div>
  <div class="fg" style="margin-top:10px;"><div class="fl">心理標籤</div><div class="tags-sec"><div><div class="tgl pos">正向</div><div class="tags-row"><span class="tchip g" onclick="this.classList.toggle('on')"><i class="ti ti-check"></i>按計畫執行</span><span class="tchip g" onclick="this.classList.toggle('on')"><i class="ti ti-shield"></i>嚴守紀律</span></div></div><div><div class="tgl neg">負向</div><div class="tags-row"><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-flame"></i>FOMO</span><span class="tchip w" onclick="this.classList.toggle('on')"><i class="ti ti-alert-triangle"></i>盲目跟風</span><span class="tchip b" onclick="this.classList.toggle('on')"><i class="ti ti-mood-angry"></i>報復交易</span></div></div></div></div>`;
  document.getElementById('trade-cards').appendChild(d);
  d.scrollIntoView({behavior:'smooth',block:'start'});
}

// ══ 自定義分類標籤系統 ══════════════════════════════════
// 預設標籤（不可刪除）
const DEFAULT_CATS = ['現股','當沖','波段','AI供應鏈','半導體'];

function getCustomCats(){
  try{ return JSON.parse(localStorage.getItem('tradelog_cats')||'[]'); }
  catch(e){ return []; }
}
function saveCustomCats(cats){
  localStorage.setItem('tradelog_cats', JSON.stringify(cats));
}
function getAllCats(){
  return [...new Set([...DEFAULT_CATS, ...getCustomCats()])];
}

// 建立標籤 chips HTML（含自定義，第一個預設 active）
function buildCustomCatChips(catId, activeLabel='現股'){
  return getAllCats().map((cat, i) => {
    const isActive = cat === activeLabel;
    const isDef    = DEFAULT_CATS.includes(cat);
    return `<button class="chip${isActive?' active':''}" onclick="setCat(this,'${catId}')">${cat}${isDef?'':` <span onclick="event.stopPropagation();removeCustomCat('${cat}','${catId}')" style="margin-left:3px;opacity:.5;font-size:9px;cursor:pointer;">×</span>`}</button>`;
  }).join('');
}

// 打開新增標籤 modal
function openAddTagModal(catId){
  // 移除舊的
  document.getElementById('cat-modal')?.remove();
  const m = document.createElement('div');
  m.id='cat-modal';
  m.style.cssText='position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:9000;display:flex;align-items:center;justify-content:center;';
  m.innerHTML=`<div style="background:#fff;border-radius:14px;padding:24px;width:320px;box-shadow:0 8px 40px rgba(0,0,0,.2);">
    <div style="font-size:14px;font-weight:700;margin-bottom:14px;">新增分類標籤</div>
    <input id="cat-input" class="di" type="text" placeholder="如：CPO、TGV、生技、ETF..." style="margin-bottom:14px;" autofocus>
    <div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px;" id="cat-preview">
      ${getCustomCats().map(c=>`<span style="background:var(--blue-d);color:var(--blue);border:1px solid var(--blue-b);border-radius:20px;padding:3px 10px;font-size:11px;display:flex;align-items:center;gap:4px;">${c}<span onclick="removeCustomCat('${c}',null)" style="cursor:pointer;opacity:.6;">×</span></span>`).join('')}
    </div>
    <div style="display:flex;gap:8px;">
      <button onclick="document.getElementById('cat-modal').remove()" class="s-prev" style="flex:1;padding:9px;">取消</button>
      <button onclick="confirmAddTag('${catId}')" class="s-next" style="flex:2;padding:9px;">新增</button>
    </div>
  </div>`;
  document.body.appendChild(m);
  m.addEventListener('click',e=>{ if(e.target===m) m.remove(); });
  document.getElementById('cat-input').focus();
  document.getElementById('cat-input').addEventListener('keydown',e=>{ if(e.key==='Enter') confirmAddTag(catId); });
}

function confirmAddTag(catId){
  const inp=document.getElementById('cat-input');
  const val=inp?.value?.trim();
  if(!val){ return; }
  const cats=getCustomCats();
  if(!cats.includes(val) && !DEFAULT_CATS.includes(val)){
    cats.push(val);
    saveCustomCats(cats);
  }
  document.getElementById('cat-modal')?.remove();
  // 重刷所有交易卡的分類區
  refreshAllCatRows(catId, val);
  showToast(`標籤「${val}」已新增`);
}

function removeCustomCat(label, catId){
  const cats=getCustomCats().filter(c=>c!==label);
  saveCustomCats(cats);
  // 移除各卡中的這個 chip
  document.querySelectorAll('.frow .chip').forEach(btn=>{
    if(btn.textContent.trim().startsWith(label)){ btn.remove(); }
  });
  document.getElementById('cat-modal')?.remove();
  showToast(`標籤「${label}」已移除`);
}

function refreshAllCatRows(catId, newLabel){
  // 只在目標 catId 的 frow 裡加入新標籤
  const frow=document.getElementById(catId);
  if(!frow) return;
  // 先確認不重複
  const existing=[...frow.querySelectorAll('.chip')].map(b=>b.textContent.trim().replace('×','').trim());
  if(existing.includes(newLabel)) return;
  const btn=document.createElement('button');
  btn.className='chip';
  btn.innerHTML=`${newLabel} <span onclick="event.stopPropagation();removeCustomCat('${newLabel}',null)" style="margin-left:3px;opacity:.5;font-size:9px;cursor:pointer;">×</span>`;
  btn.onclick=()=>setCat(btn,catId);
  frow.appendChild(btn);
  // 自動選中剛加的
  setCat(btn, catId);
}

// ══ 第一張卡的分類標籤初始化 ════════════════════════════
function initFirstCardCats(){
  const frow=document.getElementById('tc1-cats');
  if(!frow) return;
  // 加入自定義標籤（預設已在 HTML 裡有現股/當沖等）
  const custom=getCustomCats();
  custom.forEach(cat=>{
    if([...frow.querySelectorAll('.chip')].some(b=>b.textContent.trim().startsWith(cat))) return;
    const btn=document.createElement('button');
    btn.className='chip';
    btn.innerHTML=`${cat} <span onclick="event.stopPropagation();removeCustomCat('${cat}',null)" style="margin-left:3px;opacity:.5;font-size:9px;cursor:pointer;">×</span>`;
    btn.onclick=()=>setCat(btn,'tc1-cats');
    frow.appendChild(btn);
  });
}

// ══ Playbook 連動 ════════════════════════════════════════
// 建立 Playbook 選項 HTML（從已載入的卡片讀取）
function buildPbOptions(){
  let opts='<option value="">不套用</option>';
  document.querySelectorAll('#pb-list .pbc').forEach(card=>{
    const id=card.id;
    const nameEl=card.querySelector('[id$="-n"]');
    const name=nameEl?.textContent||id;
    opts+=`<option value="${id}">${name}</option>`;
  });
  return opts;
}

// 選 Playbook 後自動帶入（從下拉選單）
function applyPbToCard(cardId, pbId){
  if(!pbId) return;
  const pbCard=document.getElementById(pbId);
  if(!pbCard) return;
  const num=pbId.replace('pb-','');
  const stop    =parseFloat(document.getElementById('pb'+num+'-stop')?.value)||null;
  const target  =parseFloat(document.getElementById('pb'+num+'-target')?.value)||null;
  const target2 =parseFloat(document.getElementById('pb'+num+'-target2')?.value)||null;
  const entryP  =parseFloat(document.getElementById('pb'+num+'-entry-price')?.value)||null;
  const entryEl =pbCard.querySelector('.pbrule:first-child span');
  const logic=(entryEl?.textContent||'').replace(/^進場：/,'');
  const capitalEl=pbCard.querySelector('.pbrule:nth-child(3) span');
  const capital=(capitalEl?.textContent||'').replace(/^資金：/,'');

  const card=document.getElementById(cardId);
  if(!card) return;
  const nums=card.querySelectorAll('input[type=number]');
  // nums[0]=成交價, nums[1]=股數, nums[2]=停損, nums[3]=目標
  if(stop    && nums[2] && !nums[2].value){ nums[2].value=stop;   nums[2].classList.add('dnv'); }
  if(target  && nums[3] && !nums[3].value){ nums[3].value=target; nums[3].classList.add('upv'); }
  const ta=card.querySelector('textarea');
  if(ta && !ta.value) ta.value=[logic,capital].filter(Boolean).join('\n');
  if(stop&&target) updateCardRR(cardId, stop, target, entryP);
  showToast('已帶入劇本設定');
}

// 從 Playbook 頁「執行劇本」按鈕 → 帶入日記
function applyPlaybookToJournal(pbId){
  const num=pbId.replace('pb-','');
  const name    =document.getElementById('pb'+num+'-n')?.textContent||'';
  const stop    =parseFloat(document.getElementById('pb'+num+'-stop')?.value)||null;
  const target  =parseFloat(document.getElementById('pb'+num+'-target')?.value)||null;
  const target2 =parseFloat(document.getElementById('pb'+num+'-target2')?.value)||null;
  const entryP  =parseFloat(document.getElementById('pb'+num+'-entry-price')?.value)||null;
  const entryEl =document.querySelector('#'+pbId+' .pbrule:first-child span');
  const logic=(entryEl?.textContent||'').replace(/^進場：/,'');
  window._pendingPlaybook={name,stop,target,target2,entryPrice:entryP,logic,pbId};
  showPage('journal',document.getElementById('nav-journal'));
  gotoStep(1);
  setTimeout(()=>applyPendingPlaybook('tc-1'),150);
  showToast('劇本「'+name+'」已帶入');
}

function applyPendingPlaybook(cardId){
  const pb=window._pendingPlaybook;
  if(!pb) return;
  const card=document.getElementById(cardId);
  if(!card) return;
  const nums=card.querySelectorAll('input[type=number]');
  if(pb.stop    && nums[2] && !nums[2].value){ nums[2].value=pb.stop;   nums[2].classList.add('dnv'); }
  if(pb.target  && nums[3] && !nums[3].value){ nums[3].value=pb.target; nums[3].classList.add('upv'); }
  const ta=card.querySelector('textarea');
  if(ta&&pb.logic&&!ta.value) ta.value=pb.logic;
  if(pb.stop&&pb.target) updateCardRR(cardId, pb.stop, pb.target, pb.entryPrice);
  window._pendingPlaybook=null;
}

// ══ 進出場卡風報比顯示 ══════════════════════════════════
function updateCardRR(cardId, stop, target, entry){
  const s=parseFloat(stop), t=parseFloat(target), e=parseFloat(entry)||null;
  const el=document.getElementById(cardId+'-rr');
  if(!el||!s||!t){ if(el) el.style.display='none'; return; }

  const isUp = t > s;
  const pct  = ((t-s)/Math.abs(s)*100).toFixed(2);

  let rrText = '';
  if(e && e > s){
    const risk   = e - s;       // 每股可能虧損
    const reward = t - e;       // 每股潛在獲利
    if(risk > 0 && reward > 0){
      const rr = (reward/risk).toFixed(2);
      const color = parseFloat(rr) >= 2 ? 'var(--dn)' : parseFloat(rr) >= 1 ? 'var(--blue)' : 'var(--amber)';
      rrText = ` → <span style="font-weight:800;color:${color};">風報比 1:${rr}</span>`;
    }
  }

  el.style.display='block';
  el.innerHTML = `<span style="color:${isUp?'var(--dn)':'var(--up)'};">停損 $${s}</span> → <span style="color:${isUp?'var(--dn)':'var(--up)'};">目標 $${t}（${isUp?'+':''}${pct}%）</span>${rrText}`;
}


// 三個輸入（成交價、停損、目標）任何一個變動就更新 RR
function updateCardRRFromInputs(cardId){
  const card=document.getElementById(cardId);
  if(!card) return;
  const nums=card.querySelectorAll('input[type=number]');
  const entry=parseFloat(nums[0]?.value)||null;
  const stop =parseFloat(nums[2]?.value)||null;
  const tgt  =parseFloat(nums[3]?.value)||null;
  if(stop&&tgt) updateCardRR(cardId, stop, tgt, entry);
  else{ const el=document.getElementById(cardId+'-rr'); if(el) el.style.display='none'; }
}

function rmCard(id){const e=document.getElementById(id);if(e) e.remove();}
function togAchip(el){const p=el.closest('.achips');if(!p) return;p.querySelectorAll('.achip').forEach(c=>c.classList.remove('on'));el.classList.add('on');}
function setDir(btn){const p=btn.closest('.dir-toggle');if(!p) return;p.querySelectorAll('.dir-b').forEach(b=>b.classList.remove('on'));btn.classList.add('on');}
// Fix3: 交易分類改為「多選 toggle」（不再單選）
function setCat(el, rowId){
  el.classList.toggle('active');
  // 確保至少有一個選中
  const r = document.getElementById(rowId);
  if(r && [...r.querySelectorAll('.chip')].every(c=>!c.classList.contains('active'))){
    el.classList.add('active'); // 防止全部取消
  }
}
function toggleCbox(inp){const box=inp.nextElementSibling;if(box) box.classList.toggle('checked',inp.checked);}
function addIfthen(){
  const r=document.createElement('div');
  r.className='ift-check';
  r.style.gridTemplateColumns='90px 1fr 52px 1fr 36px';
  const stockOpts=buildIfthenStockOptions();
  r.innerHTML=`<select class="di ift-stock" style="font-size:10px;padding:5px 4px;cursor:pointer;"><option value="">全部庫存</option>${stockOpts}</select><input class="di" type="text" placeholder="觸發條件（如：突破$120）"><div class="ifl then">THEN</div><input class="di" type="text" placeholder="執行動作"><label class="cbox-wrap"><input type="checkbox" onchange="toggleCbox(this)"><span class="cbox"></span></label>`;
  document.getElementById('ifthen-rows').appendChild(r);
}

// 從庫存+進出場卡建立股票下拉選項
function buildIfthenStockOptions(){
  const names=new Set();
  document.querySelectorAll('#inv-rows .inv-row .inv-name').forEach(inp=>{ if(inp.value?.trim()) names.add(inp.value.trim()); });
  document.querySelectorAll('#trade-cards .trade-card input[type=text]').forEach(inp=>{ if(inp.value?.trim()) names.add(inp.value.trim()); });
  return [...names].map(n=>`<option value="${n}">${n}</option>`).join('');
}

// 刷新 ifthen 行的股票下拉
function refreshIfthenStockSelects(){
  const opts='<option value="">全部庫存</option>'+buildIfthenStockOptions();
  document.querySelectorAll('#ifthen-rows .ift-stock').forEach(sel=>{ const cur=sel.value; sel.innerHTML=opts; if(cur) sel.value=cur; });
}
let invN=0;
function addInvRow(){invN++;if(window.addInvRowData) window.addInvRowData(invN,{});}
function delInvRow(id){const e=document.getElementById(id);if(e) e.remove();}
function calcPnl(rowId){
  const row=document.getElementById(rowId);if(!row) return;
  const inputs=row.querySelectorAll('input[type=number]');
  const shares=parseFloat(inputs[0]?.value)||0,cost=parseFloat(inputs[1]?.value)||0,now=parseFloat(inputs[2]?.value)||0;
  const pnl=(now-cost)*shares,pct=cost>0?((now-cost)/cost)*100:0;
  const pe=document.getElementById(rowId+'-pnl'),pce=document.getElementById(rowId+'-pct');
  if(pe){pe.textContent=(pnl>=0?'+':'')+Math.round(pnl).toLocaleString();pe.className='di inv-pnl '+(pnl>=0?'up':'dn');}
  if(pce){pce.textContent=(pct>=0?'+':'')+pct.toFixed(1)+'%';pce.className='di inv-pct '+(pct>=0?'up':'dn');}
  let total=0;
  document.querySelectorAll('.inv-row').forEach(r=>{
    const ins=r.querySelectorAll('input[type=number]');
    const s=parseFloat(ins[0]?.value)||0,c=parseFloat(ins[1]?.value)||0,n=parseFloat(ins[2]?.value)||0;
    total+=(n-c)*s;
  });
  const te=document.getElementById('inv-total-pnl');
  if(te){te.textContent=(total>=0?'+':'')+Math.round(total).toLocaleString();te.className='di inv-pnl '+(total>=0?'up':'dn');}
}
function togglePbCard(id){
  const num=id.replace('pb-','');
  const view=document.getElementById('pb'+num+'-view');
  const edit=document.getElementById('pb'+num+'-edit');
  const icon=document.getElementById('pb'+num+'-btn-icon');
  const txt =document.getElementById('pb'+num+'-btn-txt');
  if(!view||!edit) return;
  const isEditing=edit.style.display!=='none';
  view.style.display=isEditing?'block':'none';
  edit.style.display=isEditing?'none':'block';
  if(icon) icon.className=isEditing?'ti ti-edit':'ti ti-eye';
  if(txt)  txt.textContent=isEditing?'編輯':'檢視';
}
function updPbType(elId,val){const el=document.getElementById(elId);if(!el) return;el.className='pbtag';el.classList.add(val==='rev'?'rev':'trend');el.textContent=val==='rev'?'逆勢反彈':'趨勢跟隨';}

// API Keys
function saveApiKey(){
  const raw=document.getElementById('api-key-input').value;
  if(raw==='••••••••••••••••'){showToast('API Key 已儲存 ✓');return;}
  const key=raw.split('').filter(c=>c.charCodeAt(0)>=33&&c.charCodeAt(0)<=126).join('');
  if(!key||key.length<20){alert('請輸入正確的 Anthropic API Key');return;}
  localStorage.setItem('tradelog_api_key',key);
  document.getElementById('api-key-input').value='••••••••••••••••';
  showToast('API Key 已儲存 ✓');
}
function getApiKey(){const raw=localStorage.getItem('tradelog_api_key')||'';return raw.split('').filter(c=>c.charCodeAt(0)>=33&&c.charCodeAt(0)<=126).join('');}
// ── 風報比計算核心 ────────────────────────────────────────
// 公式：(目標價 - 進場價) / (進場價 - 停損價) → 1:N
function calcRR(target, entry, stop){
  if(!target || !entry || !stop) return null;
  const risk   = entry - stop;
  const reward = target - entry;
  if(risk <= 0 || reward <= 0) return null;
  return (reward / risk).toFixed(2);
}

function calcRRText(stop, target){
  // 簡化版（無進場價時用）
  if(!stop || !target) return '';
  const diff = target - stop;
  const pct  = (Math.abs(diff) / Math.abs(stop) * 100).toFixed(1);
  return diff > 0 ? `▲${pct}%` : `▼${pct}%`;
}

// Playbook 編輯中即時更新雙目標風報比
function calcPbRR(num){
  const e  = parseFloat(document.getElementById('pb'+num+'-entry-price')?.value) || 0;
  const s  = parseFloat(document.getElementById('pb'+num+'-stop')?.value)        || 0;
  const t1 = parseFloat(document.getElementById('pb'+num+'-target')?.value)      || 0;
  const t2 = parseFloat(document.getElementById('pb'+num+'-target2')?.value)     || 0;

  const el1 = document.getElementById('pb'+num+'-rr1');
  const el2 = document.getElementById('pb'+num+'-rr2');

  // 第一目標風報比
  if(el1){
    const rr1 = (e && s && t1) ? calcRR(t1, e, s) : null;
    if(rr1){
      el1.textContent = `風報比 1:${rr1}`;
      el1.style.color = parseFloat(rr1) >= 2 ? 'var(--dn)' : parseFloat(rr1) >= 1 ? 'var(--blue)' : 'var(--amber)';
    } else if(t1 && s) {
      const pct = ((t1-s)/Math.abs(s)*100).toFixed(1);
      el1.textContent = t1 > s ? `▲${pct}%` : `▼${pct}%`;
      el1.style.color = t1 > s ? 'var(--dn)' : 'var(--amber)';
    } else { el1.textContent = ''; }
  }

  // 第二目標風報比
  if(el2){
    const rr2 = (e && s && t2) ? calcRR(t2, e, s) : null;
    if(rr2){
      el2.textContent = `風報比 1:${rr2}`;
      el2.style.color = parseFloat(rr2) >= 2 ? 'var(--dn)' : parseFloat(rr2) >= 1 ? 'var(--blue)' : 'var(--amber)';
    } else if(t2 && s) {
      const pct = ((t2-s)/Math.abs(s)*100).toFixed(1);
      el2.textContent = t2 > s ? `▲${pct}%` : `▼${pct}%`;
      el2.style.color = t2 > s ? 'var(--dn)' : 'var(--amber)';
    } else { el2.textContent = ''; }
  }
}


// 刷新所有交易卡的 Playbook 下拉選單
function refreshPbSelects(){
  document.querySelectorAll('#trade-cards select').forEach(sel=>{
    const cardId=sel.closest('.trade-card')?.id||'tc-1';
    const cur=sel.value;
    sel.innerHTML=buildPbOptions();
    if(cur) sel.value=cur;
  });
}

window.addEventListener('load',()=>{
  const saved=localStorage.getItem('tradelog_api_key');
  const inp=document.getElementById('api-key-input');
  if(saved&&inp) inp.value='••••••••••••••••';
  // 初始化第一張卡的自定義標籤
  initFirstCardCats();
  // 初始化趨勢自定義標籤
  initTrendTags();
});

// Stats
let aiRange='week';
function setAiRange(el,range){document.querySelectorAll('#page-stats .chip').forEach(c=>c.classList.remove('active'));el.classList.add('active');aiRange=range;}
async function fetchJournalData(range){
  if(!window.currentUser) return [];
  const snap=await db.collection('users').doc(window.currentUser.uid).collection('journals').get();
  const now=new Date();
  const results=[];
  snap.forEach(doc=>{
    const date=new Date(doc.id);
    const diffDays=(now-date)/(1000*60*60*24);
    if(range==='week'&&diffDays>7) return;
    if(range==='month'&&diffDays>30) return;
    results.push({date:doc.id,...doc.data()});
  });
  return results.sort((a,b)=>a.date.localeCompare(b.date));
}
async function updateStatsCards(){
  // 從快取或 Firestore 取資料
  if(!window._calCache) await loadCalendarData();
  const allEntries = Object.values(window._calCache || {});
  allEntries.sort((a,b) => (a.date||'').localeCompare(b.date||''));

  const now2 = new Date();
  const ym   = `${now2.getFullYear()}-${String(now2.getMonth()+1).padStart(2,'0')}`;

  // ── 全期統計 ──
  let totalDays = allEntries.length;
  let hasPnlCount=0, win=0, lose=0, totalPnl=0;
  let maxLoss=0, maxLossDate='';
  let posTagCount=0, negTagCount=0, planWinCount=0, planTotalCount=0;
  const tagMap = {};
  const posTags = ['按計畫執行','嚴守紀律','理性分析','自律達成'];
  const negTags = ['FOMO','盲目跟風','過度交易','報復交易'];

  allEntries.forEach(d => {
    // 損益
    if(d.pnl != null){
      hasPnlCount++;
      totalPnl += d.pnl;
      if(d.pnl > 0) win++;
      else if(d.pnl < 0){
        lose++;
        if(d.pnl < maxLoss){ maxLoss = d.pnl; maxLossDate = d.date||''; }
      }
    }
    // 標籤
    const tags = Array.isArray(d.tags) ? d.tags : [];
    const notesText = (d.notes||'') + ' ' + (d.trend||'');
    const allText = tags.join(' ') + ' ' + notesText;
    let hasPos=false, hasNeg=false;
    posTags.forEach(t => { if(allText.includes(t)){ posTagCount++; tagMap[t]=(tagMap[t]||0)+1; hasPos=true; } });
    negTags.forEach(t => { if(allText.includes(t)){ negTagCount++; tagMap[t]=(tagMap[t]||0)+1; hasNeg=true; } });
    // 按計畫勝率：有正面標籤且有損益資料
    if(d.pnl != null){
      planTotalCount++;
      if(hasPos && !hasNeg && d.pnl > 0) planWinCount++;
    }
  });

  // ── 本月統計 ──
  let mPnl=0, mWin=0, mTotal=0;
  allEntries.forEach(d => {
    if(!(d.date||'').startsWith(ym) || d.pnl==null) return;
    mPnl += d.pnl; mTotal++;
    if(d.pnl > 0) mWin++;
  });

  // ── 更新統計頁 ──
  document.getElementById('stat-days').textContent  = totalDays + ' 天';
  document.getElementById('stat-total').textContent = hasPnlCount + ' 筆';
  const tagTotal = posTagCount + negTagCount || 1;
  document.getElementById('stat-pos').textContent = Math.round(posTagCount/tagTotal*100) + '%';
  document.getElementById('stat-neg').textContent = Math.round(negTagCount/tagTotal*100) + '%';
  const rng = document.getElementById('stats-range');
  if(rng) rng.textContent = `共 ${totalDays} 天 · ${hasPnlCount} 筆含損益`;

  // ── 更新 Dashboard 四格卡 ──
  // 卡1：月淨損益
  const dashPnl = document.getElementById('dash-pnl');
  if(dashPnl){
    const sign = mPnl >= 0 ? '+' : '';
    const abs  = Math.abs(mPnl);
    dashPnl.textContent = mTotal > 0 ? (abs>=1000?`${sign}$${(mPnl/1000).toFixed(1)}k`:`${sign}$${mPnl.toLocaleString()}`) : '—';
    dashPnl.className   = 'mv ' + (mPnl>=0?'up':'dn');
  }
  const dashPnlSub = document.getElementById('dash-pnl-sub');
  if(dashPnlSub) dashPnlSub.textContent = mTotal > 0 ? `本月 ${mTotal} 筆` : '本月無紀錄';

  // 卡2：整體勝率
  const dashWr = document.getElementById('dash-wr');
  if(dashWr) dashWr.textContent = hasPnlCount > 0 ? Math.round(win/hasPnlCount*100)+'%' : '—';
  const dashWrSub = document.getElementById('dash-wr-sub');
  if(dashWrSub) dashWrSub.textContent = hasPnlCount > 0 ? `${win}勝 ${lose}負` : '尚無損益紀錄';

  // 卡3：按計畫勝率（有正面標籤且獲利的比例）
  const dashPlan = document.getElementById('dash-plan-wr');
  if(dashPlan) dashPlan.textContent = planTotalCount > 0 ? Math.round(planWinCount/planTotalCount*100)+'%' : '—';
  const dashPlanSub = document.getElementById('dash-plan-sub');
  if(dashPlanSub) dashPlanSub.textContent = `共 ${planTotalCount} 筆有損益`;

  // 卡4：最大單日虧損
  const dashMax = document.getElementById('dash-max-loss');
  if(dashMax){
    dashMax.textContent = maxLoss < 0 ? `$${maxLoss.toLocaleString()}` : '—';
    dashMax.className = 'mv dn';
  }
  const dashMaxSub = document.getElementById('dash-max-loss-sub');
  if(dashMaxSub) dashMaxSub.textContent = maxLossDate ? maxLossDate.slice(5) : '尚無虧損紀錄';

  // ── 側邊欄 ──
  updateSidebarStats();

  // ── 標籤長條圖 ──
  renderTagBars(allEntries, tagMap);
}

function renderTagBars(data, tagMap){
  const container = document.getElementById('tag-bars');
  if(!container) return;
  if(!data || data.length === 0){
    container.innerHTML = '<div style="text-align:center;padding:20px;color:var(--t2);font-size:12px;">尚無日記資料，先去填寫今日日記吧！</div>';
    return;
  }

  // 若有真實 tagMap 就用，否則用示意資料
  const total = data.length || 1;
  const tags = tagMap && Object.keys(tagMap).length > 0 ? [
    ...Object.entries(tagMap)
      .sort((a,b) => b[1]-a[1])
      .slice(0, 6)
      .map(([name, cnt]) => ({
        name,
        val: Math.round(cnt/total*100),
        color: ['按計畫','嚴守紀律','理性分析','自律'].includes(name) ? '#fee2e0' : '#e0f2ec',
        tc:   ['按計畫','嚴守紀律','理性分析','自律'].includes(name) ? 'var(--up)'  : 'var(--dn)'
      }))
  ] : [
    {name:'按計畫執行',val:0,color:'#fee2e0',tc:'var(--up)'},
    {name:'嚴守紀律',  val:0,color:'#fee2e0',tc:'var(--up)'},
    {name:'FOMO',     val:0,color:'#e0f2ec',tc:'var(--dn)'},
    {name:'過度交易',  val:0,color:'#e0f2ec',tc:'var(--dn)'},
  ];

  container.innerHTML = tags.map(t => `
    <div class="brow">
      <span class="blbl">${t.name}</span>
      <div class="btrack">
        <div class="bfill" style="width:${t.val}%;background:${t.color};border-radius:4px;min-width:${t.val>0?'28px':'0'}">
          <span class="bpct" style="color:${t.tc}">${t.val}%</span>
        </div>
      </div>
    </div>`).join('');
}
async function generateAiReport(){
  const apiKey=getApiKey();
  if(!apiKey){alert('請先在上方輸入你的 Anthropic API Key！');return;}
  document.getElementById('ai-report-content').style.display='none';
  document.getElementById('ai-loading').style.display='block';
  const badge=document.getElementById('ai-status-badge');
  badge.textContent='分析中...';badge.style.background='var(--purple-d)';badge.style.color='var(--purple)';
  try{
    const journals=await fetchJournalData(aiRange);
    const rangeLabel=aiRange==='week'?'本週':aiRange==='month'?'本月':'所有歷史';
    if(journals.length===0) throw new Error('尚無日記資料！請先填寫幾天的交易日記。');
    const summary=journals.map(j=>`日期：${j.date}\n趨勢：${j.trend||'未填寫'}\n資金：${j.funds||'未填寫'}\n紀律：${j.notes||'未填寫'}`).join('\n\n---\n\n');
    const response=await fetch('https://tradelog-proxy.a23137141.workers.dev',{method:'POST',headers:{'Content-Type':'application/json','x-api-key':apiKey,'anthropic-version':'2023-06-01','anthropic-dangerous-allow-direct-browser-access':'true'},body:JSON.stringify({model:'claude-sonnet-4-5',max_tokens:1000,messages:[{role:'user',content:`你是一位專業的台灣股市交易心理教練。以下是這位交易者「${rangeLabel}」的投資日記資料：\n\n${summary}\n\n請用繁體中文提供：\n1. **交易心理分析**：找出 2-3 個具體的行為模式或心理盲點\n2. **亮點表現**：肯定做得好的地方\n3. **下週改善建議**：給出 2-3 個可立即執行的具體行動\n\n語氣要像一位嚴格但關心的教練，用「你」稱呼。`}]})});
    if(!response.ok){const err=await response.json();throw new Error(err.error?.message||'呼叫 API 失敗');}
    const data=await response.json();const text=data.content[0].text;
    document.getElementById('ai-loading').style.display='none';
    document.getElementById('ai-report-content').style.display='block';
    const blocks=text.split('\n\n').filter(Boolean);
    document.getElementById('ai-report-content').innerHTML=blocks.map(block=>`<div class="ai-insight-block"><i class="ti ti-sparkles"></i><p>${block.replace(/\*\*(.*?)\*\*/g,'<b>$1</b>').replace(/\n/g,'<br>')}</p></div>`).join('');
    badge.textContent='✓ 已生成';badge.style.background='var(--blue-d)';badge.style.color='var(--blue)';badge.style.border='1px solid var(--blue-b)';
  }catch(err){
    document.getElementById('ai-loading').style.display='none';
    document.getElementById('ai-report-content').style.display='block';
    document.getElementById('ai-report-content').innerHTML=`<div style="padding:16px;background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);color:var(--up);font-size:12px;"><b>錯誤：</b>${err.message}</div>`;
    badge.textContent='錯誤';badge.style.background='var(--up-d)';badge.style.color='var(--up)';
  }
}
async function generateFullReport(){
  const apiKey=getApiKey();if(!apiKey){alert('請先輸入 API Key！');return;}
  const journals=await fetchJournalData('month');if(journals.length===0){alert('本月尚無日記資料！');return;}
  document.getElementById('full-report-card').style.display='block';
  document.getElementById('full-report-content').textContent='正在生成完整報告，請稍候...';
  document.getElementById('full-report-card').scrollIntoView({behavior:'smooth'});
  const summary=journals.map(j=>`${j.date}：${j.notes||'無備註'}`).join('\n');
  try{
    const response=await fetch('https://tradelog-proxy.a23137141.workers.dev',{method:'POST',headers:{'Content-Type':'application/json','x-api-key':apiKey,'anthropic-version':'2023-06-01','anthropic-dangerous-allow-direct-browser-access':'true'},body:JSON.stringify({model:'claude-sonnet-4-5',max_tokens:2000,messages:[{role:'user',content:`你是一位台灣股市交易心理教練。以下是這位交易者本月的投資日記摘要：\n\n${summary}\n\n請用繁體中文生成完整月度覆盤報告，包含：\n一、本月交易心理總結\n二、主要行為模式分析\n三、情緒管理評估\n四、下月具體改善計畫\n五、教練總評語\n\n語氣真誠直接，用「你」稱呼。`}]})});
    const data=await response.json();
    document.getElementById('full-report-content').textContent=data.content[0].text;
  }catch(err){document.getElementById('full-report-content').textContent='生成失敗：'+err.message;}
}
function copyReport(){navigator.clipboard.writeText(document.getElementById('full-report-content').textContent).then(()=>showToast('已複製到剪貼簿 ✓'));}
</script>
<script>
// ══ 個股分析 ══
function saGetToken(){const v=document.getElementById('sa-finmind-token')?.value||'';return v==='••••••••'?localStorage.getItem('tradelog_finmind')||'':v;}
function saGetClaudeKey(){
  const keys=['tradelog_api_key','sa_claude_key'];
  for(const k of keys){const v=localStorage.getItem(k);if(v&&v.length>20) return v.split('').filter(c=>c.charCodeAt(0)>=33&&c.charCodeAt(0)<=126).join('');}
  const inp=document.getElementById('api-key-input');
  if(inp&&inp.value&&inp.value!=='••••••••••••••••') return inp.value.split('').filter(c=>c.charCodeAt(0)>=33&&c.charCodeAt(0)<=126).join('');
  return '';
}
function sasSaveKeys(){const fm=document.getElementById('sa-finmind-token')?.value;if(fm&&fm!=='••••••••'){localStorage.setItem('tradelog_finmind',fm);document.getElementById('sa-finmind-token').value='••••••••';}showToast('FinMind Token 已儲存 ✓');}
function saLoadKeys(){if(localStorage.getItem('tradelog_finmind')&&document.getElementById('sa-finmind-token')) document.getElementById('sa-finmind-token').value='••••••••';}
async function saFetchFinMind(dataset,stockId,s,e){
  const token=saGetToken();if(!token) throw new Error('請先填入 FinMind Token！');
  const p=new URLSearchParams({dataset,data_id:stockId,start_date:s,end_date:e,token});
  const res=await fetch(`https://tradelog-proxy.a23137141.workers.dev/finmind?${p}`);
  const json=await res.json();if(json.status!==200) throw new Error(`FinMind：${json.msg}`);
  return json.data;
}
function saCalcMA(data,n){return data.map((_,i)=>{if(i<n-1) return null;return data.slice(i-n+1,i+1).reduce((s,d)=>s+d.close,0)/n;});}
function saCalcRSI(data,n=14){const rsi=new Array(data.length).fill(null);if(data.length<n+1) return rsi;const chg=data.map((d,i)=>i===0?0:d.close-data[i-1].close);let g=0,l=0;for(let i=1;i<=n;i++){if(chg[i]>0)g+=chg[i];else l+=Math.abs(chg[i]);}g/=n;l/=n;rsi[n]=100-(100/(1+(l===0?Infinity:g/l)));for(let i=n+1;i<data.length;i++){const cg=chg[i]>0?chg[i]:0,cl=chg[i]<0?Math.abs(chg[i]):0;g=(g*(n-1)+cg)/n;l=(l*(n-1)+cl)/n;rsi[i]=100-(100/(1+(l===0?100:g/l)));}return rsi;}
function saFmt(n,d=2){return n!==null&&!isNaN(n)?Number(n).toFixed(d):'—';}
function saRsiSt(v){if(v===null) return'—';return v>70?'Overbought 超買':v<30?'Oversold 超賣':'Normal 正常';}
function saRsiCls(v){return v>70?'rsi-ob':v<30?'rsi-os':'rsi-ok';}
function saGetDR(days=90){const end=new Date(),start=new Date();start.setDate(end.getDate()-days);return{start:start.toISOString().slice(0,10),end:end.toISOString().slice(0,10)};}

let saDT=null;
function onStockInput(input,cardId){const hint=document.getElementById('tc1-auto-hint');if(hint) hint.textContent='';clearTimeout(saDT);const val=input.value.trim();if(val.length>=4){if(hint) hint.textContent='✦ 分析中...';saDT=setTimeout(()=>triggerStockAnalysis(val,cardId),1500);}}

async function triggerStockAnalysis(stockId,cardId){
  if(!stockId||!saGetToken()) return;
  const panel=document.getElementById(cardId+'-analysis');if(!panel) return;
  panel.classList.add('show');
  document.getElementById(cardId+'-analysis-title').textContent=stockId+' 即時分析';
  ['close','rsi','inst','margin'].forEach(k=>{const el=document.getElementById(cardId+'-'+k);if(el){el.textContent='載入...';el.className='tc-analysis-val nu';}});
  const sumEl=document.getElementById(cardId+'-ai-summary');
  if(sumEl) sumEl.innerHTML='<div style="display:flex;align-items:center;gap:6px;"><div class="ai-dots2"><span></span><span></span><span></span></div><span style="font-size:10px;color:var(--purple);">Claude 分析中...</span></div>';
  const {start,end}=saGetDR(60);
  try{
    const pd=await saFetchFinMind('TaiwanStockPrice',stockId,start,end);
    if(!pd||pd.length===0) throw new Error('無數據');
    const cd=pd.sort((a,b)=>a.date.localeCompare(b.date)).map(d=>({date:d.date,open:parseFloat(d.open),high:parseFloat(d.max),low:parseFloat(d.min),close:parseFloat(d.close)}));
    const rsiV=saCalcRSI(cd,14);const lastRsi=rsiV.filter(v=>v!==null).slice(-1)[0];const last=cd[cd.length-1];
    const closeEl=document.getElementById(cardId+'-close');if(closeEl) closeEl.textContent='$'+saFmt(last.close);
    const rsiEl=document.getElementById(cardId+'-rsi');if(rsiEl){rsiEl.textContent=lastRsi!==null?saFmt(lastRsi):'—';rsiEl.className='tc-analysis-val '+(lastRsi>70?'up':lastRsi<30?'dn':'nu');}
    try{const id2=await saFetchFinMind('TaiwanStockInstitutionalInvestorsBuySell',stockId,start,end);const s=id2.sort((a,b)=>b.date.localeCompare(a.date));const ld=s[0]?.date;const lr=s.filter(d=>d.date===ld);const gn=name=>{const r=lr.find(d=>d.name===name);return r?parseInt(r.buy)-parseInt(r.sell):0;};const tot=gn('Foreign_Investor')+gn('Investment_Trust')+gn('Dealer');const iEl=document.getElementById(cardId+'-inst');if(iEl){iEl.textContent=(tot>=0?'+':'')+tot.toLocaleString()+' 張';iEl.className='tc-analysis-val '+(tot>=0?'up':'dn');}}catch(e){}
    try{const mg=await saFetchFinMind('TaiwanStockMarginPurchaseShortSale',stockId,start,end);const s2=mg.sort((a,b)=>b.date.localeCompare(a.date));const lm=s2[0],pm=s2[1];if(lm){const delta=pm?parseInt(lm.MarginPurchaseTodayBalance)-parseInt(pm.MarginPurchaseTodayBalance):0;const mEl=document.getElementById(cardId+'-margin');if(mEl){mEl.textContent=(delta>=0?'+':'')+delta.toLocaleString()+' 張';mEl.className='tc-analysis-val '+(delta>=0?'up':'dn');}}}catch(e){}
    const apiKey=saGetClaudeKey();
    if(apiKey){const ma20=saCalcMA(cd,20);const lm20=ma20[ma20.length-1];try{const res=await fetch('https://tradelog-proxy.a23137141.workers.dev',{method:'POST',headers:{'Content-Type':'application/json','x-api-key':apiKey,'anthropic-version':'2023-06-01','anthropic-dangerous-allow-direct-browser-access':'true'},body:JSON.stringify({model:'claude-sonnet-4-5',max_tokens:350,messages:[{role:'user',content:`以繁體中文，2-3句話分析台股 ${stockId}：收盤 $${saFmt(last.close)}，RSI ${saFmt(lastRsi)}（${saRsiSt(lastRsi)}），20MA $${saFmt(lm20)}。近5日：${cd.slice(-5).map(d=>d.close.toFixed(1)).join('→')}。僅供教育參考。`}]})});const json=await res.json();if(sumEl) sumEl.innerHTML=`<i class="ti ti-sparkles" style="font-size:13px;color:var(--purple);margin-right:6px;"></i>${json.content[0].text.replace(/\*\*(.*?)\*\*/g,'<b>$1</b>')}`;}catch(e){if(sumEl) sumEl.textContent='AI 分析暫時無法使用';}}
    else{if(sumEl) sumEl.innerHTML='<span style="color:var(--t2);font-size:11px;">設定 Anthropic API Key 後可顯示 AI 摘要</span>';}
    const hint=document.getElementById('tc1-auto-hint');if(hint) hint.textContent='✓ 分析完成';
  }catch(err){if(sumEl) sumEl.textContent='分析失敗：'+err.message;const hint=document.getElementById('tc1-auto-hint');if(hint) hint.textContent='';}
}

async function saRunAnalysis(){
  const stockId=document.getElementById('sa-stock-input').value.trim();const days=parseInt(document.getElementById('sa-days').value)||90;
  if(!stockId){showToast('請輸入股票代號！');return;}if(!saGetToken()){showToast('請先填入 FinMind Token！');return;}
  document.getElementById('sa-empty').style.display='none';document.getElementById('sa-results').style.display='flex';
  document.getElementById('sa-ai-badge').textContent='分析中...';
  document.getElementById('sa-ai-content').innerHTML='<div style="text-align:center;padding:24px;"><div class="ai-dots2" style="justify-content:center;margin-bottom:10px;"><span></span><span></span><span></span></div><div style="font-size:12px;color:var(--purple);">Claude 正在分析...</div></div>';
  const {start,end}=saGetDR(days);
  try{
    const pd=await saFetchFinMind('TaiwanStockPrice',stockId,start,end);
    if(!pd||pd.length===0) throw new Error(`找不到 ${stockId}`);
    const cd=pd.sort((a,b)=>a.date.localeCompare(b.date)).map(d=>({date:d.date,open:parseFloat(d.open),high:parseFloat(d.max),low:parseFloat(d.min),close:parseFloat(d.close),volume:parseInt(d.Trading_Volume)}));
    const ma5=saCalcMA(cd,5),ma10=saCalcMA(cd,10),ma20=saCalcMA(cd,20),ma60=saCalcMA(cd,60),rsiV=saCalcRSI(cd,14);
    const first=cd[0],last=cd[cd.length-1],lastRsi=rsiV.filter(v=>v!==null).slice(-1)[0];
    const pct=(last.close-first.close)/first.close*100,maxH=Math.max(...cd.map(d=>d.high)),minL=Math.min(...cd.map(d=>d.low));
    document.getElementById('sa-title').textContent=stockId+' 技術分析';document.getElementById('sa-period').textContent=start+' ～ '+end+'　'+cd.length+' 日';
    document.getElementById('sa-chart-title').textContent=stockId+' K 線圖';document.getElementById('sa-chart-sub').textContent=cd.length+' 日';
    document.getElementById('sa-m-start').textContent='$'+saFmt(first.close);document.getElementById('sa-m-end').textContent='$'+saFmt(last.close);
    const chgEl=document.getElementById('sa-m-chg');chgEl.textContent=(pct>=0?'+':'')+pct.toFixed(2)+'%';chgEl.className='sa-mv '+(pct>=0?'up':'dn');document.getElementById('sa-m-chg-abs').textContent=(pct>=0?'+':'')+saFmt(last.close-first.close);
    const rsiMEl=document.getElementById('sa-m-rsi');if(rsiMEl){rsiMEl.textContent=lastRsi!==null?saFmt(lastRsi):'—';rsiMEl.className='sa-mv '+(lastRsi>70?'up':lastRsi<30?'dn':'nu');}
    const rsiStEl=document.getElementById('sa-m-rsi-st');if(rsiStEl) rsiStEl.textContent=saRsiSt(lastRsi).split(' ')[0];
    document.getElementById('sa-m-hl').textContent='$'+saFmt(maxH)+' / $'+saFmt(minL);
    if(lastRsi!==null) document.getElementById('sa-rsi-badge').innerHTML=`<span class="rsi-badge ${saRsiCls(lastRsi)}">RSI ${saFmt(lastRsi)} · ${saRsiSt(lastRsi)}</span>`;
    const wEl=document.getElementById('sa-rsi-warn');
    if(lastRsi>70){wEl.style.display='block';wEl.innerHTML=`<div style="background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);padding:8px 12px;font-size:12px;color:var(--up);">⚠️ RSI 超買（${saFmt(lastRsi)}）歷史顯示此時曾處於相對高位。</div>`;}
    else if(lastRsi<30){wEl.style.display='block';wEl.innerHTML=`<div style="background:var(--dn-d);border:1px solid var(--dn-b);border-radius:var(--r);padding:8px 12px;font-size:12px;color:var(--dn);">ℹ️ RSI 超賣（${saFmt(lastRsi)}）歷史顯示此時曾處於相對低位。</div>`;}
    else wEl.style.display='none';
    saRenderKChart(cd,ma5,ma10,ma20,ma60);saRenderRsiChart(cd,rsiV);
    document.getElementById('sa-data-tbody').innerHTML=cd.slice(-15).reverse().map((d,i)=>{const idx=cd.length-1-i;const rsi=rsiV[idx],rs=rsi!==null?(rsi>70?'超買':rsi<30?'超賣':'正常'):'—',rc=rsi!==null?(rsi>70?'td-up':rsi<30?'td-dn':''):'';return `<tr><td>${d.date}</td><td>${saFmt(d.open)}</td><td class="td-up">${saFmt(d.high)}</td><td class="td-dn">${saFmt(d.low)}</td><td>${saFmt(d.close)}</td><td>${(d.volume/1000).toFixed(0)}K</td><td>${ma5[idx]!==null?saFmt(ma5[idx]):'—'}</td><td>${ma20[idx]!==null?saFmt(ma20[idx]):'—'}</td><td class="${rc}">${rsi!==null?saFmt(rsi):'—'}</td><td class="${rc}">${rs}</td></tr>`;}).join('');
    saFetchInst(stockId,start,end);saFetchMargin(stockId,start,end);
    saGenerateAI(stockId,cd.slice(-20).map((d,i)=>({date:d.date,close:d.close.toFixed(2),ma5:ma5[cd.length-20+i]?.toFixed(2)||null,ma20:ma20[cd.length-20+i]?.toFixed(2)||null,rsi:rsiV[cd.length-20+i]?.toFixed(2)||null})),first,last,pct,lastRsi,days);
  }catch(err){showToast('錯誤：'+err.message);document.getElementById('sa-ai-content').innerHTML=`<div style="padding:14px;background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);font-size:12px;color:var(--up);">${err.message}</div>`;}
}
function saRenderKChart(cd,ma5,ma10,ma20,ma60){const c=document.getElementById('sa-chart');if(!c||typeof LightweightCharts==='undefined') return;c.innerHTML='';const chart=LightweightCharts.createChart(c,{width:c.clientWidth,height:360,layout:{background:{color:'#fff'},textColor:'#7a7268'},grid:{vertLines:{color:'#f0ede8'},horzLines:{color:'#f0ede8'}},rightPriceScale:{borderColor:'#e2ddd6'},timeScale:{borderColor:'#e2ddd6',timeVisible:true}});const cs=chart.addCandlestickSeries({upColor:'#c0392b',downColor:'#1a7a4a',borderUpColor:'#c0392b',borderDownColor:'#1a7a4a',wickUpColor:'#c0392b',wickDownColor:'#1a7a4a'});cs.setData(cd.map(d=>({time:d.date,open:d.open,high:d.high,low:d.low,close:d.close})));const aM=(vals,color,title)=>{const s=chart.addLineSeries({color,lineWidth:1,title});s.setData(cd.map((d,i)=>vals[i]!==null?{time:d.date,value:vals[i]}:null).filter(Boolean));};aM(ma5,'#e8c84a','MA5');aM(ma10,'#4a90e2','MA10');aM(ma20,'#e24a8f','MA20');aM(ma60,'#4ae28f','MA60');window.addEventListener('resize',()=>chart.applyOptions({width:c.clientWidth}));}
function saRenderRsiChart(cd,rsiV){const c=document.getElementById('sa-rsi-chart');if(!c||typeof LightweightCharts==='undefined') return;c.innerHTML='';const chart=LightweightCharts.createChart(c,{width:c.clientWidth,height:140,layout:{background:{color:'#fff'},textColor:'#7a7268'},grid:{vertLines:{color:'#f0ede8'},horzLines:{color:'#f0ede8'}},rightPriceScale:{borderColor:'#e2ddd6',scaleMargins:{top:0.1,bottom:0.1}},timeScale:{borderColor:'#e2ddd6',timeVisible:true}});const rs=chart.addLineSeries({color:'#4a90e2',lineWidth:2,title:'RSI(14)'});rs.setData(cd.map((d,i)=>rsiV[i]!==null?{time:d.date,value:rsiV[i]}:null).filter(Boolean));const vd=cd.filter((_,i)=>rsiV[i]!==null);if(vd.length>0){const ob=chart.addLineSeries({color:'#c0392b',lineWidth:1,lineStyle:LightweightCharts.LineStyle.Dashed,title:'OB 70'});const os=chart.addLineSeries({color:'#1a7a4a',lineWidth:1,lineStyle:LightweightCharts.LineStyle.Dashed,title:'OS 30'});ob.setData([{time:vd[0].date,value:70},{time:vd[vd.length-1].date,value:70}]);os.setData([{time:vd[0].date,value:30},{time:vd[vd.length-1].date,value:30}]);}window.addEventListener('resize',()=>chart.applyOptions({width:c.clientWidth}));}
async function saFetchInst(stockId,start,end){try{const data=await saFetchFinMind('TaiwanStockInstitutionalInvestorsBuySell',stockId,start,end);const sorted=data.sort((a,b)=>b.date.localeCompare(a.date));const ld=sorted[0]?.date;const iDEl=document.getElementById('sa-inst-date');if(iDEl) iDEl.textContent=ld||'—';const byDate={};sorted.forEach(r=>{if(!byDate[r.date])byDate[r.date]={f:0,t:0,d:0};const net=parseInt(r.buy)-parseInt(r.sell);if(r.name==='Foreign_Investor'||r.name==='Foreign_Dealer_Self')byDate[r.date].f+=net;else if(r.name==='Investment_Trust')byDate[r.date].t+=net;else if(r.name==='Dealer_self'||r.name==='Dealer_Hedging')byDate[r.date].d+=net;});const lr=byDate[ld]||{};const si=(id,bid,net)=>{const el=document.getElementById(id);if(!el) return;el.textContent=(net>=0?'+':'')+net.toLocaleString()+' 張';el.className='inst-val2 '+(net>=0?'up':'dn');const bar=document.getElementById(bid);if(bar){bar.style.width=Math.min(Math.abs(net)/10000*100,100)+'%';bar.style.background=net>=0?'var(--up)':'var(--dn)';}};si('sa-inst-f','sa-inst-f-bar',lr.f||0);si('sa-inst-t','sa-inst-t-bar',lr.t||0);si('sa-inst-d','sa-inst-d-bar',lr.d||0);const dates=[...new Set(sorted.map(d=>d.date))].slice(0,10);const tbody=document.getElementById('sa-inst-tbody');if(tbody) tbody.innerHTML=dates.map(date=>{const row=byDate[date]||{};const total=(row.f||0)+(row.t||0)+(row.d||0);const f2=v=>v===undefined?'—':(v>=0?'+':'')+v.toLocaleString();const cl=v=>v>0?'td-up':v<0?'td-dn':'';return `<tr><td>${date}</td><td class="${cl(row.f)}">${f2(row.f)}</td><td class="${cl(row.t)}">${f2(row.t)}</td><td class="${cl(row.d)}">${f2(row.d)}</td><td class="${cl(total)}" style="font-weight:600">${f2(total)}</td></tr>`;}).join('');}catch(e){}}
async function saFetchMargin(stockId,start,end){try{const data=await saFetchFinMind('TaiwanStockMarginPurchaseShortSale',stockId,start,end);const sorted=data.sort((a,b)=>b.date.localeCompare(a.date));const latest=sorted[0],prev=sorted[1];if(!latest) return;const mDEl=document.getElementById('sa-margin-date');if(mDEl) mDEl.textContent=latest.date;const lb=parseInt(latest.MarginPurchaseTodayBalance),sb=parseInt(latest.ShortSaleTodayBalance);const ld=prev?lb-parseInt(prev.MarginPurchaseTodayBalance):0,sd=prev?sb-parseInt(prev.ShortSaleTodayBalance):0;const mlEl=document.getElementById('sa-mg-loan');if(mlEl) mlEl.textContent=lb.toLocaleString()+' 張';const ldEl=document.getElementById('sa-mg-loan-d');if(ldEl){ldEl.textContent=(ld>=0?'+':'')+ld.toLocaleString()+' 張';ldEl.className='sa-mv '+(ld>=0?'up':'dn');}const msEl=document.getElementById('sa-mg-short');if(msEl) msEl.textContent=sb.toLocaleString()+' 張';const sdEl=document.getElementById('sa-mg-short-d');if(sdEl){sdEl.textContent=(sd>=0?'+':'')+sd.toLocaleString()+' 張';sdEl.className='sa-mv '+(sd>=0?'dn':'up');}}catch(e){}}
async function saGenerateAI(stockId,data,first,last,pct,lastRsi,days){
  const apiKey=saGetClaudeKey();const aiBadge=document.getElementById('sa-ai-badge');const aiContent=document.getElementById('sa-ai-content');
  if(!apiKey){if(aiContent) aiContent.innerHTML='<div style="padding:14px;background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);font-size:12px;color:var(--amber);">請在「統計 &amp; AI」頁面設定 Anthropic API Key 啟用分析。</div>';if(aiBadge) aiBadge.textContent='未設定';return;}
  try{const res=await fetch('https://tradelog-proxy.a23137141.workers.dev',{method:'POST',headers:{'Content-Type':'application/json','x-api-key':apiKey,'anthropic-version':'2023-06-01','anthropic-dangerous-allow-direct-browser-access':'true'},body:JSON.stringify({model:'claude-sonnet-4-5',max_tokens:2000,messages:[{role:'user',content:`你是台灣股市技術分析師。以繁體中文分析 ${stockId}：\n期間：近 ${days} 日，漲跌 ${pct.toFixed(2)}%（$${first.close.toFixed(2)}→$${last.close.toFixed(2)}）\nRSI(14)：${lastRsi!==null?lastRsi.toFixed(2):'不足'}（${saRsiSt(lastRsi)}）\n近 20 日數據：${JSON.stringify(data)}\n\n請分析：\n#### 1. 趨勢分析\n#### 2. RSI 動量分析\n#### 3. 支撐壓力位\n#### 4. 風險評估\n#### 5. 短中期技術觀察\n\n使用客觀用語，不提供投資建議。`}]})});const json=await res.json();if(!res.ok) throw new Error(json.error?.message||'API 失敗');const text=json.content[0].text;const sections=text.split(/\n#{1,4}\s+/).filter(Boolean);if(aiContent) aiContent.innerHTML=sections.map((s,i)=>{const lines=s.split('\n');const title=i===0?'':lines[0];const body=(i===0?lines:lines.slice(1)).join('\n').trim();return `<div style="margin-bottom:12px;">${title?`<div style="font-size:12px;font-weight:600;margin-bottom:6px;display:flex;align-items:center;gap:5px;"><i class="ti ti-sparkles" style="font-size:13px;color:var(--purple);"></i>${title}</div>`:''}<div style="background:var(--purple-d);border:1px solid var(--purple-b);border-radius:var(--r);padding:11px 13px;font-size:12px;color:#5a3080;line-height:1.8;">${body.replace(/\*\*(.*?)\*\*/g,'<b>$1</b>').replace(/\n/g,'<br>')}</div></div>`;}).join('');if(aiBadge) aiBadge.textContent='✓ 分析完成';}catch(err){if(aiContent) aiContent.innerHTML=`<div style="padding:14px;background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);font-size:12px;color:var(--up);">${err.message}</div>`;if(aiBadge) aiBadge.textContent='錯誤';}
}
</script>
<script>
// ══ 籌碼診斷系統（修正版 v2）══
// 修正：TaiwanStockHoldingSharesPer + HoldingSharesPercent欄位 + 周轉率單位確認
const CHIP_WORKER = 'https://tradelog-proxy.a23137141.workers.dev';
let chipCharts = {};

function destroyChipChart(id){if(chipCharts[id]){chipCharts[id].destroy();delete chipCharts[id];}}
function getChipDateRange(months){const end=new Date();const start=new Date();start.setMonth(start.getMonth()-months);const fmt=d=>d.toISOString().slice(0,10);return{start:fmt(start),end:fmt(end)};}

async function chipFetch(dataset,stockId,start,end){
  const token=localStorage.getItem('tradelog_finmind')||'';
  const params=new URLSearchParams({dataset,data_id:stockId,start_date:start,end_date:end,token});
  const res=await fetch(`${CHIP_WORKER}/finmind?${params}`);
  const json=await res.json();
  if(json.status!==200) throw new Error(`FinMind 錯誤: ${json.msg}`);
  return json.data;
}
async function chipFetchInfo(stockId){
  const token=localStorage.getItem('tradelog_finmind')||'';
  const params=new URLSearchParams({dataset:'TaiwanStockInfo',data_id:stockId,token});
  const res=await fetch(`${CHIP_WORKER}/finmind?${params}`);
  const json=await res.json();
  return json.data||[];
}
function fmtN(n,d=0){return n===null||isNaN(n)?'—':Number(n).toLocaleString('zh-TW',{minimumFractionDigits:d,maximumFractionDigits:d});}
function makeChipChart(id,type,labels,datasets,opts={}){
  destroyChipChart(id);
  const ctx=document.getElementById(id);if(!ctx) return;
  chipCharts[id]=new Chart(ctx,{type,data:{labels,datasets},options:{responsive:true,plugins:{legend:{display:opts.legend??false,labels:{font:{size:10},color:'#7a7268'}},tooltip:{mode:'index',intersect:false}},scales:{x:{ticks:{maxTicksLimit:6,font:{size:9},color:'#b0a89e'},grid:{color:'#f0ede8'}},y:{ticks:{font:{size:9},color:'#b0a89e'},grid:{color:'#f0ede8'},...(opts.y||{})},...(opts.y2?{y2:{position:'right',ticks:{font:{size:9},color:'#4a90e2'},grid:{drawOnChartArea:false},...opts.y2}}:{})}}});
}

async function runChipAnalysis(){
  const stockId=document.getElementById('chip-stock-id').value.trim();
  const months=parseInt(document.getElementById('chip-range').value);
  if(!stockId){alert('請輸入股票代號！');return;}
  const token=localStorage.getItem('tradelog_finmind')||'';
  if(!token){alert('請先到「個股分析」頁面儲存 FinMind Token！');return;}

  const btn=document.getElementById('chip-btn');
  btn.disabled=true;btn.innerHTML='<i class="ti ti-loader-2" style="animation:spin 1s linear infinite"></i> 分析中...';
  document.getElementById('chip-empty').style.display='none';
  document.getElementById('chip-results').style.display='flex';
  ['chip-inst-content','chip-holder-content','chip-margin-content','chip-turnover-content'].forEach(id=>{const el=document.getElementById(id);if(el) el.innerHTML='<div style="color:var(--t3);font-size:11px;">載入中...</div>';});
  document.getElementById('chip-ai-content').innerHTML='<div style="text-align:center;padding:24px;"><div class="ai-loading-dots"><span></span><span></span><span></span></div><div style="font-size:12px;color:var(--purple);margin-top:10px;">Claude 正在解讀籌碼結構...</div></div>';

  try{
    const{start,end}=getChipDateRange(months);
    const[priceData,instData,marginData,infoData]=await Promise.all([
      chipFetch('TaiwanStockPrice',stockId,start,end),
      chipFetch('TaiwanStockInstitutionalInvestorsBuySell',stockId,start,end),
      chipFetch('TaiwanStockMarginPurchaseShortSale',stockId,start,end),
      chipFetchInfo(stockId)
    ]);

    // ✅ 修正一：dataset 改為 TaiwanStockHoldingSharesPer（需 backer/sponsor 帳號）
    let holderData=[];
    try{holderData=await chipFetch('TaiwanStockHoldingSharesPer',stockId,start,end);}
    catch(e){console.warn('股權分散資料取得失敗（可能需要 backer/sponsor 帳號）:',e.message);}

    // 公司資訊
    const info=infoData[0]||{};
    const capital=parseFloat(info.capital||0);
    // capital 單位：元 / 面額10元/股 / 1000股/張 = 張
    const totalShares=capital>0?Math.round(capital/10/1000):0;
    document.getElementById('chip-company-name').textContent=`${info.stock_name||stockId} (${stockId})`;
    document.getElementById('chip-company-info').textContent=`${info.industry_category||'—'} ／ 資本額 ${fmtN(capital/1e8,1)} 億`;
    document.getElementById('chip-total-shares').textContent=totalShares>0?fmtN(totalShares)+' 張':'—';

    // 股價處理
    const prices=priceData.sort((a,b)=>a.date.localeCompare(b.date));
    const latestP=prices[prices.length-1],prevP=prices[prices.length-2];
    if(latestP){
      const close=parseFloat(latestP.close),prevClose=prevP?parseFloat(prevP.close):close;
      const chg=close-prevClose,chgPct=prevClose>0?chg/prevClose*100:0;
      document.getElementById('chip-price').textContent=`$${close.toFixed(1)}`;
      const chgEl=document.getElementById('chip-price-chg');
      chgEl.textContent=`${chg>=0?'+':''}${chg.toFixed(1)} (${chg>=0?'+':''}${chgPct.toFixed(1)}%)`;
      chgEl.style.color=chg>=0?'var(--up)':'var(--dn)';
    }
    // ✅ Trading_Volume 單位是「股」，除以 1000 轉換為「張」
    const vols5=prices.slice(-5).map(d=>parseInt(d.Trading_Volume||0)/1000);
    const avgVol=vols5.reduce((a,b)=>a+b,0)/Math.max(vols5.length,1);
    document.getElementById('chip-avg-vol').textContent=fmtN(Math.round(avgVol))+' 張';

    const dates=prices.map(d=>d.date);
    const closePrices=prices.map(d=>parseFloat(d.close));
    // ✅ 成交量：股 → 張（÷1000）
    const volumes=prices.map(d=>parseInt(d.Trading_Volume||0)/1000);

    // 三大法人
    const instByDate={};
    instData.forEach(r=>{
      if(!instByDate[r.date]) instByDate[r.date]={f:0,t:0,d:0};
      const net=(parseInt(r.buy||0)-parseInt(r.sell||0))/1000;
      if(r.name==='Foreign_Investor'||r.name==='Foreign_Dealer_Self') instByDate[r.date].f+=net;
      else if(r.name==='Investment_Trust') instByDate[r.date].t+=net;
      else if(r.name==='Dealer_self'||r.name==='Dealer_Hedging') instByDate[r.date].d+=net;
    });
    const foreignArr=dates.map(d=>instByDate[d]?.f||0);
    const trustArr=dates.map(d=>instByDate[d]?.t||0);
    const dealerArr=dates.map(d=>instByDate[d]?.d||0);
    const cumTrust=trustArr.reduce((acc,v,i)=>{acc.push((acc[i-1]||0)+v);return acc;},[]);
    const last5F=foreignArr.slice(-5).reduce((a,b)=>a+b,0);
    const last5T=trustArr.slice(-5).reduce((a,b)=>a+b,0);
    const totalInst=foreignArr.reduce((a,b)=>a+b,0);
    const totalTrust=trustArr.reduce((a,b)=>a+b,0);
    const totalDealer=dealerArr.reduce((a,b)=>a+b,0);
    const instRatios=dates.map((d,i)=>{const vol=volumes[i];if(!vol) return 0;return(Math.abs(foreignArr[i])+Math.abs(trustArr[i])+Math.abs(dealerArr[i]))/vol*100;});
    const avgInstRatio=instRatios.reduce((a,b)=>a+b,0)/Math.max(instRatios.length,1);
    const isHighControl=instRatios.slice(-5).reduce((a,b)=>a+b,0)/5>30;

    document.getElementById('chip-inst-content').innerHTML=`
      <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin-bottom:10px;">
        <div style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">外資累積</div><div style="font-size:14px;font-weight:700;color:${totalInst>=0?'var(--up)':'var(--dn)'};">${totalInst>=0?'+':''}${fmtN(Math.round(totalInst))}張</div></div>
        <div style="background:var(--dn-d);border:1px solid var(--dn-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">投信累積</div><div style="font-size:14px;font-weight:700;color:${totalTrust>=0?'var(--up)':'var(--dn)'};">${totalTrust>=0?'+':''}${fmtN(Math.round(totalTrust))}張</div></div>
        <div style="background:var(--amber-d);border:1px solid var(--amber-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">自營累積</div><div style="font-size:14px;font-weight:700;color:${totalDealer>=0?'var(--up)':'var(--dn)'};">${totalDealer>=0?'+':''}${fmtN(Math.round(totalDealer))}張</div></div>
      </div>
      <div style="font-size:11px;color:var(--t1);line-height:1.8;">近5日：外資 ${last5F>=0?'+':''}${fmtN(Math.round(last5F))} 張 ／ 投信 ${last5T>=0?'+':''}${fmtN(Math.round(last5T))} 張<br>法人平均成交佔比：<b>${avgInstRatio.toFixed(1)}%</b>${isHighControl?' <span style="background:var(--up-d);color:var(--up);border-radius:4px;padding:1px 6px;font-size:9px;">法人高度控盤</span>':''}</div>`;
    makeChipChart('chip-inst-chart','bar',dates.slice(-30),[
      {label:'外資',data:foreignArr.slice(-30),backgroundColor:foreignArr.slice(-30).map(v=>v>=0?'rgba(192,57,43,0.7)':'rgba(26,122,74,0.7)')},
      {label:'投信',data:trustArr.slice(-30),backgroundColor:trustArr.slice(-30).map(v=>v>=0?'rgba(192,57,43,0.4)':'rgba(26,122,74,0.4)')},
    ],{legend:true});

    // ✅ 修正二：股權分散 - TaiwanStockHoldingSharesPer 正確欄位
    // HoldingSharesLevel 1~8 = 散戶(50張以下), 12~15 = 大戶(400張以上)
    // HoldingSharesPercent = 持股佔比%
    const holderByDate={};
    holderData.forEach(r=>{
      if(!holderByDate[r.date]) holderByDate[r.date]={retail:0,large400:0};
      const pct=parseFloat(r.HoldingSharesPercent||r.percent||0);  // ✅ 正確欄位名稱
      const lvl=parseInt(r.HoldingSharesLevel||0);
      if(lvl>=1&&lvl<=8) holderByDate[r.date].retail+=pct;   // 散戶：50張以下
      if(lvl>=12) holderByDate[r.date].large400+=pct;          // 大戶：400張以上
    });
    const holderDates=Object.keys(holderByDate).sort();
    const latestHolder=holderByDate[holderDates[holderDates.length-1]];
    const firstHolder=holderByDate[holderDates[0]];

    let holderHTML='',chipFlowLabel='—',chipFlowColor='var(--t2)';
    if(holderDates.length===0){
      holderHTML=`<div style="background:var(--amber-d);border:1px solid var(--amber-b);border-radius:8px;padding:10px 12px;font-size:11px;color:var(--amber);line-height:1.7;margin-bottom:8px;">⚠️ 股東持股分級表需要 FinMind <b>backer/sponsor</b> 帳號<br>以三大法人動向替代大戶參考：</div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;">
        <div style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">外資累積（大戶替代）</div><div style="font-size:14px;font-weight:700;color:${totalInst>=0?'var(--up)':'var(--dn)'};">${totalInst>=0?'+':''}${fmtN(Math.round(totalInst))} 張</div></div>
        <div style="background:var(--purple-d);border:1px solid var(--purple-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">投信累積（認養估算）</div><div style="font-size:14px;font-weight:700;color:${totalTrust>=0?'var(--up)':'var(--dn)'};">${totalTrust>=0?'+':''}${fmtN(Math.round(totalTrust))} 張</div></div>
      </div>`;
      chipFlowLabel=totalInst>0?'法人增持（替代指標）🟢':totalInst<0?'法人減持（替代指標）🔴':'法人中性 🟡';
      chipFlowColor=totalInst>0?'var(--up)':totalInst<0?'var(--dn)':'var(--amber)';
    }else if(latestHolder&&firstHolder){
      const large400Chg=latestHolder.large400-firstHolder.large400;
      const retailChg=latestHolder.retail-firstHolder.retail;
      if(large400Chg>0&&retailChg<0){chipFlowLabel='籌碼集中（主力吃貨）🟢';chipFlowColor='var(--up)';}
      else if(large400Chg<0&&retailChg>0){chipFlowLabel='籌碼發散（主力出貨）🔴';chipFlowColor='var(--dn)';}
      else{chipFlowLabel='籌碼沉澱震盪 🟡';chipFlowColor='var(--amber)';}
      holderHTML=`<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;"><div style="background:var(--purple-d);border:1px solid var(--purple-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">大戶400張↑持股%</div><div style="font-size:15px;font-weight:700;color:var(--purple);">${latestHolder.large400.toFixed(1)}%</div><div style="font-size:10px;color:${large400Chg>=0?'var(--up)':'var(--dn)'};">${large400Chg>=0?'▲':'▼'} ${Math.abs(large400Chg).toFixed(1)}%</div></div><div style="background:var(--blue-d);border:1px solid var(--blue-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">散戶50張↓持股%</div><div style="font-size:15px;font-weight:700;color:var(--blue);">${latestHolder.retail.toFixed(1)}%</div><div style="font-size:10px;color:${retailChg<=0?'var(--up)':'var(--dn)'};">${retailChg>=0?'▲':'▼'} ${Math.abs(retailChg).toFixed(1)}%</div></div></div><div style="font-weight:600;font-size:12px;color:${chipFlowColor};">${chipFlowLabel}</div>`;
      const large400Series=holderDates.map(d=>holderByDate[d].large400);
      const retailSeries=holderDates.map(d=>holderByDate[d].retail);
      makeChipChart('chip-holder-chart','line',holderDates,[
        {label:'大戶400張↑%',data:large400Series,borderColor:'#9b59b6',backgroundColor:'rgba(155,89,182,0.1)',tension:0.4,fill:true,pointRadius:2},
        {label:'散戶50張↓%',data:retailSeries,borderColor:'#4a90e2',backgroundColor:'rgba(74,144,226,0.1)',tension:0.4,fill:true,pointRadius:2},
      ],{legend:true});
    }
    document.getElementById('chip-holder-content').innerHTML=holderHTML;

    // 融資融券
    const margins=marginData.sort((a,b)=>a.date.localeCompare(b.date));
    const mDates=margins.map(d=>d.date);
    const marginBal=margins.map(d=>parseInt(d.MarginPurchaseTodayBalance||0)/1000);
    const shortBal=margins.map(d=>parseInt(d.ShortSaleTodayBalance||0)/1000);
    const latestM=margins[margins.length-1],prevM=margins[margins.length-2];
    let marginHTML='<div style="color:var(--t2);font-size:11px;">資料載入中...</div>';
    if(latestM){
      const mBal=parseInt(latestM.MarginPurchaseTodayBalance||0)/1000,sBal=parseInt(latestM.ShortSaleTodayBalance||0)/1000;
      const mChg=prevM?mBal-parseInt(prevM.MarginPurchaseTodayBalance||0)/1000:0,sChg=prevM?sBal-parseInt(prevM.ShortSaleTodayBalance||0)/1000:0;
      const snRatio=mBal>0?sBal/mBal*100:0;
      const latestClose=closePrices[closePrices.length-1];
      const ma5c=closePrices.slice(-5).reduce((a,b)=>a+b,0)/5;
      let riskLabel='正常觀察',riskColor='var(--t2)';
      if(snRatio>30&&latestClose>ma5c){riskLabel='⚡ 具備潛在軋空契機';riskColor='var(--up)';}
      else if(mChg>0&&latestClose<closePrices[closePrices.length-10]){riskLabel='⚠️ 散戶盲目承接賣壓';riskColor='var(--dn)';}
      marginHTML=`<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;"><div style="background:var(--up-d);border:1px solid var(--up-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">融資餘額</div><div style="font-size:15px;font-weight:700;color:var(--up);">${fmtN(Math.round(mBal))} 張</div><div style="font-size:10px;color:${mChg>=0?'var(--up)':'var(--dn)'};">${mChg>=0?'▲':'▼'} ${fmtN(Math.abs(Math.round(mChg)))}</div></div><div style="background:var(--dn-d);border:1px solid var(--dn-b);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">融券餘額</div><div style="font-size:15px;font-weight:700;color:var(--dn);">${fmtN(Math.round(sBal))} 張</div><div style="font-size:10px;color:${sChg>=0?'var(--dn)':'var(--up)'};">${sChg>=0?'▲':'▼'} ${fmtN(Math.abs(Math.round(sChg)))}</div></div></div><div style="font-size:11px;margin-bottom:4px;">券資比：<b>${snRatio.toFixed(1)}%</b></div><div style="font-weight:600;font-size:12px;color:${riskColor};">${riskLabel}</div>`;
    }
    document.getElementById('chip-margin-content').innerHTML=marginHTML;
    makeChipChart('chip-margin-chart','line',mDates.slice(-30),[
      {label:'融資(張)',data:marginBal.slice(-30),borderColor:'#c0392b',backgroundColor:'rgba(192,57,43,0.1)',tension:0.4,pointRadius:2,yAxisID:'y'},
      {label:'融券(張)',data:shortBal.slice(-30),borderColor:'#1a7a4a',backgroundColor:'rgba(26,122,74,0.1)',tension:0.4,pointRadius:2,yAxisID:'y2'},
    ],{legend:true,y2:{}});

    // ✅ 周轉率計算（單位確認）
    // volumes[i] = 張（Trading_Volume 已 ÷1000）
    // totalShares = 張（capital / 10 / 1000）
    // 周轉率% = 成交張數 / 發行張數 × 100
    const turnoverRates=dates.map((d,i)=>{if(!totalShares||!volumes[i]) return 0;return volumes[i]/totalShares*100;});
    const trustCumRatio=cumTrust.map(v=>totalShares>0?v/totalShares*100:0);
    const latestTrustRatio=trustCumRatio[trustCumRatio.length-1]||0;
    const totalTurnover=turnoverRates.reduce((a,b)=>a+b,0);
    const avgTurnover5=turnoverRates.slice(-5).reduce((a,b)=>a+b,0)/5;
    const maxClose=Math.max(...closePrices.slice(-60));
    const latestClose2=closePrices[closePrices.length-1];
    let turnoverAlert='',trustAlert='';
    if(latestClose2>=maxClose*0.95&&avgTurnover5>10) turnoverAlert='⚠️ 高檔爆量，籌碼過熱風險';
    else if(avgTurnover5>=1&&avgTurnover5<=5) turnoverAlert='✅ 周轉率溫和，籌碼健康';
    else if(avgTurnover5<1) turnoverAlert='😴 量能低迷，觀察方向';
    else turnoverAlert='⚡ 換手積極，留意方向';
    if(latestTrustRatio<0&&Math.abs(latestTrustRatio)>2) trustAlert='🌟 投信區間新認養黑馬';
    else if(Math.abs(latestTrustRatio)>15) trustAlert='⚠️ 投信持股過高，防季底多殺多';
    else trustAlert=`投信區間估計持股變化：${latestTrustRatio>=0?'+':''}${latestTrustRatio.toFixed(2)}%`;

    document.getElementById('chip-turnover-content').innerHTML=`<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;"><div style="background:var(--bg3);border:1px solid var(--border);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">近5日均周轉率</div><div style="font-size:15px;font-weight:700;color:var(--blue);">${avgTurnover5.toFixed(2)}%</div></div><div style="background:var(--bg3);border:1px solid var(--border);border-radius:8px;padding:8px;text-align:center;"><div style="font-size:9px;color:var(--t2);font-family:var(--mono);">區間累積周轉率</div><div style="font-size:15px;font-weight:700;color:var(--blue);">${totalTurnover.toFixed(1)}%</div></div></div><div style="font-size:11px;margin-bottom:4px;color:var(--t1);">${turnoverAlert}</div><div style="font-size:11px;color:var(--t1);">${trustAlert}</div>`;
    makeChipChart('chip-turnover-chart','bar',dates.slice(-30),[
      {label:'周轉率%',data:turnoverRates.slice(-30),backgroundColor:turnoverRates.slice(-30).map(v=>v>10?'rgba(192,57,43,0.7)':v>=1?'rgba(26,95,212,0.6)':'rgba(176,168,158,0.5)')},
    ],{});

    // Chip Score
    let score=0;const breakdown=[];
    if(last5F>0){score+=2;breakdown.push({label:'外資近5日買超',val:'+2',ok:true});}else breakdown.push({label:'外資近5日賣超',val:'0',ok:false});
    if(last5T>0){score+=2;breakdown.push({label:'投信近5日買超',val:'+2',ok:true});}else breakdown.push({label:'投信近5日未買超',val:'0',ok:false});
    if(latestHolder&&firstHolder&&(latestHolder.large400-firstHolder.large400)>0){score+=2;breakdown.push({label:'大戶持股比例上升',val:'+2',ok:true});}else breakdown.push({label:holderDates.length===0?'大戶資料需升級帳號':'大戶持股未增加',val:'0',ok:false});
    if(latestHolder&&firstHolder&&(latestHolder.retail-firstHolder.retail)<0){score+=1;breakdown.push({label:'散戶持股比例下降',val:'+1',ok:true});}else breakdown.push({label:'散戶持股未減少',val:'0',ok:false});
    if(latestM&&prevM&&parseInt(latestM.MarginPurchaseTodayBalance)<parseInt(prevM.MarginPurchaseTodayBalance)){score+=1;breakdown.push({label:'融資餘額減少',val:'+1',ok:true});}else breakdown.push({label:'融資餘額未減少',val:'0',ok:false});
    if(avgTurnover5>=1&&avgTurnover5<=5){score+=2;breakdown.push({label:'周轉率溫和健康',val:'+2',ok:true});}else breakdown.push({label:'周轉率不在健康區間',val:'0',ok:false});

    document.getElementById('chip-score-num').textContent=score;
    const sp=document.getElementById('chip-score-pointer');if(sp) sp.style.left=`${score/10*100}%`;
    const badge=document.getElementById('chip-score-badge');
    let scoreLabel,scoreCls;
    if(score>=8){scoreLabel=`${score}分 ・ 高度集中 🟢`;scoreCls='bdone';}
    else if(score>=5){scoreLabel=`${score}分 ・ 偏向多方 🟡`;scoreCls='bcurr';}
    else if(score>=3){scoreLabel=`${score}分 ・ 中性偏空 🟠`;scoreCls='bpend';}
    else{scoreLabel=`${score}分 ・ 籌碼渙散 🔴`;scoreCls='btodo';}
    badge.textContent=scoreLabel;badge.className=`badge ${scoreCls}`;
    document.getElementById('chip-score-num').style.color=score>=7?'var(--dn)':score>=5?'var(--amber)':'var(--up)';
    document.getElementById('chip-score-breakdown').innerHTML=breakdown.map(b=>`<div style="display:flex;align-items:center;gap:6px;font-size:11px;"><span style="color:${b.ok?'var(--dn)':'var(--t3)'};font-size:13px;">${b.ok?'●':'○'}</span><span style="flex:1;color:${b.ok?'var(--t0)':'var(--t3)'};">${b.label}</span><span style="font-family:var(--mono);font-weight:700;color:${b.ok?'var(--dn)':'var(--t3)'};">${b.val}</span></div>`).join('');

    // AI 籌碼解讀
    await generateChipAI(stockId,{score,scoreLabel,totalInst:Math.round(totalInst),totalTrust:Math.round(totalTrust),totalDealer:Math.round(totalDealer),last5F:Math.round(last5F),last5T:Math.round(last5T),avgInstRatio:avgInstRatio.toFixed(1),isHighControl,chipFlowLabel:holderDates.length>0?chipFlowLabel:'股權分散資料不可用（需升級帳號）',large400Latest:latestHolder?.large400?.toFixed(1)||'需升級帳號',large400Chg:latestHolder&&firstHolder?(latestHolder.large400-firstHolder.large400).toFixed(1):'—',retailLatest:latestHolder?.retail?.toFixed(1)||'需升級帳號',marginBal:latestM?fmtN(Math.round(parseInt(latestM.MarginPurchaseTodayBalance||0)/1000)):'—',shortBal:latestM?fmtN(Math.round(parseInt(latestM.ShortSaleTodayBalance||0)/1000)):'—',snRatio:latestM&&parseInt(latestM.MarginPurchaseTodayBalance)>0?(parseInt(latestM.ShortSaleTodayBalance)/parseInt(latestM.MarginPurchaseTodayBalance)*100).toFixed(1):'—',avgTurnover5:avgTurnover5.toFixed(2),totalTurnover:totalTurnover.toFixed(1),trustCumRatio:latestTrustRatio.toFixed(2),turnoverAlert,trustAlert,months});

  }catch(err){
    console.error(err);
    document.getElementById('chip-ai-content').innerHTML=`<div style="padding:14px;background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);color:var(--up);font-size:12px;"><b>錯誤：</b>${err.message}</div>`;
    alert('籌碼分析失敗：'+err.message);
  }
  btn.disabled=false;btn.innerHTML='<i class="ti ti-dna"></i>重新分析';
}

async function generateChipAI(stockId,data){
  const apiKey=getApiKey();
  if(!apiKey){document.getElementById('chip-ai-content').innerHTML='<div style="padding:14px;background:var(--amber-d);border:1px solid var(--amber-b);border-radius:var(--r);font-size:12px;color:var(--amber);">請先在「統計 &amp; AI」設定 Anthropic API Key</div>';document.getElementById('chip-ai-badge').textContent='未設定 Key';return;}
  const prompt=`你是一位專精台股籌碼面量化分析的資深操盤手。以下是 ${stockId} 的五階段精煉籌碼指標，請進行深度解讀。

## 籌碼綜合評分
Chip Score：${data.score}/10 分（${data.scoreLabel}）

## 一、三大法人動向（近${data.months}個月）
- 外資累積：${data.totalInst>=0?'+':''}${data.totalInst} 張 ／ 近5日：${data.last5F>=0?'+':''}${data.last5F} 張
- 投信累積：${data.totalTrust>=0?'+':''}${data.totalTrust} 張 ／ 近5日：${data.last5T>=0?'+':''}${data.last5T} 張
- 自營商累積：${data.totalDealer>=0?'+':''}${data.totalDealer} 張
- 法人平均成交佔比：${data.avgInstRatio}%（${data.isHighControl?'法人高度控盤股':'一般水準'}）

## 二、大戶與散戶流向
- 籌碼流向判定：${data.chipFlowLabel}
- 大戶400張↑最新持股：${data.large400Latest}%（區間變化：${data.large400Chg}%）
- 散戶50張↓最新持股：${data.retailLatest}%

## 三、信用交易風險
- 融資餘額：${data.marginBal} 張 ／ 融券餘額：${data.shortBal} 張
- 券資比：${data.snRatio}% ／ 判定：${data.turnoverAlert}

## 四、周轉率與投信認養
- 近5日平均周轉率：${data.avgTurnover5}% ／ 區間累積：${data.totalTurnover}%
- 投信估計持股變化：${data.trustCumRatio}% ／ 判定：${data.trustAlert}

---
請用繁體中文，按以下結構輸出 Markdown 格式報告（不超過600字）：
1. **籌碼結構總評**
2. **主力控盤與意圖剖析**
3. **周轉率換手健康度**
4. **信用交易與潛在爆發點**
5. **後市操盤觀察重點**

注意：僅分析歷史籌碼數據，不提供買賣建議，強調「歷史數據僅供參考」。`;
  try{
    const res=await fetch(CHIP_WORKER,{method:'POST',headers:{'Content-Type':'application/json','x-api-key':apiKey,'anthropic-version':'2023-06-01'},body:JSON.stringify({model:'claude-sonnet-4-5',max_tokens:1500,messages:[{role:'user',content:prompt}]})});
    const json=await res.json();
    if(!res.ok) throw new Error(json.error?.message||'呼叫失敗');
    if(!json.content||!json.content[0]||!json.content[0].text) throw new Error('API 回應格式錯誤');
    const text=json.content[0].text;
    const html=text.replace(/^## (.+)$/gm,'<h3 style="font-size:13px;font-weight:700;margin:14px 0 6px;">$1</h3>').replace(/\*\*(.+?)\*\*/g,'<b>$1</b>').replace(/^- (.+)$/gm,'<li style="margin-left:16px;margin-bottom:3px;">$1</li>').replace(/\n/g,'<br>');
    document.getElementById('chip-ai-content').innerHTML=`<div style="line-height:1.9;">${html}</div>`;
    document.getElementById('chip-ai-badge').textContent='✓ 分析完成';
    document.getElementById('chip-ai-badge').className='badge bdone';
  }catch(err){
    document.getElementById('chip-ai-content').innerHTML=`<div style="padding:14px;background:var(--up-d);border:1px solid var(--up-b);border-radius:var(--r);color:var(--up);font-size:12px;"><b>AI 分析失敗：</b>${err.message}</div>`;
    document.getElementById('chip-ai-badge').textContent='錯誤';
  }
}
</script>
</body>
</html>