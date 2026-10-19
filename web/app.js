/* Nowish web — same data as the Android app, synced through a private GitHub repo. */
(() => {
  "use strict";

  // ---------- storage ----------
  const LS = {
    get(key, fallback) {
      try { const v = localStorage.getItem("nowish." + key); return v == null ? fallback : JSON.parse(v); }
      catch { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem("nowish." + key, JSON.stringify(value)); } catch { /* private mode */ }
    },
  };

  const settings = Object.assign(
    { repo: "GitJotsarup/nowish-data", token: "", localOnly: false, sound: true, seed: "#6C5CE7", theme: "auto" },
    LS.get("settings", {}),
  );
  const saveSettings = () => LS.set("settings", settings);

  let data = LS.get("data", { tasks: [], lists: [] });
  const saveData = () => LS.set("data", data);

  const ui = Object.assign({ space: "PERSONAL", list: null, showDone: false }, LS.get("ui", {}));
  const saveUi = () => LS.set("ui", { space: ui.space, list: ui.list, showDone: ui.showDone });

  let editingId = null;
  let popover = null; // { id, x, y }
  let dialog = null; // "settings" | { list: "new" } | { list: id } | { deleteList: id }
  let addState = { date: "today", time: "", space: "WORK" };

  // ---------- helpers ----------
  const $ = (sel, root = document) => root.querySelector(sel);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const now = () => Date.now();
  const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : "w" + Math.random().toString(36).slice(2) + now().toString(36));
  const pad = (n) => String(n).padStart(2, "0");
  const isoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const todayStr = () => isoDate(new Date());
  const addDays = (iso, n) => { const [y, m, d] = iso.split("-").map(Number); return isoDate(new Date(y, m - 1, d + n)); };
  const parseIso = (iso) => { const [y, m, d] = iso.split("-").map(Number); return new Date(y, m - 1, d); };
  const SPACES = { WORK: "Work", PERSONAL: "Personal" };
  const otherSpace = (s) => (s === "WORK" ? "PERSONAL" : "WORK");

  function friendlyDate(iso) {
    const t = todayStr();
    if (iso === t) return "Today";
    if (iso === addDays(t, 1)) return "Tomorrow";
    if (iso === addDays(t, -1)) return "Yesterday";
    const d = parseIso(iso);
    const opts = { weekday: "short", month: "short", day: "numeric" };
    if (d.getFullYear() !== new Date().getFullYear()) { delete opts.weekday; opts.year = "numeric"; }
    return d.toLocaleDateString(undefined, opts);
  }
  function friendlyTime(hhmm) {
    const [h, m] = hhmm.split(":").map(Number);
    return new Date(2000, 0, 1, h, m).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  }
  function greeting() {
    const h = new Date().getHours();
    if (h < 5) return "Up late";
    if (h < 12) return "Good morning";
    if (h < 17) return "Good afternoon";
    if (h < 22) return "Good evening";
    return "Good night";
  }
  const cheers = ["Nice! One less thing ✨", "Crushed it 💪", "Done and dusted 🎉", "Look at you go 🚀", "Cleared! ✅", "That felt good 😌", "Off the list 🙌"];

  // ---------- data ops (every write stamps updatedAt so sync can merge) ----------
  const liveTasks = () => data.tasks.filter((t) => !t.deleted);
  const liveLists = () => data.lists.filter((l) => !l.deleted);
  const findTask = (id) => data.tasks.find((t) => t.id === id);
  const findList = (id) => data.lists.find((l) => l.id === id && !l.deleted);

  function touch() { saveData(); render(); scheduleSync(); }

  function addTask(title, space, list, due, time) {
    data.tasks.push({
      id: uuid(), title: title.trim(), space, list: list || null,
      due: due || null, time: due && time ? time : null,
      done: false, completedAt: null, createdAt: now(), updatedAt: now(), deleted: false,
    });
    touch();
  }
  function updateTask(id, patch) {
    const t = findTask(id);
    if (!t) return;
    Object.assign(t, patch, { updatedAt: now() });
    if (!t.due) t.time = null;
    touch();
  }
  function setDone(id, done) {
    updateTask(id, { done, completedAt: done ? now() : null });
  }

  // ---------- sections ----------
  function sectionsFor(space, listId) {
    const t0 = todayStr(), t1 = addDays(t0, 1);
    const inView = liveTasks().filter((t) =>
      listId ? t.list === listId : t.space === space && (!t.list || t.due));
    const open = inView.filter((t) => !t.done);
    const kind = (t) => !t.due ? "LATER" : t.due < t0 ? "OVERDUE" : t.due === t0 ? "TODAY" : t.due === t1 ? "TOMORROW" : "UPCOMING";
    const names = { OVERDUE: "Overdue", TODAY: "Today", TOMORROW: "Tomorrow", UPCOMING: "Upcoming", LATER: listId ? "Anytime" : "Later" };
    const byTime = (a, b) => (a.due || "").localeCompare(b.due || "") || (a.time || "99").localeCompare(b.time || "99") || a.createdAt - b.createdAt;
    const sections = ["OVERDUE", "TODAY", "TOMORROW", "UPCOMING", "LATER"].map((k) => ({
      kind: k, title: names[k],
      tasks: open.filter((t) => kind(t) === k).sort(k === "LATER" ? (a, b) => a.createdAt - b.createdAt : byTime),
    })).filter((s) => s.tasks.length);
    const done = inView.filter((t) => t.done).sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0));
    return { sections, done };
  }

  // ---------- rendering ----------
  const app = $("#app");

  function render() {
    applyTheme();
    if (!settings.token && !settings.localOnly) { renderSetup(); return; }
    if (ui.list && !findList(ui.list)) ui.list = null;

    const list = ui.list ? findList(ui.list) : null;
    const startOfDay = parseIso(todayStr()).getTime();
    const cleared = liveTasks().filter((t) => t.done && (t.completedAt || 0) >= startOfDay && (ui.space === "BOTH" || t.space === ui.space)).length;
    const title = list ? `${esc(list.emoji)} ${esc(list.name)}` : ui.space === "BOTH" ? "Everything" : SPACES[ui.space];
    const scrollY = window.scrollY;
    const focusedAdd = document.activeElement && document.activeElement.id === "add-title";
    const addValue = $("#add-title") ? $("#add-title").value : "";

    let body;
    if (list) body = columnHtml(null, list.id, false);
    else if (ui.space === "BOTH") body = `<div class="columns two">${columnHtml("WORK", null, true)}${columnHtml("PERSONAL", null, true)}</div>`;
    else body = columnHtml(ui.space, null, false);

    app.innerHTML = `
      <div class="shell">
        ${sidebarHtml()}
        <main>
          <div class="head-sub">${greeting()} · ${new Date().toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" })}</div>
          <div class="head-title">
            <button class="icon-btn menu-btn" data-act="menu" title="Menu">☰</button>
            <span>${title}</span>
            ${list ? `<button class="icon-btn" data-act="edit-list" data-id="${list.id}" title="Edit list">✎</button>
                      <button class="icon-btn danger" data-act="ask-delete-list" data-id="${list.id}" title="Delete list">🗑</button>` : ""}
          </div>
          <div class="cleared">${cleared ? `✨ ${cleared} cleared today` : ""}</div>
          ${addHtml(list)}
          ${body}
        </main>
      </div>
      ${popoverHtml()}
      ${dialogHtml()}`;

    const input = $("#add-title");
    if (input) {
      input.value = addValue;
      $("#add-send").disabled = !addValue.trim();
      if (focusedAdd) { input.focus(); input.setSelectionRange(addValue.length, addValue.length); }
    }
    const editor = $(".title-input");
    if (editor) { editor.focus(); editor.setSelectionRange(editor.value.length, editor.value.length); }
    positionPopover();
    window.scrollTo(0, scrollY);
  }

  function sidebarHtml() {
    const counts = (space) => liveTasks().filter((t) => !t.done && t.space === space && t.due && t.due <= todayStr()).length;
    const item = (act, attrs, emoji, label, active, count) =>
      `<button class="nav-item ${active ? "active" : ""}" data-act="${act}" ${attrs}><span class="emoji">${emoji}</span>${label}${count ? `<span class="count">${count}</span>` : ""}</button>`;
    const spaces = ui.space === "BOTH" ? ["WORK", "PERSONAL"] : [ui.space];
    const listsHtml = spaces.map((s) => {
      const ls = liveLists().filter((l) => l.space === s).sort((a, b) => a.createdAt - b.createdAt);
      return `<div class="nav-label">${ui.space === "BOTH" ? SPACES[s] + " lists" : "Lists"}</div>` +
        ls.map((l) => item("open-list", `data-id="${l.id}"`, esc(l.emoji), esc(l.name), ui.list === l.id,
          liveTasks().filter((t) => t.list === l.id && !t.done).length)).join("") +
        item("new-list", `data-space="${s}"`, "＋", "New list", false, 0);
    }).join("");
    const st = syncStatus();
    return `
      <aside class="sidebar" id="sidebar">
        <div class="brand"><img src="icon.svg" alt="">Nowish</div>
        ${item("space", 'data-space="WORK"', "💼", "Work", !ui.list && ui.space === "WORK", counts("WORK"))}
        ${item("space", 'data-space="PERSONAL"', "🏠", "Personal", !ui.list && ui.space === "PERSONAL", counts("PERSONAL"))}
        ${item("space", 'data-space="BOTH"', "🌗", "Both, side by side", !ui.list && ui.space === "BOTH", 0)}
        ${listsHtml}
        <div class="sidebar-foot">
          <div class="sync-pill"><span class="dot ${st.cls}"></span>${esc(st.text)}</div>
          ${item("settings", "", "⚙️", "Settings", false, 0)}
          <div class="kbd" style="padding:4px 14px"><kbd>N</kbd> new task · <kbd>Esc</kbd> close</div>
        </div>
      </aside>`;
  }

  function addHtml(list) {
    const t0 = todayStr();
    const d = addState.date;
    const custom = d && d !== "today" && d !== "tomorrow" && d !== "later" ? d : "";
    const spaceChips = !list && ui.space === "BOTH"
      ? `<span style="width:8px"></span>` + ["WORK", "PERSONAL"].map((s) =>
        `<button class="chip ${addState.space === s ? "on" : ""}" data-act="add-space" data-space="${s}">${s === "WORK" ? "💼" : "🏠"} ${SPACES[s]}</button>`).join("")
      : "";
    return `
      <div class="add">
        <div class="add-row">
          <input id="add-title" class="title" placeholder="${list ? `Add to ${esc(list.name)}…` : "What needs doing?"}" autocomplete="off">
          <button id="add-send" class="send" data-act="add" title="Add (Enter)" disabled>↑</button>
        </div>
        <div class="add-chips">
          <button class="chip ${d === "today" ? "on" : ""}" data-act="add-date" data-v="today">☀️ Today</button>
          <button class="chip ${d === "tomorrow" ? "on" : ""}" data-act="add-date" data-v="tomorrow">🌙 Tomorrow</button>
          <label class="chip ${custom ? "on" : ""}">📅 <input type="date" id="add-date" value="${custom}" min="${t0}"></label>
          <button class="chip ${d === "later" ? "on" : ""}" data-act="add-date" data-v="later">📥 Later</button>
          <label class="chip ${addState.time ? "on" : ""}">⏰ <input type="time" id="add-time" value="${addState.time}"></label>
          ${spaceChips}
        </div>
      </div>`;
  }

  function columnHtml(space, listId, showTitle) {
    const { sections, done } = sectionsFor(space, listId);
    const onlyAnytime = listId && sections.length === 1 && sections[0].kind === "LATER";
    let html = showTitle ? `<div><div class="col-title">${space === "WORK" ? "💼" : "🏠"} ${SPACES[space]}</div>` : "<div>";
    if (!sections.length) {
      html += `<div class="empty"><div class="big">${listId ? "🌱" : "🌤️"}</div><h2>${listId ? "Nothing here yet" : "All clear"}</h2>
        <p>${listId ? "Add the first thing above." : "Nothing on your plate. Enjoy it ✨"}</p></div>`;
    }
    for (const s of sections) {
      if (!onlyAnytime) html += `<div class="section-head s-${s.kind}">${s.title}<span class="badge">${s.tasks.length}</span></div>`;
      html += s.tasks.map((t) => taskHtml(t, s.kind, !listId)).join("");
    }
    if (done.length) {
      html += `<div class="done-head"><button class="link" data-act="toggle-done">Completed · ${done.length} ${ui.showDone ? "▴" : "▾"}</button>
        ${ui.showDone ? `<button class="link clear" data-act="clear-done" data-space="${space || ""}" data-list="${listId || ""}">Clear all</button>` : ""}</div>`;
      if (ui.showDone) {
        html += done.slice(0, 100).map((t) => `
          <div class="task done" data-id="${t.id}">
            <button class="check checked" data-act="undone" title="Mark not done">✓</button>
            <div class="body"><div class="title-text">${esc(t.title)}</div></div>
            <div class="row-actions" style="opacity:1"><button class="icon-btn danger" data-act="delete" title="Delete">🗑</button></div>
          </div>`).join("");
      }
    }
    return html + "</div>";
  }

  function taskHtml(t, kind, showList) {
    const overdue = kind === "OVERDUE";
    const pills = [];
    if (t.due && (kind === "OVERDUE" || kind === "UPCOMING")) pills.push(`<span class="pill ${overdue ? "overdue" : ""}">📅 ${friendlyDate(t.due)}</span>`);
    if (t.time) pills.push(`<span class="pill ${overdue ? "overdue" : ""}">⏰ ${friendlyTime(t.time)}</span>`);
    const list = showList && t.list ? findList(t.list) : null;
    if (list) pills.push(`<span class="pill list">${esc(list.emoji)} ${esc(list.name)}</span>`);
    const editing = editingId === t.id;
    return `
      <div class="task" data-id="${t.id}">
        <button class="check ${overdue ? "overdue" : ""}" data-act="done" title="Mark done">✓</button>
        <div class="body" data-act="edit">
          ${editing ? `<input class="title-input" value="${esc(t.title)}" data-id="${t.id}">` : `<div class="title-text">${esc(t.title)}</div>`}
          ${pills.length ? `<div class="meta">${pills.join("")}</div>` : ""}
        </div>
        <div class="row-actions">
          <button class="icon-btn" data-act="options" title="Date, reminder, list…">⋯</button>
          <button class="icon-btn danger" data-act="delete" title="Delete">🗑</button>
        </div>
      </div>`;
  }

  function popoverHtml() {
    if (!popover) return "";
    const t = findTask(popover.id);
    if (!t || t.deleted) { popover = null; return ""; }
    const t0 = todayStr(), t1 = addDays(t0, 1);
    const custom = t.due && t.due !== t0 && t.due !== t1 ? t.due : "";
    const lists = liveLists().filter((l) => l.space === t.space);
    return `
      <div class="popover" id="popover">
        <h3>${esc(t.title)}</h3>
        <div class="pop-label">When</div>
        <div class="pop-row">
          <button class="chip small ${t.due === t0 ? "on" : ""}" data-act="set-due" data-v="${t0}">☀️ Today</button>
          <button class="chip small ${t.due === t1 ? "on" : ""}" data-act="set-due" data-v="${t1}">🌙 Tomorrow</button>
          <label class="chip small ${custom ? "on" : ""}">📅 <input type="date" id="pop-date" value="${custom}"></label>
          <button class="chip small ${!t.due ? "on" : ""}" data-act="set-due" data-v="">📥 Later</button>
        </div>
        <div class="pop-label">Reminder</div>
        <div class="pop-row">
          <label class="chip small ${t.time ? "on" : ""}">⏰ <input type="time" id="pop-time" value="${t.time || ""}"></label>
          ${t.time ? `<button class="chip small" data-act="clear-time">✕ Remove</button>` : ""}
        </div>
        <div class="pop-label">List</div>
        <select class="field" id="pop-list">
          <option value="">None</option>
          ${lists.map((l) => `<option value="${l.id}" ${t.list === l.id ? "selected" : ""}>${esc(l.emoji)} ${esc(l.name)}</option>`).join("")}
        </select>
        <div class="pop-actions">
          <button class="btn green" data-act="pop-done">✓ Done</button>
          <button class="btn outline" data-act="move-space">To ${SPACES[otherSpace(t.space)]} →</button>
          <button class="btn danger" data-act="pop-delete">Delete</button>
        </div>
      </div>`;
  }

  function positionPopover() {
    const el = $("#popover");
    if (!el || !popover) return;
    const r = el.getBoundingClientRect();
    let x = Math.min(popover.x, window.innerWidth - r.width - 12);
    let y = popover.y + 8;
    if (y + r.height > window.innerHeight - 12) y = Math.max(12, popover.y - r.height - 8);
    el.style.left = Math.max(12, x) + "px";
    el.style.top = y + "px";
  }

  const EMOJIS = ["📝", "🎬", "📚", "🛒", "✈️", "🎁", "💡", "🎮", "🎵", "🍿", "🏋️", "🍳", "💼", "🏠", "❤️", "⭐"];

  function dialogHtml() {
    if (!dialog) return "";
    if (dialog === "settings") {
      const seeds = ["#6C5CE7", "#0B8F7A", "#2F6FDE", "#D9467A", "#E8742A", "#7A8B2E", "#5A5A72"];
      return `
        <div class="scrim" data-act="close-dialog"><div class="dialog" data-stop>
          <h2>Settings</h2>
          <label>Data repo</label>
          <input class="field" id="set-repo" value="${esc(settings.repo)}" placeholder="owner/name">
          <label>Access token</label>
          <input class="field" id="set-token" type="password" value="${esc(settings.token)}" placeholder="github_pat_…">
          <p style="font-size:13px">Stored only in this browser. <a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noopener">Create a token</a> with access to just that repo and Contents: Read and write.</p>
          <label>Accent color</label>
          <div class="swatches">${seeds.map((c) => `<button class="swatch ${settings.seed === c ? "on" : ""}" style="background:${c}" data-act="seed" data-v="${c}" title="${c}"></button>`).join("")}</div>
          <label>Theme</label>
          <div class="pop-row">${["auto", "light", "dark"].map((t) => `<button class="chip small ${settings.theme === t ? "on" : ""}" data-act="theme" data-v="${t}">${t[0].toUpperCase() + t.slice(1)}</button>`).join("")}</div>
          <div class="switch-row" style="margin-top:12px"><span>Success sound</span><button class="chip small ${settings.sound ? "on" : ""}" data-act="toggle-sound">${settings.sound ? "On" : "Off"}</button></div>
          <div class="switch-row"><span>Reminders on this computer</span>
            <button class="chip small ${notifyGranted() ? "on" : ""}" data-act="notify">${notifyGranted() ? "On" : "Turn on"}</button></div>
          <div class="err-text">${esc(syncError || "")}</div>
          <div class="actions">
            <button class="btn text" data-act="close-dialog">Close</button>
            <button class="btn" data-act="save-settings">Save</button>
          </div>
        </div></div>`;
    }
    if (dialog.list) {
      const existing = dialog.list === "new" ? null : findList(dialog.list);
      const emoji = dialog.emoji || existing?.emoji || EMOJIS[0];
      return `
        <div class="scrim" data-act="close-dialog"><div class="dialog" data-stop>
          <h2>${existing ? "Edit list" : `New ${SPACES[dialog.space || ui.space] || ""} list`}</h2>
          <label>Name</label>
          <input class="field" id="list-name" value="${esc(dialog.name ?? existing?.name ?? "")}" placeholder="To watch">
          <label>Icon</label>
          <div class="emoji-grid">${EMOJIS.map((e) => `<button class="${e === emoji ? "on" : ""}" data-act="pick-emoji" data-v="${e}">${e}</button>`).join("")}</div>
          <div class="actions">
            <button class="btn text" data-act="close-dialog">Cancel</button>
            <button class="btn" data-act="save-list">${existing ? "Save" : "Create"}</button>
          </div>
        </div></div>`;
    }
    if (dialog.deleteList) {
      const l = findList(dialog.deleteList);
      return `
        <div class="scrim" data-act="close-dialog"><div class="dialog" data-stop>
          <h2>Delete ${esc(l?.emoji)} ${esc(l?.name)}?</h2>
          <p>All tasks in this list will be deleted too.</p>
          <div class="actions">
            <button class="btn text" data-act="close-dialog">Cancel</button>
            <button class="btn" style="background:var(--error)" data-act="delete-list">Delete</button>
          </div>
        </div></div>`;
    }
    return "";
  }

  function renderSetup() {
    app.innerHTML = `
      <div class="setup"><div class="dialog">
        <img class="logo" src="icon.svg" alt="">
        <h2>Connect Nowish</h2>
        <p>Your tasks live in a private GitHub repo that your phone syncs to. Connect this browser to see and edit them here.</p>
        <ol class="steps">
          <li><a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noopener">Create a fine-grained token</a>: pick <b>Only select repositories → nowish-data</b>, then <b>Repository permissions → Contents: Read and write</b>.</li>
          <li>Paste it below (and in the phone app under ☰ → Sync with laptop).</li>
        </ol>
        <label>Data repo</label>
        <input class="field" id="setup-repo" value="${esc(settings.repo)}">
        <label>Access token</label>
        <input class="field" id="setup-token" type="password" placeholder="github_pat_…">
        <div class="err-text" id="setup-err">${esc(syncError || "")}</div>
        <div class="actions">
          <button class="btn text" data-act="local-only">Use without sync</button>
          <button class="btn" data-act="connect">Connect</button>
        </div>
      </div></div>`;
  }

  function applyTheme() {
    document.documentElement.style.setProperty("--seed", settings.seed);
    if (settings.theme === "auto") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", settings.theme);
  }

  // ---------- celebration ----------
  const chime = new Audio("success.wav");
  function celebrate(el) {
    if (settings.sound) { try { chime.currentTime = 0; chime.play().catch(() => {}); } catch { /* ignore */ } }
    if (window.confetti) {
      const r = el ? el.getBoundingClientRect() : { left: innerWidth / 2, top: innerHeight / 3, width: 0, height: 0 };
      const seed = getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
      window.confetti({
        particleCount: 70, spread: 75, startVelocity: 32, scalar: 0.9, ticks: 160,
        origin: { x: (r.left + r.width / 2) / innerWidth, y: (r.top + r.height / 2) / innerHeight },
        colors: [settings.seed, "#FFC94D", "#FF6B9A", "#5ED6A0", "#6CB8FF", seed || "#fff"],
      });
    }
  }

  let toastTimer = null;
  function toast(message, undo) {
    const el = $("#toast");
    el.innerHTML = `<span>${esc(message)}</span>${undo ? '<button id="toast-undo">Undo</button>' : ""}`;
    el.hidden = false;
    if (undo) $("#toast-undo").onclick = () => { undo(); el.hidden = true; };
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (el.hidden = true), 5000);
  }

  function completeTask(id, rowEl) {
    const before = { ...findTask(id) };
    const check = rowEl && rowEl.querySelector(".check");
    if (check) check.classList.add("checked");
    celebrate(check || rowEl);
    const finish = () => {
      setDone(id, true);
      toast(cheers[Math.floor(Math.random() * cheers.length)], () => updateTask(id, { done: false, completedAt: null }));
    };
    if (rowEl) { rowEl.classList.add("leaving"); setTimeout(finish, 260); } else finish();
    return before;
  }

  function deleteTask(id, rowEl) {
    const finish = () => {
      updateTask(id, { deleted: true });
      toast("Task deleted", () => updateTask(id, { deleted: false }));
    };
    if (rowEl && !rowEl.classList.contains("done")) { rowEl.classList.add("deleting"); setTimeout(finish, 240); } else finish();
  }

  // ---------- events ----------
  function submitAdd() {
    const input = $("#add-title");
    if (!input || !input.value.trim()) return;
    const list = ui.list ? findList(ui.list) : null;
    const t0 = todayStr();
    const d = addState.date;
    let due = d === "today" ? t0 : d === "tomorrow" ? addDays(t0, 1) : d === "later" ? null : d;
    if (!due && addState.time) due = addState.time > new Date().toTimeString().slice(0, 5) ? t0 : addDays(t0, 1);
    const space = list ? list.space : ui.space === "BOTH" ? addState.space : ui.space;
    const title = input.value;
    const time = addState.time;
    input.value = "";
    addState.time = "";
    addTask(title, space, list ? list.id : null, due, time);
    $("#add-title").focus();
  }

  function commitEdit(input) {
    const id = input.dataset.id;
    const t = findTask(id);
    editingId = null;
    if (t && input.value.trim() && input.value.trim() !== t.title) {
      Object.assign(t, { title: input.value.trim(), updatedAt: now() });
      saveData();
      scheduleSync();
    }
    // Re-render a beat later so a click that caused this blur (e.g. on another task) still lands.
    setTimeout(() => { if (!editingId) render(); }, 180);
  }

  app.addEventListener("click", (e) => {
    const target = e.target.closest("[data-act]");
    if (!target) {
      if (popover && !e.target.closest("#popover")) { popover = null; render(); }
      return;
    }
    if (target.hasAttribute("data-stop")) return;
    const act = target.dataset.act;
    const row = target.closest(".task");
    const id = row && row.dataset.id;

    if (act === "close-dialog" && e.target !== target && e.target.closest("[data-stop]")) return;
    if (popover && act !== "options" && !target.closest("#popover")) popover = null;

    switch (act) {
      case "menu": $("#sidebar").classList.toggle("open"); return;
      case "space": ui.space = target.dataset.space; ui.list = null; addState.date = "today"; if (ui.space !== "BOTH") addState.space = ui.space; saveUi(); break;
      case "open-list": ui.list = target.dataset.id; addState.date = "later"; saveUi(); break;
      case "new-list": dialog = { list: "new", space: target.dataset.space }; break;
      case "edit-list": dialog = { list: target.dataset.id }; break;
      case "ask-delete-list": dialog = { deleteList: target.dataset.id }; break;
      case "settings": dialog = "settings"; break;
      case "close-dialog": dialog = null; break;
      case "add": submitAdd(); return;
      case "add-date": addState.date = target.dataset.v; if (addState.date === "later") addState.time = ""; break;
      case "add-space": addState.space = target.dataset.space; break;
      case "done": completeTask(id, row); return;
      case "undone": updateTask(id, { done: false, completedAt: null }); return;
      case "delete": deleteTask(id, row); return;
      case "edit":
        if (e.target.closest(".title-input")) return;
        if (editingId === id) return;
        editingId = id;
        break;
      case "options": {
        const r = target.getBoundingClientRect();
        popover = popover && popover.id === id ? null : { id, x: r.right - 340, y: r.bottom };
        break;
      }
      case "set-due": updateTask(popover.id, { due: target.dataset.v || null }); return;
      case "clear-time": updateTask(popover.id, { time: null }); return;
      case "pop-done": { const pid = popover.id; popover = null; completeTask(pid, null); return; }
      case "pop-delete": { const pid = popover.id; popover = null; deleteTask(pid, null); return; }
      case "move-space": { const t = findTask(popover.id); popover = null; updateTask(t.id, { space: otherSpace(t.space), list: null }); toast(`Moved to ${SPACES[t.space]}`); return; }
      case "toggle-done": ui.showDone = !ui.showDone; saveUi(); break;
      case "clear-done": {
        const { done } = sectionsFor(target.dataset.space || null, target.dataset.list || null);
        done.forEach((t) => Object.assign(t, { deleted: true, updatedAt: now() }));
        touch();
        return;
      }
      case "seed": settings.seed = target.dataset.v; saveSettings(); break;
      case "theme": settings.theme = target.dataset.v; saveSettings(); break;
      case "toggle-sound": settings.sound = !settings.sound; saveSettings(); break;
      case "notify":
        if ("Notification" in window) Notification.requestPermission().then(render);
        return;
      case "save-settings": {
        const repo = $("#set-repo").value.trim(), token = $("#set-token").value.trim();
        const changed = repo !== settings.repo || token !== settings.token;
        Object.assign(settings, { repo, token, localOnly: !token });
        saveSettings();
        dialog = null;
        if (changed) resetRemote();
        sync();
        break;
      }
      case "pick-emoji": dialog.emoji = target.dataset.v; dialog.name = $("#list-name").value; break;
      case "save-list": {
        const name = $("#list-name").value.trim();
        if (!name) return;
        const emoji = dialog.emoji || (dialog.list !== "new" && findList(dialog.list)?.emoji) || EMOJIS[0];
        if (dialog.list === "new") {
          const l = { id: uuid(), name, emoji, space: dialog.space || ui.space, createdAt: now(), updatedAt: now(), deleted: false };
          data.lists.push(l);
          ui.list = l.id;
          addState.date = "later";
          saveUi();
        } else {
          Object.assign(findList(dialog.list), { name, emoji, updatedAt: now() });
        }
        dialog = null;
        touch();
        return;
      }
      case "delete-list": {
        const lid = dialog.deleteList;
        const l = findList(lid);
        if (l) Object.assign(l, { deleted: true, updatedAt: now() });
        data.tasks.filter((t) => t.list === lid && !t.deleted).forEach((t) => Object.assign(t, { deleted: true, updatedAt: now() }));
        ui.list = null; saveUi(); dialog = null;
        touch();
        return;
      }
      case "connect": {
        const repo = $("#setup-repo").value.trim(), token = $("#setup-token").value.trim();
        if (!token || !repo.includes("/")) { $("#setup-err").textContent = "Enter the repo and a token."; return; }
        Object.assign(settings, { repo, token, localOnly: false });
        saveSettings();
        resetRemote();
        render();
        sync();
        return;
      }
      case "local-only": settings.localOnly = true; saveSettings(); break;
      default: return;
    }
    render();
  });

  app.addEventListener("input", (e) => {
    if (e.target.id === "add-title") $("#add-send").disabled = !e.target.value.trim();
  });

  app.addEventListener("change", (e) => {
    const el = e.target;
    if (el.id === "add-date") { addState.date = el.value || "today"; render(); }
    else if (el.id === "add-time") {
      addState.time = el.value;
      if (el.value && addState.date === "later") addState.date = el.value > new Date().toTimeString().slice(0, 5) ? "today" : "tomorrow";
      render();
    } else if (el.id === "pop-date" && el.value) updateTask(popover.id, { due: el.value });
    else if (el.id === "pop-time") {
      const t = findTask(popover.id);
      const due = t.due || (el.value > new Date().toTimeString().slice(0, 5) ? todayStr() : addDays(todayStr(), 1));
      updateTask(popover.id, { time: el.value || null, due });
    } else if (el.id === "pop-list") updateTask(popover.id, { list: el.value || null });
  });

  app.addEventListener("keydown", (e) => {
    if (e.target.id === "add-title" && e.key === "Enter") { e.preventDefault(); submitAdd(); }
    if (e.target.classList.contains("title-input")) {
      if (e.key === "Enter") { e.preventDefault(); commitEdit(e.target); }
      if (e.key === "Escape") { editingId = null; render(); }
    }
    if (e.target.id === "setup-token" && e.key === "Enter") $('[data-act="connect"]').click();
  });

  app.addEventListener("focusout", (e) => {
    if (e.target.classList && e.target.classList.contains("title-input") && editingId) commitEdit(e.target);
  });

  app.addEventListener("contextmenu", (e) => {
    const row = e.target.closest(".task:not(.done)");
    if (!row) return;
    e.preventDefault();
    popover = { id: row.dataset.id, x: e.clientX, y: e.clientY };
    render();
  });

  document.addEventListener("keydown", (e) => {
    const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName || "");
    if (e.key === "Escape") {
      if (popover || dialog || editingId) { popover = null; dialog = null; editingId = null; render(); }
      else if (typing) document.activeElement.blur();
      return;
    }
    if (!typing && (e.key === "n" || e.key === "/") && !e.metaKey && !e.ctrlKey) {
      e.preventDefault();
      $("#add-title")?.focus();
    }
  });

  window.addEventListener("resize", positionPopover);

  // ---------- sync (same nowish.json format as the phone) ----------
  const TOMBSTONE_TTL = 30 * 24 * 60 * 60 * 1000;
  let remote = { etag: null, sha: null, data: null };
  let syncing = false, syncAgain = false, syncError = null, lastSync = LS.get("lastSync", 0), syncTimer = null;

  function resetRemote() { remote = { etag: null, sha: null, data: null }; syncError = null; }

  function syncStatus() {
    if (!settings.token) return { cls: "off", text: "Not syncing (this browser only)" };
    if (syncing) return { cls: "busy", text: "Syncing…" };
    if (syncError) return { cls: "err", text: syncError };
    if (!lastSync) return { cls: "off", text: "Connecting…" };
    const mins = Math.round((now() - lastSync) / 60000);
    return { cls: "", text: mins < 1 ? "Synced just now" : `Synced ${mins} min ago` };
  }
  function renderSyncPill() {
    const pill = $(".sync-pill");
    if (!pill) return;
    const st = syncStatus();
    pill.innerHTML = `<span class="dot ${st.cls}"></span>${esc(st.text)}`;
  }

  function scheduleSync(delay = 800) {
    if (!settings.token) return;
    clearTimeout(syncTimer);
    syncTimer = setTimeout(sync, delay);
  }

  const api = () => `https://api.github.com/repos/${settings.repo}/contents/nowish.json`;
  const headers = () => ({
    Authorization: `Bearer ${settings.token}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
  });

  function b64decode(b64) {
    const bin = atob(b64.replace(/\s/g, ""));
    return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  }
  function b64encode(text) {
    const bytes = new TextEncoder().encode(text);
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  async function fetchRemote() {
    const h = headers();
    if (remote.etag && remote.data) h["If-None-Match"] = remote.etag;
    const res = await fetch(api(), { headers: h, cache: "no-store" });
    if (res.status === 304) return remote.data;
    if (res.status === 404) { remote = { etag: null, sha: null, data: null }; return null; }
    if (res.status === 401 || res.status === 403) throw new Error("Token rejected: check its repo access");
    if (!res.ok) throw new Error(`GitHub error ${res.status}`);
    const json = await res.json();
    const parsed = JSON.parse(b64decode(json.content));
    remote = { etag: res.headers.get("ETag"), sha: json.sha, data: { tasks: parsed.tasks || [], lists: parsed.lists || [] } };
    return remote.data;
  }

  async function push(merged) {
    const body = {
      message: "Sync from web",
      content: b64encode(JSON.stringify({ schema: 1, updatedAt: now(), lists: merged.lists, tasks: merged.tasks }, null, 1)),
    };
    if (remote.sha) body.sha = remote.sha;
    const res = await fetch(api(), { method: "PUT", headers: { ...headers(), "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (res.status === 409 || res.status === 422) return false;
    if (res.status === 404) throw new Error(`Can't find repo ${settings.repo}`);
    if (res.status === 401 || res.status === 403) throw new Error("Token can't write to the repo");
    if (!res.ok) throw new Error(`GitHub error ${res.status}`);
    const json = await res.json();
    remote = { etag: null, sha: json.content.sha, data: merged };
    return true;
  }

  /** Newest updatedAt wins per record; tombstones older than 30 days are dropped. */
  function mergeRecords(local, rem, cutoff) {
    const out = new Map();
    let localChanged = false, needPush = false;
    const remMap = new Map(rem.map((r) => [r.id, r]));
    for (const l of local) {
      const r = remMap.get(l.id);
      if (!r) { needPush = true; out.set(l.id, l); }
      else if ((r.updatedAt || 0) > (l.updatedAt || 0)) { localChanged = true; out.set(l.id, r); }
      else { if ((l.updatedAt || 0) > (r.updatedAt || 0)) needPush = true; out.set(l.id, l); }
    }
    for (const r of rem) {
      if (out.has(r.id)) continue;
      if (r.deleted && (r.updatedAt || 0) < cutoff) continue;
      localChanged = true;
      out.set(r.id, r);
    }
    const records = [...out.values()].filter((x) => !(x.deleted && (x.updatedAt || 0) < cutoff));
    return { records, localChanged, needPush };
  }

  async function sync() {
    if (!settings.token) return;
    if (syncing) { syncAgain = true; return; }
    syncing = true;
    renderSyncPill();
    try {
      for (let attempt = 0; attempt < 3; attempt++) {
        const rem = await fetchRemote();
        const cutoff = now() - TOMBSTONE_TTL;
        const lists = mergeRecords(data.lists, rem ? rem.lists : [], cutoff);
        const tasks = mergeRecords(data.tasks, rem ? rem.tasks : [], cutoff);
        const changed = lists.localChanged || tasks.localChanged;
        data = { lists: lists.records, tasks: tasks.records.sort((a, b) => a.createdAt - b.createdAt) };
        saveData();
        if (changed && !editingId) render();
        if (!rem || lists.needPush || tasks.needPush) {
          if (await push(data)) break;
          remote.etag = null; // conflict: refetch and merge again
          continue;
        }
        break;
      }
      syncError = null;
      lastSync = now();
      LS.set("lastSync", lastSync);
    } catch (err) {
      syncError = err && err.message && !/fetch/i.test(err.message) ? err.message : "Offline — will retry";
    } finally {
      syncing = false;
      renderSyncPill();
      if (syncAgain) { syncAgain = false; scheduleSync(200); }
    }
  }

  // ---------- reminders while the tab is open ----------
  const notifyGranted = () => "Notification" in window && Notification.permission === "granted";
  const notified = new Set(LS.get("notified", []));
  function checkReminders() {
    if (!notifyGranted()) return;
    const t0 = todayStr(), hhmm = new Date().toTimeString().slice(0, 5);
    for (const t of liveTasks()) {
      if (t.done || t.due !== t0 || !t.time || t.time > hhmm) continue;
      const key = `${t.id}@${t.due}T${t.time}`;
      if (notified.has(key)) continue;
      notified.add(key);
      // Only ping for reminders that just came due (within 10 minutes), not old ones.
      const [h, m] = t.time.split(":").map(Number);
      const due = new Date(); due.setHours(h, m, 0, 0);
      if (now() - due.getTime() < 10 * 60 * 1000) {
        const n = new Notification(t.title, { body: `${SPACES[t.space]} · ${friendlyTime(t.time)}`, icon: "icon.svg", tag: t.id });
        n.onclick = () => { window.focus(); n.close(); };
      }
    }
    LS.set("notified", [...notified].slice(-300));
  }

  // ---------- start ----------
  addState.space = ui.space === "BOTH" ? "WORK" : ui.space;
  if (ui.list) addState.date = "later";
  render();
  sync();
  setInterval(() => { if (!document.hidden) sync(); }, 20000);
  setInterval(checkReminders, 30000);
  setInterval(() => { if (!editingId && !popover && !dialog && document.activeElement?.id !== "add-title") render(); }, 60000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) sync(); });
  window.addEventListener("focus", () => sync());
  checkReminders();
})();
