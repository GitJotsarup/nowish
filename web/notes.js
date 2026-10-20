/* Nowish web: Notes tab. Same data as the phone (notes, categories, notesLock in nowish.json). */
window.NowishNotes = function (ctx) {
  "use strict";
  const { esc, uuid, now, toast, $ } = ctx;
  const D = () => ctx.getData();

  // ---------- palettes (match the Android app) ----------
  const COLORS = [
    ["default", "Default", null, null],
    ["coral", "Coral", "#FAAFA8", "#77172E"], ["peach", "Peach", "#F39F76", "#692B17"],
    ["sand", "Sand", "#FFF8B8", "#7C4A03"], ["mint", "Mint", "#E2F6D3", "#264D3B"],
    ["sage", "Sage", "#B4DDD3", "#0C625D"], ["fog", "Fog", "#D4E4ED", "#256377"],
    ["storm", "Storm", "#AECCDC", "#284255"], ["dusk", "Dusk", "#D3BFDB", "#472E5B"],
    ["blossom", "Blossom", "#F6E2DD", "#6C394F"], ["clay", "Clay", "#E9E3D4", "#4B443A"],
    ["chalk", "Chalk", "#EFEFF1", "#232427"],
  ];
  const THEMES = [
    ["sunset", "Sunset peaks", 1], ["night", "Starry night", 1], ["ocean", "Ocean", 1], ["forest", "Pine forest", 1],
    ["desert", "Desert", 0], ["city", "City lights", 1], ["sakura", "Sakura", 0], ["snow", "Snowy hills", 0],
    ["volcano", "Volcano", 1], ["aurora", "Aurora", 1], ["planet", "Planet", 1], ["rain", "Rainy day", 1],
    ["synthwave", "Synthwave", 1], ["meadow", "Meadow", 0], ["reef", "Reef", 1], ["autumn", "Autumn", 0],
    ["galaxy", "Galaxy", 1], ["beach", "Palm beach", 0], ["candy", "Candy clouds", 0], ["campfire", "Campfire", 1],
  ];
  const CAT_EMOJIS = ["📁", "💼", "☬", "🙏", "📿", "📖", "🎓", "💡", "🧠", "❤️", "🏠", "🛒", "✈️", "🎬", "🎵", "🍳", "🏋️", "💰", "🌱", "🎨", "💻", "📝", "⭐", "🔥", "🌙", "☀️", "🐾", "🎮", "📷", "🧾"];

  const isDark = () => {
    const t = ctx.settings.theme;
    return t === "dark" || (t === "auto" && matchMedia("(prefers-color-scheme: dark)").matches);
  };
  const colorValue = (key) => {
    const c = COLORS.find((x) => x[0] === key);
    return !c || !c[2] ? null : isDark() ? c[3] : c[2];
  };
  const theme = (key) => THEMES.find((t) => t[0] === key);

  /** Inline style + ink class for a note's color / pixel theme. */
  function look(n) {
    const t = theme(n.theme);
    if (t) {
      const scrim = t[2] ? "rgb(0 0 0 / .38)" : "rgb(255 255 255 / .5)";
      return { style: `background:linear-gradient(${scrim},${scrim}),url(themes/${t[0]}.png) center bottom/cover;`, cls: (t[2] ? "ink-light" : "ink-dark") + " themed" };
    }
    const c = colorValue(n.color);
    if (c) return { style: `background:${c};`, cls: isDark() ? "ink-light" : "ink-dark" };
    return { style: "", cls: "plain" };
  }

  // ---------- state ----------
  let unlocked = false;
  let hiddenAt = 0;
  let editorId = null;
  let editorFocus = null; // checklist item id to focus after re-render
  let notesDialog = null; // { type: "category", id|null } | { type: "unlock", then } | { type: "setpin", then } | { type: "trash", id } | { type: "palette" }
  let saveTimer = null;

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) hiddenAt = now();
    else if (unlocked && now() - hiddenAt > 5 * 60 * 1000) { unlocked = false; ctx.render(); }
  });

  const notes = () => (D().notes || []).filter((n) => !n.deleted);
  const cats = () => (D().categories || []).filter((c) => !c.deleted).sort((a, b) => a.createdAt - b.createdAt);
  const findNote = (id) => (D().notes || []).find((n) => n.id === id);
  const findCat = (id) => cats().find((c) => c.id === id);
  const lock = () => D().notesLock;

  function touchNote(n, patch, { renderEditorToo = false } = {}) {
    delete n._draft;
    Object.assign(n, patch, { updatedAt: now() });
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => { ctx.saveData(); ctx.scheduleSync(); }, 400);
    ctx.render();
    if (renderEditorToo) renderEditor();
  }

  // ---------- HTML helpers ----------
  const ALLOWED = new Set(["B", "STRONG", "I", "EM", "U", "INS", "S", "STRIKE", "DEL", "MARK", "SMALL", "SUB", "SUP", "H1", "H2", "H3", "P", "BR", "UL", "OL", "LI", "SPAN", "DIV", "CODE", "A", "BLOCKQUOTE"]);
  const STYLE_OK = /^(font-size|font-weight|font-style|text-decoration|text-decoration-line|background|background-color)$/;
  function cleanNode(node) {
    let i = 0;
    while (i < node.children.length) {
      const el = node.children[i];
      if (!ALLOWED.has(el.tagName)) {
        if (/^(SCRIPT|STYLE|IFRAME|OBJECT|EMBED|svg|SVG|MATH)$/.test(el.tagName)) { el.remove(); continue; }
        el.replaceWith(...el.childNodes); // unwrap, then look at the same index again
        continue;
      }
      for (const a of [...el.attributes]) {
        if (a.name === "style") {
          const kept = [...el.style].filter((p) => STYLE_OK.test(p)).map((p) => `${p}:${el.style.getPropertyValue(p)}`).join(";");
          if (kept) el.setAttribute("style", kept); else el.removeAttribute("style");
        } else if (!(a.name === "href" && el.tagName === "A" && /^https?:/i.test(a.value))) {
          el.removeAttribute(a.name);
        }
      }
      if (el.tagName === "A") { el.setAttribute("target", "_blank"); el.setAttribute("rel", "noopener"); }
      cleanNode(el);
      i++;
    }
  }
  function sanitize(html) {
    const doc = new DOMParser().parseFromString(`<body>${html || ""}</body>`, "text/html");
    cleanNode(doc.body);
    return doc.body.innerHTML;
  }
  const textOf = (html) => new DOMParser().parseFromString(`<body>${html || ""}</body>`, "text/html").body.textContent || "";
  const isBlank = (n) => !n.title?.trim() && !textOf(n.content).trim() && !(n.images || []).length && !(n.checklist || []).some((i) => i.text?.trim());

  // ---------- images (stored in the data repo under images/) ----------
  const imgUrls = new Map();
  async function imageUrl(uid) {
    if (imgUrls.has(uid)) return imgUrls.get(uid);
    let blob = null;
    try { const hit = await (await caches.open("nowish-images")).match(`img/${uid}`); if (hit) blob = await hit.blob(); } catch { /* no cache api */ }
    if (!blob && ctx.settings.token) {
      const res = await fetch(`${ctx.repoApi()}/contents/images/${uid}.jpg`, { headers: { ...ctx.headers(), Accept: "application/vnd.github.raw+json" }, cache: "no-store" });
      if (!res.ok) return null;
      blob = await res.blob();
      try { await (await caches.open("nowish-images")).put(`img/${uid}`, new Response(blob)); } catch { /* ignore */ }
    }
    if (!blob) return null;
    const url = URL.createObjectURL(blob);
    imgUrls.set(uid, url);
    return url;
  }
  function hydrateImages(root) {
    root.querySelectorAll("img[data-img]").forEach(async (img) => {
      const url = await imageUrl(img.dataset.img);
      if (url) img.src = url; else img.classList.add("missing");
    });
  }
  async function resizeToJpeg(file) {
    const url = URL.createObjectURL(file);
    try {
      const img = await new Promise((ok, bad) => { const i = new Image(); i.onload = () => ok(i); i.onerror = bad; i.src = url; });
      const scale = Math.min(1, 1600 / Math.max(img.naturalWidth, img.naturalHeight));
      const c = document.createElement("canvas");
      c.width = Math.round(img.naturalWidth * scale); c.height = Math.round(img.naturalHeight * scale);
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      return await new Promise((ok) => c.toBlob(ok, "image/jpeg", 0.85));
    } finally { URL.revokeObjectURL(url); }
  }
  async function addImages(n, files) {
    if (!ctx.settings.token) { toast("Connect sync first to add images"); return; }
    for (const file of files) {
      if (!file.type.startsWith("image/")) continue;
      try {
        const blob = await resizeToJpeg(file);
        const b64 = await new Promise((ok) => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(",")[1]); r.readAsDataURL(blob); });
        const uid = uuid();
        const res = await fetch(`${ctx.repoApi()}/contents/images/${uid}.jpg`, {
          method: "PUT", headers: { ...ctx.headers(), "Content-Type": "application/json" },
          body: JSON.stringify({ message: "Add image", content: b64 }),
        });
        if (!res.ok) throw new Error(String(res.status));
        imgUrls.set(uid, URL.createObjectURL(blob));
        try { await (await caches.open("nowish-images")).put(`img/${uid}`, new Response(blob)); } catch { /* ignore */ }
        touchNote(n, { images: [...(n.images || []), uid] }, { renderEditorToo: true });
      } catch { toast("Couldn't upload that image"); }
    }
  }

  // ---------- PIN (PBKDF2-SHA256, same as the phone) ----------
  const b64ToBytes = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const bytesToB64 = (bytes) => btoa(String.fromCharCode(...bytes));
  async function hashPin(pin, saltB64, iterations = 120000) {
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: b64ToBytes(saltB64), iterations }, key, 256);
    return bytesToB64(new Uint8Array(bits));
  }
  function withUnlock(then) {
    if (unlocked || !lock()) { unlocked = true; then(); return; }
    notesDialog = { type: "unlock", then };
    ctx.render();
  }

  // ---------- main view ----------
  function filtered() {
    const ui = ctx.ui;
    const q = (ui.noteQuery || "").trim().toLowerCase();
    return notes().filter((n) => !n.trashedAt)
      .filter((n) => !ui.noteCat || n.category === ui.noteCat)
      .filter((n) => !q || ((!n.locked || unlocked) && (
        (n.title || "").toLowerCase().includes(q) || textOf(n.content).toLowerCase().includes(q) ||
        (n.checklist || []).some((i) => (i.text || "").toLowerCase().includes(q)))))
      .sort((a, b) => b.updatedAt - a.updatedAt);
  }

  function sidebarHtml() {
    const ui = ctx.ui;
    const live = notes().filter((n) => !n.trashedAt);
    const item = (act, attrs, emoji, label, active, count, edit) =>
      `<div class="nav-row"><button class="nav-item ${active ? "active" : ""}" data-nact="${act}" ${attrs}><span class="emoji">${emoji}</span>${label}${count ? `<span class="count">${count}</span>` : ""}</button>` +
      (edit ? `<button class="icon-btn nav-edit" data-nact="cat-edit" data-id="${edit}" title="Edit or delete category">✎</button>` : "") + `</div>`;
    return `<div class="nav-label">Notes</div>` +
      item("cat", 'data-id=""', "✨", "All notes", !ui.showTrash && !ui.noteCat, live.length) +
      cats().map((c) => item("cat", `data-id="${c.id}"`, esc(c.emoji), esc(c.name), !ui.showTrash && ui.noteCat === c.id, live.filter((n) => n.category === c.id).length, c.id)).join("") +
      item("cat-new", "", "＋", "New category", false, 0) +
      item("show-trash", "", "🗑️", "Trash", ui.showTrash, notes().filter((n) => n.trashedAt).length);
  }

  function cardHtml(n) {
    const hidden = n.locked && !unlocked;
    const lk = look(n);
    const cat = findCat(n.category);
    let body = "";
    if (hidden) {
      body = `<div class="nc-locked">🔒 Locked note</div>`;
    } else {
      const imgs = n.images || [];
      if (imgs.length) body += `<div class="nc-img"><img data-img="${esc(imgs[0])}" alt="">${imgs.length > 1 ? `<span>+${imgs.length - 1}</span>` : ""}</div>`;
      body += `<div class="nc-pad">`;
      if (n.title) body += `<div class="nc-title">${esc(n.title)}</div>`;
      if (n.checklist) {
        const items = n.checklist.filter((i) => i.text?.trim()).sort((a, b) => a.done - b.done);
        body += items.slice(0, 8).map((i) => `<div class="nc-item ${i.done ? "done" : ""}">${i.done ? "☑" : "☐"} ${esc(i.text)}</div>`).join("");
        if (items.length > 8) body += `<div class="nc-more">+ ${items.length - 8} more</div>`;
      } else if (textOf(n.content).trim()) {
        body += `<div class="nc-body">${sanitize(n.content)}</div>`;
      }
      body += `</div>`;
    }
    if (cat && !hidden) body += `<div class="nc-pad nc-foot"><span class="nc-cat">${esc(cat.emoji)} ${esc(cat.name)}</span></div>`;
    else if (cat) body += `<div class="nc-pad nc-foot"><span class="nc-cat">${esc(cat.emoji)} ${esc(cat.name)}</span></div>`;
    const actions = ctx.ui.showTrash ? "" : `
      <div class="nc-actions">
        <button class="icon-btn" data-nact="pin" title="${n.pinned ? "Unpin" : "Pin"}">${n.pinned ? "📌" : "📍"}</button>
        <button class="icon-btn" data-nact="trash-note" title="Move to trash">🗑</button>
      </div>`;
    return `<div class="note-card ${lk.cls}" style="${lk.style}" data-note="${n.id}">${n.pinned && !ctx.ui.showTrash ? '<span class="nc-pin">📌</span>' : ""}${body}${actions}</div>`;
  }

  function mainHtml() {
    const ui = ctx.ui;
    if (ui.showTrash) {
      const trash = notes().filter((n) => n.trashedAt).sort((a, b) => b.trashedAt - a.trashedAt);
      return `
        <div class="head-title"><button class="icon-btn menu-btn" data-act="menu">☰</button>🗑️ Trash
          ${trash.length ? '<button class="btn text" data-nact="empty-trash">Empty trash</button>' : ""}</div>
        <p class="head-sub">Notes in the trash are deleted after 30 days. Click one to restore it.</p>
        ${trash.length ? `<div class="masonry">${trash.map(cardHtml).join("")}</div>` : `<div class="empty"><div class="big">🗑️</div><h2>Trash is empty</h2></div>`}
        ${dialogHtml()}`;
    }
    const list = filtered();
    const pinned = list.filter((n) => n.pinned), others = list.filter((n) => !n.pinned);
    const all = notes().filter((n) => !n.trashedAt);
    const circles = [`<button class="cat-circle ${!ui.noteCat ? "on" : ""}" data-nact="cat" data-id=""><span class="cc-dot">✨</span><span class="cc-label">All</span><span class="cc-count">${all.length}</span></button>`]
      .concat(cats().map((c) => `<button class="cat-circle ${ui.noteCat === c.id ? "on" : ""}" data-nact="cat" data-id="${c.id}" title="Right-click to edit">
          <span class="cc-dot" style="${colorValue(c.color) ? `background:${colorValue(c.color)}` : ""}">${esc(c.emoji)}</span>
          <span class="cc-label">${esc(c.name)}</span><span class="cc-count">${all.filter((n) => n.category === c.id).length || ""}</span></button>`))
      .concat([`<button class="cat-circle add" data-nact="cat-new"><span class="cc-dot">＋</span><span class="cc-label">New</span><span class="cc-count"></span></button>`]);
    const searching = (ui.noteQuery || "").trim() || ui.noteCat;
    const selectedCat = ui.noteCat ? findCat(ui.noteCat) : null;
    return `
      <div class="notes-top">
        <button class="icon-btn menu-btn" data-act="menu">☰</button>
        <label class="search"><span>🔍</span><input id="note-search" placeholder="Search your notes" value="${esc(ui.noteQuery || "")}" autocomplete="off">
          ${ui.noteQuery ? '<button class="icon-btn" data-nact="clear-search" title="Clear">✕</button>' : ""}</label>
        ${unlocked && lock() ? '<button class="icon-btn" data-nact="relock" title="Hide locked notes again">🔓</button>' : ""}
      </div>
      <div class="cat-row">${circles.join("")}</div>
      ${selectedCat ? `<div class="cat-tools"><button class="chip small" data-nact="cat-edit" data-id="${selectedCat.id}">✎ Edit or delete “${esc(selectedCat.name)}”</button></div>` : ""}
      ${pinned.length ? `<div class="notes-label">PINNED</div><div class="masonry">${pinned.map(cardHtml).join("")}</div>` : ""}
      ${pinned.length && others.length ? `<div class="notes-label">OTHERS</div>` : ""}
      ${others.length ? `<div class="masonry">${others.map(cardHtml).join("")}</div>` : ""}
      ${!list.length ? `<div class="empty"><div class="big">${searching ? "🔍" : "💡"}</div><h2>${searching ? "No matching notes" : "Notes you add appear here"}</h2><p>${searching ? "" : "Click + to write one"}</p></div>` : ""}
      <button class="fab" data-nact="new-note" title="New note (N)">＋</button>
      ${dialogHtml()}`;
  }

  // ---------- dialogs ----------
  function dialogHtml() {
    const d = notesDialog;
    if (!d) return "";
    if (d.type === "category") {
      const c = d.id ? findCat(d.id) : null;
      const emoji = d.emoji || c?.emoji || CAT_EMOJIS[0];
      const color = d.color || c?.color || "default";
      return `
        <div class="scrim" data-nact="close-ndialog"><div class="dialog" data-stop>
          <h2>${c ? "Edit category" : "New category"}</h2>
          ${d.confirm ? `<p class="err-text">Delete “${esc(c?.name)}”? Its notes stay, they just lose the category.</p>` : ""}
          <label>Name</label>
          <input class="field" id="cat-name" value="${esc(d.name ?? c?.name ?? "")}" placeholder="Sikhi notes">
          <label>Icon</label>
          <div class="emoji-grid">${CAT_EMOJIS.map((e) => `<button class="${e === emoji ? "on" : ""}" data-nact="cat-emoji" data-v="${e}">${e}</button>`).join("")}</div>
          <label>Color</label>
          <div class="swatches">${COLORS.map(([k, name]) => `<button class="swatch ${color === k ? "on" : ""}" title="${name}" style="background:${colorValue(k) || "var(--container-high)"}" data-nact="cat-color" data-v="${k}"></button>`).join("")}</div>
          <div class="actions">
            ${c ? `<button class="btn danger" data-nact="cat-delete" style="margin-right:auto">${d.confirm ? "Tap again to delete" : "Delete"}</button>` : ""}
            <button class="btn text" data-nact="close-ndialog">Cancel</button>
            <button class="btn" data-nact="cat-save">${c ? "Save" : "Create"}</button>
          </div>
        </div></div>`;
    }
    if (d.type === "unlock" || d.type === "setpin") {
      const set = d.type === "setpin";
      return `
        <div class="scrim" data-nact="close-ndialog"><div class="dialog" data-stop style="width:380px">
          <h2>🔒 ${set ? "Set a notes PIN" : "Locked notes"}</h2>
          <p>${set ? "Locked notes stay hidden until you enter this PIN. It's the same PIN on your phone." : "Enter your notes PIN."}</p>
          <label>PIN</label><input class="field" id="pin1" type="password" inputmode="numeric" maxlength="8" autocomplete="off">
          ${set ? '<label>Confirm PIN</label><input class="field" id="pin2" type="password" inputmode="numeric" maxlength="8" autocomplete="off">' : ""}
          <div class="err-text" id="pin-err"></div>
          <div class="actions"><button class="btn text" data-nact="close-ndialog">Cancel</button>
            <button class="btn" data-nact="${set ? "pin-set" : "pin-check"}">${set ? "Set PIN" : "Unlock"}</button></div>
        </div></div>`;
    }
    if (d.type === "trash") {
      const n = findNote(d.id);
      return `
        <div class="scrim" data-nact="close-ndialog"><div class="dialog" data-stop style="width:380px">
          <h2>${esc(n?.title || "This note")}</h2><p>Restore it, or delete it forever?</p>
          <div class="actions"><button class="btn danger" data-nact="delete-forever">Delete forever</button>
            <button class="btn" data-nact="restore">Restore</button></div>
        </div></div>`;
    }
    return "";
  }

  // ---------- editor (its own root so typing never gets re-rendered) ----------
  const root = document.createElement("div");
  root.id = "editor-root";
  document.body.appendChild(root);

  function openNote(n) {
    if (notesDialog?.type === "palette") notesDialog = null;
    if (n.locked) withUnlock(() => { editorId = n.id; renderEditor(); });
    else { editorId = n.id; renderEditor(); }
  }

  function newNote() {
    const n = {
      id: uuid(), title: "", content: "", checklist: null, color: "default", theme: null,
      category: ctx.ui.noteCat || null, pinned: false, locked: false, images: [], trashedAt: null,
      createdAt: now(), updatedAt: now(), deleted: false, _draft: true,
    };
    D().notes.push(n);
    if (notesDialog?.type === "palette") notesDialog = null;
    editorId = n.id;
    renderEditor();
    root.querySelector(".ne-title")?.focus();
  }

  function closeEditor() {
    const n = findNote(editorId);
    editorId = null;
    if (notesDialog?.type === "palette") notesDialog = null;
    root.innerHTML = "";
    if (n) {
      delete n._draft;
      if (isBlank(n)) {
        // Empty notes just disappear (tombstone so other devices drop it too).
        Object.assign(n, { deleted: true, updatedAt: now() });
      }
      ctx.saveData();
      ctx.scheduleSync(200);
    }
    ctx.render();
  }

  function edited(n) {
    const mins = Math.round((now() - n.updatedAt) / 60000);
    if (mins < 1) return "Edited just now";
    if (mins < 60) return `Edited ${mins} min ago`;
    return "Edited " + new Date(n.updatedAt).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }

  function renderEditor() {
    const n = findNote(editorId);
    if (!n) { root.innerHTML = ""; return; }
    const lk = look(n);
    const imgs = n.images || [];
    const cat = findCat(n.category);
    const scrollTop = root.querySelector(".ne-scroll")?.scrollTop || 0;
    const checklist = n.checklist
      ? `<div class="ne-checklist">
          ${n.checklist.filter((i) => !i.done).map(itemHtml).join("")}
          <button class="ne-additem" data-eact="add-item">＋ List item</button>
          ${n.checklist.some((i) => i.done) ? `<div class="ne-donelabel">${n.checklist.filter((i) => i.done).length} checked</div>${n.checklist.filter((i) => i.done).map(itemHtml).join("")}` : ""}
        </div>`
      : `<div class="ne-body" contenteditable="true" data-placeholder="Note">${sanitize(n.content)}</div>`;
    root.innerHTML = `
      <div class="scrim editor-scrim" data-eact="close">
        <div class="note-editor ${lk.cls}" style="${lk.style}" data-stop>
          <div class="ne-scroll">
            ${imgs.length ? `<div class="ne-images ${imgs.length === 1 ? "one" : ""}">${imgs.map((u) => `<div class="ne-imgwrap"><img data-img="${esc(u)}" alt=""><button class="ne-imgdel" data-eact="del-img" data-v="${esc(u)}" title="Remove image">✕</button></div>`).join("")}</div>` : ""}
            <input class="ne-title" placeholder="Title" value="${esc(n.title)}">
            ${checklist}
            <div class="ne-meta">${cat ? `<span class="nc-cat">${esc(cat.emoji)} ${esc(cat.name)}</span>` : ""}<span>${edited(n)}</span></div>
          </div>
          ${n.checklist ? "" : `<div class="ne-toolbar">
            <button data-cmd="bold" title="Bold (Ctrl+B)"><b>B</b></button>
            <button data-cmd="italic" title="Italic (Ctrl+I)"><i>I</i></button>
            <button data-cmd="underline" title="Underline (Ctrl+U)"><u>U</u></button>
            <button data-cmd="strikeThrough" title="Strikethrough"><s>S</s></button>
            <button data-cmd="h1" title="Heading">H1</button>
            <button data-cmd="h2" title="Subheading">H2</button>
            <button data-cmd="mark" title="Highlight"><mark>ab</mark></button>
            <button data-cmd="insertUnorderedList" title="Bulleted list">• ≡</button>
            <button data-cmd="insertOrderedList" title="Numbered list">1. ≡</button>
            <button data-cmd="code" title="Code"><code>&lt;/&gt;</code></button>
            <button data-cmd="removeFormat" title="Clear formatting">⌫</button>
          </div>`}
          <div class="ne-bottom">
            <button class="icon-btn" data-eact="palette" title="Color & background">🎨</button>
            <label class="icon-btn" title="Add image">🖼️<input type="file" accept="image/*" multiple id="ne-file" hidden></label>
            <button class="icon-btn" data-eact="checklist" title="${n.checklist ? "Turn into text" : "Turn into checklist"}">${n.checklist ? "📝" : "☑️"}</button>
            <select class="ne-cat" id="ne-cat" title="Category">
              <option value="">🏷️ No category</option>
              ${cats().map((c) => `<option value="${c.id}" ${n.category === c.id ? "selected" : ""}>${esc(c.emoji)} ${esc(c.name)}</option>`).join("")}
            </select>
            <button class="icon-btn" data-eact="pin" title="${n.pinned ? "Unpin" : "Pin"}">${n.pinned ? "📌" : "📍"}</button>
            <button class="icon-btn" data-eact="lock" title="${n.locked ? "Unlock note" : "Lock note"}">${n.locked ? "🔒" : "🔓"}</button>
            <button class="icon-btn" data-eact="copy" title="Make a copy">⧉</button>
            <button class="icon-btn" data-eact="trash" title="Move to trash">🗑</button>
            <span style="flex:1"></span>
            <button class="btn text ink" data-eact="close">Close</button>
          </div>
          ${notesDialog?.type === "palette" ? paletteHtml(n) : ""}
        </div>
      </div>`;
    hydrateImages(root);
    const sc = root.querySelector(".ne-scroll");
    if (sc) sc.scrollTop = scrollTop;
    if (editorFocus) {
      const el = root.querySelector(`[data-item="${editorFocus}"] input[type="text"]`);
      if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
      editorFocus = null;
    }
  }

  function itemHtml(i) {
    return `<div class="ne-item ${i.done ? "done" : ""}" data-item="${esc(i.id)}">
      <input type="checkbox" ${i.done ? "checked" : ""} data-eact="toggle-item">
      <input type="text" value="${esc(i.text)}" placeholder="List item">
      <button class="icon-btn" data-eact="del-item" title="Remove">✕</button></div>`;
  }

  function paletteHtml(n) {
    return `<div class="ne-palette">
      <div class="pop-label">Color</div>
      <div class="swatches">${COLORS.map(([k, name]) => `<button class="swatch ${n.color === k ? "on" : ""}" title="${name}" style="background:${colorValue(k) || "var(--surface)"}" data-eact="color" data-v="${k}">${k === "default" ? "⊘" : ""}</button>`).join("")}</div>
      <div class="pop-label">Background</div>
      <div class="theme-grid">
        <button class="theme-thumb none ${!n.theme ? "on" : ""}" data-eact="theme" data-v="">None</button>
        ${THEMES.map(([k, name]) => `<button class="theme-thumb ${n.theme === k ? "on" : ""}" title="${name}" style="background-image:url(themes/${k}.png)" data-eact="theme" data-v="${k}"></button>`).join("")}
      </div></div>`;
  }

  function saveBody() {
    const n = findNote(editorId);
    const body = root.querySelector(".ne-body");
    if (!n || !body) return;
    const html = sanitize(body.innerHTML);
    if (html !== n.content) touchNote(n, { content: textOf(html).trim() || /<(img|li)/i.test(html) ? html : "" });
  }

  function readItems() {
    return [...root.querySelectorAll(".ne-item")].map((row) => ({
      id: row.dataset.item,
      text: row.querySelector('input[type="text"]').value,
      done: row.querySelector('input[type="checkbox"]').checked,
    }));
  }

  function formatCmd(cmd) {
    const body = root.querySelector(".ne-body");
    if (!body) return;
    body.focus();
    if (cmd === "h1" || cmd === "h2") {
      const tag = cmd === "h1" ? "H2" : "H3"; // phone renders <h2> ≈ 1.5em, <h3> ≈ 1.2em
      const current = document.queryCommandValue("formatBlock").toUpperCase();
      document.execCommand("formatBlock", false, current === tag ? "<P>" : `<${tag}>`);
    } else if (cmd === "mark") {
      document.execCommand("styleWithCSS", false, true);
      const sel = getSelection();
      const has = sel.anchorNode && sel.anchorNode.parentElement?.closest("mark,[style*='background']");
      document.execCommand("hiliteColor", false, has ? "transparent" : "yellow");
      document.execCommand("styleWithCSS", false, false);
    } else if (cmd === "code") {
      const text = getSelection().toString();
      if (text) document.execCommand("insertHTML", false, `<code>${esc(text)}</code>`);
    } else {
      document.execCommand(cmd, false, null);
    }
    saveBody();
  }

  root.addEventListener("mousedown", (e) => {
    // Keep the text selection when clicking toolbar buttons.
    if (e.target.closest(".ne-toolbar button")) e.preventDefault();
  });

  root.addEventListener("click", (e) => {
    const cmd = e.target.closest("[data-cmd]");
    if (cmd) { formatCmd(cmd.dataset.cmd); return; }
    const t = e.target.closest("[data-eact]");
    const n = findNote(editorId);
    if (!t || !n) return;
    if (t.dataset.eact === "close" && e.target !== t && e.target.closest("[data-stop]")) {
      if (notesDialog?.type === "palette" && !e.target.closest(".ne-palette")) { notesDialog = null; renderEditor(); }
      return;
    }
    switch (t.dataset.eact) {
      case "close": closeEditor(); return;
      case "palette": notesDialog = notesDialog?.type === "palette" ? null : { type: "palette" }; renderEditor(); return;
      case "color": touchNote(n, { color: t.dataset.v }, { renderEditorToo: true }); return;
      case "theme": touchNote(n, { theme: t.dataset.v || null }, { renderEditorToo: true }); return;
      case "pin": touchNote(n, { pinned: !n.pinned }, { renderEditorToo: true }); return;
      case "lock":
        if (n.locked) touchNote(n, { locked: false }, { renderEditorToo: true });
        else if (lock()) { unlocked = true; touchNote(n, { locked: true }, { renderEditorToo: true }); }
        else { notesDialog = { type: "setpin", then: () => touchNote(n, { locked: true }, { renderEditorToo: true }) }; ctx.render(); }
        return;
      case "copy": {
        const c = { ...JSON.parse(JSON.stringify(n)), id: uuid(), pinned: false, createdAt: now(), updatedAt: now() };
        delete c._draft;
        D().notes.push(c); ctx.saveData(); ctx.scheduleSync(); toast("Copied"); ctx.render(); return;
      }
      case "trash": {
        const id = n.id;
        editorId = null; root.innerHTML = "";
        if (isBlank(n)) Object.assign(n, { deleted: true, updatedAt: now() });
        else { delete n._draft; touchNote(n, { trashedAt: now(), pinned: false }); toast("Note moved to trash", () => touchNote(findNote(id), { trashedAt: null })); }
        ctx.saveData(); ctx.scheduleSync(); ctx.render(); return;
      }
      case "checklist":
        if (n.checklist) {
          const html = n.checklist.filter((i) => i.text.trim()).map((i) => `<p>${esc(i.text)}</p>`).join("");
          touchNote(n, { checklist: null, content: html }, { renderEditorToo: true });
        } else {
          const lines = textOf(n.content.replace(/<\/(p|div|li|h\d)>|<br\s*\/?>/gi, "\n")).split("\n").map((s) => s.trim()).filter(Boolean);
          const items = (lines.length ? lines : [""]).map((text) => ({ id: uuid(), text, done: false }));
          editorFocus = items[items.length - 1].id;
          touchNote(n, { checklist: items, content: "" }, { renderEditorToo: true });
        }
        return;
      case "add-item": {
        const items = readItems();
        const item = { id: uuid(), text: "", done: false };
        const lastOpen = items.map((i) => !i.done).lastIndexOf(true);
        items.splice(lastOpen + 1, 0, item);
        editorFocus = item.id;
        touchNote(n, { checklist: items }, { renderEditorToo: true });
        return;
      }
      case "toggle-item": {
        touchNote(n, { checklist: readItems() }, { renderEditorToo: true });
        if (t.checked) ctx.celebrate(t, { quiet: true });
        return;
      }
      case "del-item": {
        const id = t.closest(".ne-item").dataset.item;
        touchNote(n, { checklist: readItems().filter((i) => i.id !== id) }, { renderEditorToo: true });
        return;
      }
      case "del-img": touchNote(n, { images: (n.images || []).filter((u) => u !== t.dataset.v) }, { renderEditorToo: true }); return;
    }
  });

  root.addEventListener("input", (e) => {
    const n = findNote(editorId);
    if (!n) return;
    if (e.target.classList.contains("ne-title")) touchNote(n, { title: e.target.value });
    else if (e.target.closest(".ne-body")) saveBody();
    else if (e.target.closest(".ne-item") && e.target.type === "text") touchNote(n, { checklist: readItems() });
  });

  root.addEventListener("change", (e) => {
    const n = findNote(editorId);
    if (!n) return;
    if (e.target.id === "ne-cat") touchNote(n, { category: e.target.value || null }, { renderEditorToo: true });
    if (e.target.id === "ne-file") addImages(n, [...e.target.files]);
  });

  root.addEventListener("keydown", (e) => {
    const n = findNote(editorId);
    if (!n) return;
    if (e.key === "Escape") { e.stopPropagation(); if (notesDialog?.type === "palette") { notesDialog = null; renderEditor(); } else closeEditor(); return; }
    if (e.target.classList.contains("ne-title") && e.key === "Enter") {
      e.preventDefault();
      (root.querySelector(".ne-body") || root.querySelector('.ne-item input[type="text"]'))?.focus();
    }
    const row = e.target.closest(".ne-item");
    if (row && e.target.type === "text") {
      if (e.key === "Enter") {
        e.preventDefault();
        const items = readItems();
        const idx = items.findIndex((i) => i.id === row.dataset.item);
        const item = { id: uuid(), text: "", done: false };
        items.splice(idx + 1, 0, item);
        editorFocus = item.id;
        touchNote(n, { checklist: items }, { renderEditorToo: true });
      } else if (e.key === "Backspace" && !e.target.value) {
        e.preventDefault();
        const items = readItems();
        const idx = items.findIndex((i) => i.id === row.dataset.item);
        if (items.length > 1) {
          editorFocus = items[Math.max(0, idx - 1)].id === row.dataset.item ? items[idx + 1]?.id : items[Math.max(0, idx - 1)].id;
          items.splice(idx, 1);
          touchNote(n, { checklist: items }, { renderEditorToo: true });
        }
      }
    }
  });

  root.addEventListener("paste", (e) => {
    const n = findNote(editorId);
    const files = [...(e.clipboardData?.files || [])].filter((f) => f.type.startsWith("image/"));
    if (n && files.length) { e.preventDefault(); addImages(n, files); return; }
    if (e.target.closest(".ne-body")) {
      // Paste as clean HTML.
      const html = e.clipboardData.getData("text/html");
      if (html) { e.preventDefault(); document.execCommand("insertHTML", false, sanitize(html)); saveBody(); }
    }
  });

  root.addEventListener("dragover", (e) => e.preventDefault());
  root.addEventListener("drop", (e) => {
    const n = findNote(editorId);
    const files = [...(e.dataTransfer?.files || [])].filter((f) => f.type.startsWith("image/"));
    if (n && files.length) { e.preventDefault(); addImages(n, files); }
  });

  // ---------- main view events (called from app.js) ----------
  function handleClick(e, target) {
    const act = target.dataset.nact;
    if (!act) return false;
    if (act === "close-ndialog" && e.target !== target && e.target.closest("[data-stop]")) return true;
    const card = target.closest(".note-card");
    const n = card ? findNote(card.dataset.note) : null;
    const ui = ctx.ui;
    switch (act) {
      case "cat": ui.noteCat = target.dataset.id || null; ui.showTrash = false; ctx.saveUi(); document.getElementById("sidebar")?.classList.remove("open"); break;
      case "cat-new": notesDialog = { type: "category", id: null }; break;
      case "cat-edit": notesDialog = { type: "category", id: target.dataset.id }; break;
      case "cat-emoji": notesDialog.emoji = target.dataset.v; notesDialog.name = $("#cat-name").value; break;
      case "cat-color": notesDialog.color = target.dataset.v; notesDialog.name = $("#cat-name").value; break;
      case "cat-save": {
        const name = $("#cat-name").value.trim();
        if (!name) return true;
        const existing = notesDialog.id ? findCat(notesDialog.id) : null;
        const emoji = notesDialog.emoji || existing?.emoji || CAT_EMOJIS[0];
        const color = notesDialog.color || existing?.color || "default";
        if (existing) Object.assign(existing, { name, emoji, color, updatedAt: now() });
        else {
          const c = { id: uuid(), name, emoji, color, createdAt: now(), updatedAt: now(), deleted: false };
          (D().categories ||= []).push(c);
          ui.noteCat = c.id; ctx.saveUi();
        }
        notesDialog = null; ctx.saveData(); ctx.scheduleSync();
        break;
      }
      case "cat-delete":
        if (!notesDialog.confirm) { notesDialog.confirm = true; notesDialog.name = $("#cat-name").value; break; }
      // falls through
      case "cat-delete-yes": {
        const c = findCat(notesDialog.id);
        if (c) {
          Object.assign(c, { deleted: true, updatedAt: now() });
          notes().filter((x) => x.category === c.id).forEach((x) => Object.assign(x, { category: null, updatedAt: now() }));
          if (ui.noteCat === c.id) { ui.noteCat = null; ctx.saveUi(); }
        }
        notesDialog = null; ctx.saveData(); ctx.scheduleSync();
        break;
      }
      case "close-ndialog": notesDialog = null; break;
      case "clear-search": ui.noteQuery = ""; break;
      case "relock": unlocked = false; break;
      case "new-note": newNote(); return true;
      case "pin": if (n) touchNote(n, { pinned: !n.pinned }); return true;
      case "trash-note":
        if (n) { const id = n.id; touchNote(n, { trashedAt: now(), pinned: false }); toast("Note moved to trash", () => touchNote(findNote(id), { trashedAt: null })); }
        return true;
      case "empty-trash":
        notes().filter((x) => x.trashedAt).forEach((x) => Object.assign(x, { deleted: true, updatedAt: now() }));
        ctx.saveData(); ctx.scheduleSync();
        break;
      case "restore": { const x = findNote(notesDialog.id); notesDialog = null; if (x) touchNote(x, { trashedAt: null }); return true; }
      case "delete-forever": { const x = findNote(notesDialog.id); notesDialog = null; if (x) touchNote(x, { deleted: true }); return true; }
      case "pin-check": {
        const pin = $("#pin1").value;
        const l = lock();
        hashPin(pin, l.salt, l.iterations || 120000).then((h) => {
          if (h === l.hash) { unlocked = true; const then = notesDialog.then; notesDialog = null; ctx.render(); then && then(); }
          else { $("#pin-err").textContent = "Wrong PIN"; $("#pin1").value = ""; }
        });
        return true;
      }
      case "pin-set": {
        const a = $("#pin1").value, b = $("#pin2").value;
        if (!/^\d{4,8}$/.test(a)) { $("#pin-err").textContent = "Use 4–8 digits"; return true; }
        if (a !== b) { $("#pin-err").textContent = "PINs don't match"; return true; }
        const salt = bytesToB64(crypto.getRandomValues(new Uint8Array(16)));
        hashPin(a, salt).then((hash) => {
          D().notesLock = { salt, hash, iterations: 120000, updatedAt: now() };
          unlocked = true;
          const then = notesDialog.then; notesDialog = null;
          ctx.saveData(); ctx.scheduleSync(); ctx.render(); then && then();
        });
        return true;
      }
      case "show-trash": ui.showTrash = true; ui.noteQuery = ""; ctx.saveUi(); window.scrollTo(0, 0); break;
      case "open-note": break;
      default: return false;
    }
    ctx.render();
    return true;
  }

  function handleCardClick(e) {
    const card = e.target.closest(".note-card");
    if (!card || e.target.closest("[data-nact]")) return false;
    const n = findNote(card.dataset.note);
    if (!n) return true;
    if (ctx.ui.showTrash) { notesDialog = { type: "trash", id: n.id }; ctx.render(); }
    else openNote(n);
    return true;
  }

  function handleContextMenu(e) {
    const circle = e.target.closest('.cat-circle[data-id]:not([data-id=""])');
    if (!circle) return false;
    e.preventDefault();
    notesDialog = { type: "category", id: circle.dataset.id };
    ctx.render();
    return true;
  }

  function afterRender() {
    hydrateImages(document.getElementById("app"));
    const pin = $("#pin1");
    if (pin && document.activeElement !== pin && !$("#pin2")?.matches(":focus")) pin.focus();
    const cat = $("#cat-name");
    if (cat && document.activeElement?.tagName !== "INPUT") cat.focus();
  }

  return {
    sidebarHtml, mainHtml, handleClick, handleCardClick, handleContextMenu, afterRender, newNote,
    isEditing: () => editorId != null,
    keydown(e) {
      if (e.target.id === "pin1" && e.key === "Enter") { e.preventDefault(); $(`[data-nact="${$("#pin2") ? "pin-set" : "pin-check"}"]`)?.click(); }
      if (e.target.id === "pin2" && e.key === "Enter") { e.preventDefault(); $('[data-nact="pin-set"]')?.click(); }
      if (e.target.id === "cat-name" && e.key === "Enter") { e.preventDefault(); $('[data-nact="cat-save"]')?.click(); }
    },
    closeDialog() { if (notesDialog) { notesDialog = null; return true; } return false; },
    refreshEditor() { if (editorId && !root.contains(document.activeElement)) renderEditor(); },
  };
};
