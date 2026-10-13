// Injected at document start into https://fantasy.basketnews.com only
// (WebViewCompat.addDocumentStartJavaScript). Port of the extension's sniffer.js
// and the rendering half of bridge.js:
//  1) watches the site's own lineup request and passes a copy of the response to
//     the app (never changes the request or the response), and
//  2) shows a red "Live points" tile next to "Round points" with values the app
//     pushes in through window.__elfSetState(state).
(() => {
  if (window.__elfInstalled || window.top !== window) return;
  window.__elfInstalled = true;

  const OP = 'fantasyTeamLineupViewRecordFromClient';
  const RED = '#ff4d4f';
  const UNSUPPORTED = 'This game mode is not supported yet';
  const startedAt = Date.now();
  const otherLeague = () => /\/(lkl|bcl)(\/|$)/i.test(window.location.pathname);
  let current = { teamId: null, round: null }; // team currently shown on the page
  let state = null; // live totals pushed by the app
  let pending = null;

  function send(msg) {
    try {
      if (window.elfNative) window.elfNative.postMessage(JSON.stringify(msg));
    } catch (e) {
      /* never break the site */
    }
  }

  // ---------- sniffer ----------

  const origFetch = window.fetch;
  window.fetch = async function (input, init) {
    const res = await origFetch.apply(this, arguments);
    try {
      const url = typeof input === 'string' ? input : input && input.url;
      if (url && String(url).includes('/backend/graphql')) {
        let body = init && init.body;
        if (body == null && typeof Request !== 'undefined' && input instanceof Request) {
          body = await input.clone().text();
        }
        if (typeof body === 'string' && body.includes(OP)) {
          let req = JSON.parse(body);
          if (Array.isArray(req)) req = req.find((r) => r && r.operationName === OP) || req[0];
          const v = (req && req.variables) || {};
          if (typeof v.fantasyTeamId === 'string') {
            current = { teamId: v.fantasyTeamId, round: Number(v.fantasyRound) || null };
            schedule();
          }
          if (!otherLeague()) {
            res
              .clone()
              .json()
              .then((json) => {
                const data = Array.isArray(json) ? json[0] && json[0].data : json && json.data;
                send({ type: 'lineup', variables: v, data, path: window.location.pathname });
              })
              .catch(() => {});
          }
        }
      }
    } catch (e) {
      /* never break the site */
    }
    return res;
  };

  // ---------- overlay ----------

  window.__elfSetState = (s) => {
    state = s || null;
    schedule();
  };

  const fmt = (n) => (Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100));

  function pickTeam() {
    if (otherLeague() || !state || !Array.isArray(state.teams)) return null;
    let team = null;
    if (current.teamId) {
      team = state.teams.find((t) => t.id === current.teamId) || null;
    } else {
      const h1 = document.querySelector('h1');
      const name = h1 && h1.textContent.trim();
      if (name) team = state.teams.find((t) => t.name === name) || null;
    }
    if (!team) return null;
    // Looking at an older round? Live points only make sense for the current one.
    if (current.round && team.fantasyRound && current.round !== team.fantasyRound) return null;
    return team;
  }

  // The "Round points" stat tile (siblings: Capital gain, Total points, ...).
  function findTile() {
    const label = [...document.querySelectorAll('div,span,p')].find(
      (e) => e.children.length === 0 && !e.closest('[data-elf-live]') && /^round points$/i.test(e.textContent.trim())
    );
    if (!label) return null;
    let node = label;
    while (node.parentElement && node.parentElement !== document.body && node.parentElement.children.length < 3) {
      node = node.parentElement;
    }
    const row = node.parentElement;
    if (!row || !/total points/i.test(row.textContent)) return null;
    return node;
  }

  const leaves = (box) =>
    [...box.querySelectorAll('*')].filter((e) => e.children.length === 0 && e.textContent.trim());

  function buildFromTile(tile) {
    const box = tile.cloneNode(true);
    box.setAttribute('data-elf-live', '1');
    box.removeAttribute('id');
    box.querySelectorAll('[id]').forEach((e) => e.removeAttribute('id'));
    return box;
  }

  const valueOf = (team) => (team.supported === false ? 'n/a' : fmt(team.total || 0));
  const labelOf = (team) => (team.live ? '● Live points' : 'Live points');

  function update(box, team) {
    const [label, value] = leaves(box);
    if (!label || !value) return;
    const labelText = labelOf(team);
    const valueText = valueOf(team);
    if (label.textContent !== labelText) label.textContent = labelText;
    if (value.textContent !== valueText) value.textContent = valueText;
    value.style.setProperty('color', RED, 'important');
    label.style.setProperty('color', RED, 'important');
    const when = state && state.updated ? new Date(state.updated).toLocaleTimeString() : '';
    box.title =
      team.supported === false
        ? UNSUPPORTED
        : `Calculated from live EuroLeague box scores (updated ${when}). BasketNews updates its own numbers after the games.`;
  }

  function floating(team) {
    let f = document.getElementById('elf-floating');
    if (!f) {
      f = document.createElement('div');
      f.id = 'elf-floating';
      f.style.cssText =
        'position:fixed;top:12px;right:12px;z-index:2147483647;background:#111;border:1px solid ' +
        RED + ';border-radius:10px;padding:6px 10px;font:600 13px system-ui,sans-serif;color:' + RED +
        ';pointer-events:none;';
      document.documentElement.append(f);
    }
    const text = team.supported === false ? UNSUPPORTED : `${team.live ? '● ' : ''}Live points ${valueOf(team)}`;
    if (f.textContent !== text) f.textContent = text;
  }

  function removeFloating() {
    const f = document.getElementById('elf-floating');
    if (f) f.remove();
  }

  function render() {
    pending = null;
    const team = pickTeam();
    const existing = document.querySelector('[data-elf-live]');

    if (!team) {
      if (existing) existing.remove();
      removeFloating();
      return;
    }

    const tile = findTile();
    if (tile) {
      removeFloating();
      let box = existing;
      if (!box || box.previousElementSibling !== tile) {
        if (box) box.remove();
        box = buildFromTile(tile);
        tile.after(box);
      }
      update(box, team);
    } else {
      if (existing) existing.remove();
      // Page layout not recognised (or still loading): fall back to a corner badge.
      if (Date.now() - startedAt > 6000) floating(team);
    }
  }

  function schedule() {
    if (pending) return;
    pending = setTimeout(render, 150);
  }

  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(schedule, 6500);
  // Ask the app for the latest state (it also pushes after every update).
  send({ type: 'ready' });
})();
