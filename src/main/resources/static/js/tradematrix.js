/* ═══════════════════════════════════════════════════════════════
   TradeMatrix — Main JS
   ════════════════════════════════════════════════════════════════ */

/* ── THEME ─────────────────────────────────────────────────────── */

const THEME_KEY = 'tm-theme';

function applyTheme(t) {
  const icon  = document.getElementById('themeIcon');
  const label = document.getElementById('themeLabel');
  if (t === 'light') {
    document.documentElement.classList.add('light');
    if (icon)  icon.textContent  = '☀️';
    if (label) label.textContent = 'Light';
  } else {
    document.documentElement.classList.remove('light');
    if (icon)  icon.textContent  = '🌙';
    if (label) label.textContent = 'Dark';
  }
}

function toggleTheme() {
  const next = document.documentElement.classList.contains('light') ? 'dark' : 'light';
  localStorage.setItem(THEME_KEY, next);
  applyTheme(next);
}

// Apply saved theme immediately (before DOMContentLoaded to avoid flash)
(function () {
  applyTheme(localStorage.getItem(THEME_KEY) || 'dark');
})();

/* ── TAB NAVIGATION ─────────────────────────────────────────────── */

function showTab(id) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item, .bn-item').forEach(n => n.classList.remove('active'));

  const panel = document.getElementById('tab-' + id);
  if (panel) panel.classList.add('active');

  document.querySelectorAll('.nav-item').forEach(n => {
    if (n.getAttribute('onclick')?.includes("'" + id + "'")) n.classList.add('active');
  });

  const bn = document.getElementById('bn-' + id);
  if (bn) {
    bn.classList.add('active');
    bn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
  }

  if (window.innerWidth <= 900) window.scrollTo({ top: 0, behavior: 'smooth' });
}

/* ── MOBILE DRAWER ──────────────────────────────────────────────── */

function toggleDrawer() {
  const open = document.getElementById('sidebarDrawer').classList.contains('open');
  open ? closeDrawer() : openDrawer();
}

function openDrawer() {
  document.getElementById('menuBtn').classList.add('open');
  document.getElementById('sidebarDrawer').classList.add('open');
  document.getElementById('drawerOverlay').classList.add('open');
  document.body.style.overflow = 'hidden';
}

function closeDrawer() {
  document.getElementById('menuBtn').classList.remove('open');
  document.getElementById('sidebarDrawer').classList.remove('open');
  document.getElementById('drawerOverlay').classList.remove('open');
  document.body.style.overflow = '';
}

function showTabMobile(id) {
  closeDrawer();
  showTab(id);
  document.querySelectorAll('#sidebarDrawer .nav-item').forEach(n => {
    n.classList.toggle('active', n.getAttribute('onclick')?.includes("'" + id + "'"));
  });
}

/* ── FORM BUSY STATE ─────────────────────────────────────────────── */

function busy(form) {
  const btn = form.querySelector('.btn-primary');
  if (!btn) return;
  btn.disabled = true;
  const spinner = document.createElement('span');
  spinner.className = 'btn-spinner';
  btn.innerHTML = '';
  btn.appendChild(spinner);
  btn.appendChild(document.createTextNode(' Processing…'));
}

/* ── SYNC ACTIVE NAV ON PAGE LOAD ────────────────────────────────── */

(function () {
  const active = document.querySelector('.tab-panel.active');
  if (!active) return;
  const id = active.id.replace('tab-', '');

  document.querySelectorAll('.nav-item').forEach(n => {
    if (n.getAttribute('onclick')?.includes("'" + id + "'")) n.classList.add('active');
  });

  const bn = document.getElementById('bn-' + id);
  if (bn) {
    bn.classList.add('active');
    setTimeout(() => bn.scrollIntoView({ behavior: 'instant', block: 'nearest', inline: 'center' }), 50);
  }

  document.querySelectorAll('#sidebarDrawer .nav-item').forEach(n => {
    if (n.getAttribute('onclick')?.includes("'" + id + "'")) n.classList.add('active');
  });
})();

/* ── EMA CALCULATOR — LIVE PRICE INSIGHTS ───────────────────────── */

(function () {

  function fmt(v, d) {
    return v == null ? '—' : parseFloat(v).toFixed(d !== undefined ? d : 2);
  }

  function pct(v, base) {
    if (!base || base === 0) return null;
    return ((v - base) / base * 100);
  }

  function insightCell(label, value, colorClass, sub) {
    return '<div class="stat-cell">' +
      '<div class="stat-label">' + label + '</div>' +
      '<div class="stat-value ' + (colorClass || '') + '" style="font-size:13px;">' + value + '</div>' +
      (sub ? '<div class="stat-sub">' + sub + '</div>' : '') +
      '</div>';
  }

  window.updateEmaInsights = function () {
    const price  = parseFloat(document.getElementById('ema-marketPrice')?.value);
    const open   = parseFloat(document.getElementById('ema-open')?.value);
    const close  = parseFloat(document.getElementById('ema-prevClose')?.value);
    const high   = parseFloat(document.getElementById('ema-high')?.value);
    const low    = parseFloat(document.getElementById('ema-low')?.value);
    const w52h   = parseFloat(document.getElementById('ema-52h')?.value);
    const w52l   = parseFloat(document.getElementById('ema-52l')?.value);
    const beta   = parseFloat(document.getElementById('ema-beta')?.value);
    const bidask = parseFloat(document.getElementById('ema-bidask')?.value);

    const panel = document.getElementById('ema-insights-panel');
    const grid  = document.getElementById('ema-insights-grid');
    if (!panel || !grid) return;

    const cells = [];
    let hasAny  = false;

    // Gap from open
    if (!isNaN(price) && !isNaN(open) && open > 0) {
      hasAny = true;
      const gapPct = pct(price, open);
      const gapCol = gapPct >= 0 ? 'green' : 'red';
      const gapLbl = gapPct >= 2  ? '🚀 Strong gap-up'  :
                     gapPct >= 0  ? '↑ Positive'        :
                     gapPct >= -2 ? '↓ Mild gap-down'   : '⚠️ Gap-down';
      cells.push(insightCell('vs Open', (gapPct >= 0 ? '+' : '') + fmt(gapPct) + '%', gapCol, gapLbl));
    }

    // Change from prev close
    if (!isNaN(price) && !isNaN(close) && close > 0) {
      hasAny = true;
      const chgPct = pct(price, close);
      const chgCol = chgPct >= 0 ? 'green' : 'red';
      const chgLbl = Math.abs(chgPct) >= 5 ? 'Strong move' :
                     Math.abs(chgPct) >= 2 ? 'Moderate move' : 'Mild move';
      cells.push(insightCell('vs Prev Close', (chgPct >= 0 ? '+' : '') + fmt(chgPct) + '%', chgCol, chgLbl));
    }

    // Day range position
    if (!isNaN(price) && !isNaN(high) && !isNaN(low) && high > low) {
      hasAny = true;
      const rangePct = ((price - low) / (high - low)) * 100;
      const rangeCol = rangePct >= 70 ? 'green' : rangePct >= 40 ? 'accent' : 'red';
      const rangeLbl = rangePct >= 80 ? '🔝 Near day high' :
                       rangePct >= 50 ? '↑ Upper half'     :
                       rangePct >= 30 ? '↔ Mid range'      : '🔻 Near day low';
      cells.push(insightCell('Day Range %', fmt(rangePct) + '%', rangeCol, rangeLbl));

      // Update range bar
      const barWrap  = document.getElementById('ema-range-bar-wrap');
      const fill     = document.getElementById('ema-range-fill');
      const marker   = document.getElementById('ema-range-marker');
      const rbPct    = document.getElementById('ema-rb-pct');
      const rbLow    = document.getElementById('ema-rb-pct-low');
      const rbHigh   = document.getElementById('ema-rb-pct-high');
      const rbLowLbl = document.getElementById('ema-rb-low');
      const rbHiLbl  = document.getElementById('ema-rb-high');
      if (barWrap) {
        barWrap.style.display = 'block';
        const pctClamped = Math.min(100, Math.max(0, rangePct));
        fill.style.width  = pctClamped + '%';
        marker.style.left = 'calc(' + pctClamped + '% - 6px)';
        if (rbPct)    rbPct.textContent    = fmt(rangePct) + '%';
        if (rbLow)    rbLow.textContent    = fmt(low, 4);
        if (rbHigh)   rbHigh.textContent   = fmt(high, 4);
        if (rbLowLbl) rbLowLbl.textContent = 'Low ' + fmt(low, 4);
        if (rbHiLbl)  rbHiLbl.textContent  = 'High ' + fmt(high, 4);
      }
    }

    // 52-week position
    if (!isNaN(price) && !isNaN(w52h) && !isNaN(w52l) && w52h > w52l) {
      hasAny = true;
      const fromHigh = pct(price, w52h);
      const fromLow  = pct(price, w52l);
      const highCol  = fromHigh >= -5  ? 'green'  : fromHigh >= -20 ? 'accent' : 'red';
      const lowCol   = fromLow  >= 50  ? 'green'  : fromLow  >= 20  ? 'accent' : 'yellow';
      cells.push(insightCell('From 52wk High',
        (fromHigh >= 0 ? '+' : '') + fmt(fromHigh) + '%', highCol,
        fromHigh >= -5  ? '🔝 Near 52wk high' :
        fromHigh >= -20 ? 'Mid recovery'       : '📉 Far from high'));
      cells.push(insightCell('From 52wk Low',
        '+' + fmt(fromLow) + '%', lowCol,
        fromLow >= 100 ? '🚀 Doubled from low' :
        fromLow >= 50  ? 'Strong recovery'     : '⚠️ Still near low'));
    }

    // Beta
    if (!isNaN(beta)) {
      hasAny = true;
      const betaCol = beta > 1.5 ? 'red' : beta > 1.0 ? 'yellow' : beta > 0.5 ? 'accent' : 'green';
      const betaLbl = beta > 2.0 ? '🔴 Very high vol'         :
                      beta > 1.5 ? '🟡 High vol — reduce size' :
                      beta > 1.0 ? '⚠️ Above market'           :
                      beta > 0.5 ? '✅ Normal'                  : '🟢 Low vol';
      cells.push(insightCell('Beta', fmt(beta, 3), betaCol, betaLbl));
    }

    // Bid/Ask demand pressure
    if (!isNaN(bidask)) {
      hasAny = true;
      const baCol  = bidask >= 60 ? 'green' : bidask >= 40 ? 'accent' : 'red';
      const baLbl  = bidask >= 70 ? '💪 Strong demand'      :
                     bidask >= 50 ? '🟢 Buyers in control'  :
                     bidask >= 40 ? '↔ Balanced'            : '🔴 Sellers dominating';
      cells.push(insightCell('Bid/Ask Ratio', fmt(bidask, 1) + '%', baCol, baLbl));
    }

    if (hasAny) {
      panel.style.display = 'block';
      grid.innerHTML = cells.join('');
    } else {
      panel.style.display = 'none';
    }
  };

  window.clearEmaInsights = function () {
    const panel   = document.getElementById('ema-insights-panel');
    const barWrap = document.getElementById('ema-range-bar-wrap');
    if (panel)   panel.style.display   = 'none';
    if (barWrap) barWrap.style.display = 'none';
  };

  // Re-trigger on load if Thymeleaf has repopulated values after a POST
  document.addEventListener('DOMContentLoaded', function () {
    updateEmaInsights();
  });

})();