"use strict";

// Single-page UI over the /api/v1 endpoints. No build step, no external dependencies.
// All server data is rendered with textContent (never innerHTML).

const API = "/api/v1";
const TOKEN_KEY = "ldd.token";
const USER_KEY = "ldd.user";

const CATEGORY_LABELS = {
  email_address: "Email address",
  phone_number: "Phone number",
  ssn: "SSN",
  credit_card_number: "Credit card number",
  national_id_number: "National ID number",
  full_name: "Full name",
  first_name: "First name",
  last_name: "Last name",
  tckn: "TCKN",
  home_address: "Home address",
  date_of_birth: "Date of birth",
  ip_address: "IP address",
};
const CHECK_LABELS = {
  tckn_checksum: "TCKN check digits",
  tckn_format: "TCKN format (11 digits)",
  luhn: "Luhn checksum",
  email_format: "Email syntax",
  ip_address: "IP address syntax",
};
// Same cut-off the evaluation script uses to call a column PII.
const PII_THRESHOLD = 0.5;

const state = {
  token: null,
  user: null,
  snapshotId: null,
  detail: null,
  results: new Map(), // column_id -> classify response
  sampleCount: 10,
};

// ---------- helpers ----------

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") el.className = value;
    // CSSOM, not a style attribute: the page's CSP has no 'unsafe-inline' for styles.
    else if (key === "style") Object.assign(el.style, value);
    else if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
    else if (value === true) el.setAttribute(key, "");
    else el.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

const $ = (id) => document.getElementById(id);

// Inline SVG icons (stroke style, 24px grid). Built with createElementNS, so no innerHTML.
const ICONS = {
  db: ["M4 5a8 3 0 1 0 16 0a8 3 0 1 0 -16 0", "M4 5v14c0 1.66 3.58 3 8 3s8-1.34 8-3V5", "M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3"],
  table: ["M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M3 9h18", "M3 15h18", "M9 9v12"],
  chevron: ["m9 6 6 6-6 6"],
  down: ["m6 9 6 6 6-6"],
  search: ["M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14", "m20 20-3.5-3.5"],
  check: ["M20 6 9 17l-5-5"],
  alert: ["M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18", "M12 8v4", "M12 16h.01"],
  info: ["M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18", "M12 11v5", "M12 8h.01"],
  sparkle: ["M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"],
  trash: ["M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3"],
  scan: ["M4 8V6a2 2 0 0 1 2-2h2", "M16 4h2a2 2 0 0 1 2 2v2", "M20 16v2a2 2 0 0 1-2 2h-2", "M8 20H6a2 2 0 0 1-2-2v-2", "M7 12h10"],
};

function icon(name, size = 16) {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  for (const [k, v] of Object.entries({
    viewBox: "0 0 24 24", width: size, height: size, fill: "none", stroke: "currentColor",
    "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true",
  })) svg.setAttribute(k, v);
  for (const d of ICONS[name]) {
    const path = document.createElementNS(NS, "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}
const pct = (p) => `${Math.round(p * 100)}%`;
const label = (category) => CATEGORY_LABELS[category] || category;

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

function formatDate(value) {
  return new Date(value).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function storage(fn) {
  try {
    return fn(window.sessionStorage);
  } catch {
    return null;
  }
}

function toast(message, kind = "info") {
  const glyph = { error: "alert", success: "check" }[kind] || "info";
  const el = h("div", { class: `toast toast-${kind}` }, icon(glyph), h("span", {}, message));
  $("toasts").append(el);
  setTimeout(() => el.remove(), kind === "error" ? 7000 : 3500);
}

function errorMessage(body, status) {
  const detail = body && body.detail;
  if (typeof detail === "string") return detail;
  if (Array.isArray(detail)) {
    return detail.map((d) => `${(d.loc || []).slice(1).join(".")}: ${d.msg}`).join("; ");
  }
  return `HTTP ${status}`;
}

async function api(method, path, body) {
  const headers = { Accept: "application/json" };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(API + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => null);
  if (response.status === 401 && path !== "/auth") {
    signOut();
    throw new Error("Session expired, please sign in again");
  }
  if (!response.ok) throw new Error(errorMessage(data, response.status));
  return data;
}

async function busy(button, task) {
  const text = button.textContent;
  button.disabled = true;
  button.classList.add("is-busy");
  try {
    return await task();
  } finally {
    button.disabled = false;
    button.classList.remove("is-busy");
    button.textContent = text;
  }
}

// ---------- session ----------

function showApp() {
  $("login-view").hidden = true;
  $("app-view").hidden = false;
  $("session").hidden = false;
  $("session-user").textContent = state.user || "";
  $("session-avatar").textContent = (state.user || "?").slice(0, 1);
  if (!state.detail) renderEmptyDetail();
  loadSnapshots();
}

function signOut() {
  state.token = null;
  state.user = null;
  state.snapshotId = null;
  state.detail = null;
  state.results.clear();
  storage((s) => {
    s.removeItem(TOKEN_KEY);
    s.removeItem(USER_KEY);
  });
  $("app-view").hidden = true;
  $("session").hidden = true;
  $("login-view").hidden = false;
  renderEmptyDetail();
}

$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = Object.fromEntries(new FormData(form));
  await busy(form.querySelector("button"), async () => {
    try {
      const result = await api("POST", "/auth", { username: data.username, password: data.password });
      state.token = result.access_token;
      state.user = data.username;
      storage((s) => {
        s.setItem(TOKEN_KEY, state.token);
        s.setItem(USER_KEY, state.user);
      });
      form.reset();
      showApp();
    } catch (error) {
      toast(error.message, "error");
    }
  });
});

$("logout").addEventListener("click", signOut);

// ---------- snapshots ----------

async function loadSnapshots() {
  const list = $("snapshot-list");
  try {
    const data = await api("GET", "/metadata?limit=100");
    list.replaceChildren();
    if (!data.items.length) {
      list.append(h("li", { class: "list-empty" }, "No snapshots yet. Scan a database to create one."));
      return;
    }
    for (const item of data.items) {
      list.append(
        h(
          "li",
          {},
          h(
            "button",
            {
              type: "button",
              class: `snapshot ${item.metadata_id === state.snapshotId ? "is-active" : ""}`,
              onclick: () => openSnapshot(item.metadata_id),
            },
            h("span", { class: "snapshot-icon" }, icon("db")),
            h(
              "span",
              { class: "snapshot-body" },
              h("span", { class: "snapshot-name" }, item.database_name, item.schema_name ? ` · ${item.schema_name}` : ""),
              h("span", { class: "snapshot-meta" }, `${item.host}:${item.port} · ${item.table_count} tables`),
              h("span", { class: "snapshot-meta" }, formatDate(item.created_at))
            )
          )
        )
      );
    }
  } catch (error) {
    toast(error.message, "error");
  }
}

$("refresh").addEventListener("click", loadSnapshots);

$("connect-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = Object.fromEntries(new FormData(form));
  const body = {
    host: data.host.trim(),
    port: Number(data.port),
    database: data.database.trim(),
    username: data.username.trim(),
    password: data.password,
  };
  if (data.schema_name.trim()) body.schema_name = data.schema_name.trim();
  await busy(form.querySelector("button[type=submit]"), async () => {
    try {
      const detail = await api("POST", "/db/metadata", body);
      form.elements.password.value = "";
      toast(`Extracted ${detail.tables.length} tables from ${detail.database_name}`, "success");
      state.snapshotId = detail.metadata_id;
      await loadSnapshots();
      renderDetail(detail);
    } catch (error) {
      toast(error.message, "error");
    }
  });
});

async function openSnapshot(id) {
  try {
    const detail = await api("GET", `/metadata/${encodeURIComponent(id)}`);
    state.snapshotId = id;
    loadSnapshots();
    renderDetail(detail);
  } catch (error) {
    toast(error.message, "error");
  }
}

async function deleteSnapshot(detail, button) {
  const ok = window.confirm(
    `Delete the snapshot of "${detail.database_name}" (${detail.table_count} tables)? The stored connection is kept.`
  );
  if (!ok) return;
  await busy(button, async () => {
    try {
      await api("DELETE", `/metadata/${encodeURIComponent(detail.metadata_id)}`);
      toast("Snapshot deleted", "success");
      state.snapshotId = null;
      renderEmptyDetail();
      loadSnapshots();
    } catch (error) {
      toast(error.message, "error");
    }
  });
}

// ---------- detail ----------

function renderEmptyDetail() {
  const step = (n, title, text) =>
    h("li", {}, h("span", { class: "step-num" }, String(n)), h("div", {}, h("strong", {}, title), h("span", {}, text)));
  $("detail").replaceChildren(
    h(
      "div",
      { class: "empty card" },
      h("span", { class: "empty-art" }, icon("scan", 32)),
      h("h2", {}, "No snapshot selected"),
      h("p", { class: "muted" }, "Scan a database, or pick an existing snapshot on the left."),
      h(
        "ol",
        { class: "steps" },
        step(1, "Scan", "Enter the connection details and press Extract metadata."),
        step(2, "Classify", "Open a table and classify single columns or the whole table."),
        step(3, "Review", "Click a classified column to see scores, checksum evidence and reasoning.")
      )
    )
  );
}

function overviewTiles(detail) {
  const columns = detail.tables.flatMap((t) => t.columns);
  const done = columns.filter((c) => state.results.has(c.column_id));
  const pii = done.filter((c) => isPii(state.results.get(c.column_id)));
  const share = columns.length ? done.length / columns.length : 0;
  const tile = (labelText, value, sub, extra = "", child = null) =>
    h("div", { class: `tile ${extra}` }, h("span", { class: "tile-label" }, labelText), h("span", { class: "tile-value" }, value), sub ? h("span", { class: "tile-sub" }, sub) : null, child);
  return [
    tile("Tables", String(detail.table_count), plural(detail.tables.filter((t) => t.table_type === "VIEW").length, "view")),
    tile("Columns", String(columns.length)),
    tile("Classified", String(done.length), `${pct(share)} of columns`, "", h("div", { class: "progress" }, h("span", { style: { width: `${share * 100}%` } }))),
    tile("PII found", String(pii.length), done.length ? `${pct(pii.length / done.length)} of classified` : "classify to find out", "tile-pii"),
  ];
}

function refreshOverview() {
  const detail = state.detail;
  if (!detail) return;
  const overview = document.querySelector(".overview");
  if (overview) overview.replaceChildren(...overviewTiles(detail));
  for (const card of document.querySelectorAll("details[data-table]")) {
    const table = detail.tables.find((t) => t.table_name === card.dataset.table);
    const slot = card.querySelector(".table-status");
    if (table && slot) slot.replaceChildren(...tableStatus(table));
  }
}

function tableStatus(table) {
  const done = table.columns.filter((c) => state.results.has(c.column_id));
  if (!done.length) return [];
  const pii = done.filter((c) => isPii(state.results.get(c.column_id))).length;
  return [
    h("span", { class: "muted small" }, `${done.length}/${table.columns.length} classified`),
    pii ? h("span", { class: "tag tag-pii" }, `${pii} PII`) : h("span", { class: "tag tag-safe" }, "no PII"),
  ];
}

function renderDetail(detail) {
  state.detail = detail;
  const deleteButton = h("button", { type: "button", class: "btn btn-danger btn-sm", title: "Delete this snapshot" }, icon("trash", 14), "Delete");
  deleteButton.addEventListener("click", () => deleteSnapshot(detail, deleteButton));

  const sampleInput = h("input", {
    type: "number",
    min: "1",
    max: "100",
    value: String(state.sampleCount),
    onchange: (e) => {
      state.sampleCount = Math.max(1, Number(e.target.value) || 10);
    },
  });
  const filterInput = h("input", { type: "search", placeholder: "Filter tables and columns" });

  const tables = h("div", { class: "tables" });
  const renderTables = () => {
    const query = filterInput.value.trim().toLowerCase();
    tables.replaceChildren();
    for (const table of detail.tables) {
      const tableMatch = table.table_name.toLowerCase().includes(query);
      const columns = table.columns.filter((c) => !query || tableMatch || c.column_name.toLowerCase().includes(query));
      if (!columns.length) continue;
      tables.append(renderTable(table, columns, Boolean(query)));
    }
    if (!tables.childElementCount) tables.append(h("p", { class: "list-empty" }, "Nothing matches the filter."));
  };
  filterInput.addEventListener("input", renderTables);

  $("detail").replaceChildren(
    h(
      "div",
      { class: "card detail-head" },
      h(
        "div",
        { class: "detail-top" },
        h(
          "div",
          { class: "detail-title" },
          h("span", { class: "detail-icon" }, icon("db", 20)),
          h(
            "div",
            {},
            h("h1", {}, detail.database_name, detail.schema_name ? h("span", { class: "muted" }, ` · ${detail.schema_name}`) : null),
            h(
              "div",
              { class: "detail-meta" },
              h("span", {}, `${detail.host}:${detail.port}`),
              h("span", {}, `Scanned ${formatDate(detail.created_at)}`),
              h("span", { class: "mono metadata-id", title: "metadata_id" }, detail.metadata_id)
            )
          )
        ),
        h(
          "div",
          { class: "detail-actions" },
          h("label", { class: "inline", title: "Values read per column for classification (1–100)" }, "Samples", sampleInput),
          deleteButton
        )
      ),
      h("div", { class: "overview" }, overviewTiles(detail))
    ),
    h("div", { class: "toolbar" }, icon("search"), filterInput),
    tables
  );
  renderTables();
}

function renderTable(table, columns, open) {
  const body = h("tbody");
  for (const column of columns) body.append(...renderColumnRows(column));

  const classifyAll = h("button", { type: "button", class: "btn btn-soft btn-sm" }, icon("sparkle", 14), "Classify all");
  classifyAll.addEventListener("click", async (event) => {
    event.preventDefault();
    await busy(classifyAll, async () => {
      for (const column of columns) {
        const button = body.querySelector(`button[data-classify="${column.column_id}"]`);
        if (button) await classifyColumn(column, button);
      }
    });
  });

  const done = columns.filter((c) => state.results.has(c.column_id));

  return h(
    "details",
    { class: "card table-card", open: open || done.length > 0, "data-table": table.table_name },
    h(
      "summary",
      {},
      h("span", { class: "chevron" }, icon("chevron", 14)),
      h("span", { class: "table-name" }, table.table_name),
      h("span", { class: "tag" }, table.table_type),
      h("span", { class: "muted small" }, `${table.columns.length} columns`),
      h("span", { class: "spacer" }),
      h("span", { class: "result table-status" }, tableStatus(table)),
      classifyAll
    ),
    h(
      "table",
      { class: "columns" },
      h(
        "thead",
        {},
        h("tr", {}, h("th", {}, "Column"), h("th", {}, "Type"), h("th", {}, "Classification"), h("th", { class: "right" }, ""))
      ),
      body
    )
  );
}

function isPii(result) {
  return result && result.predicted_category && result.pii_likelihood >= PII_THRESHOLD;
}

function renderColumnRows(column) {
  const result = state.results.get(column.column_id);
  const button = h(
    "button",
    { type: "button", class: "btn btn-sm", "data-classify": column.column_id },
    result ? "Re-run" : "Classify"
  );
  // "Re-run" asks for a fresh LLM answer; "Classify" may be served from the cache.
  button.addEventListener("click", () => classifyColumn(column, button, Boolean(result)));

  const piiRow = isPii(result);
  const row = h(
    "tr",
    { class: `${result ? "has-result" : ""} ${piiRow ? "is-pii" : ""}` },
    h(
      "td",
      {},
      h("span", { class: "col-name" }, column.column_name),
      column.is_nullable ? null : h("span", { class: "tag tag-quiet" }, "not null")
    ),
    h("td", { class: "mono small" }, column.data_type),
    h(
      "td",
      {},
      result
        ? h("span", { class: "result" }, resultSummary(result), h("span", { class: "expand-hint", title: "Show details" }, icon("down", 14)))
        : h("span", { class: "muted small" }, "Not classified")
    ),
    h("td", { class: "right" }, button)
  );
  row.dataset.column = column.column_id;
  if (!result) return [row];

  const detailRow = h("tr", { class: `result-row ${piiRow ? "is-pii" : ""}` }, h("td", { colspan: "4" }, resultDetail(result)));
  detailRow.hidden = true;
  row.classList.add("is-expandable");
  row.addEventListener("click", (event) => {
    if (event.target.closest("button")) return;
    detailRow.hidden = !detailRow.hidden;
    row.classList.toggle("is-open", !detailRow.hidden);
  });
  return [row, detailRow];
}

async function classifyColumn(column, button, forceRefresh = false) {
  await busy(button, async () => {
    try {
      const result = await api("POST", "/classify", {
        column_id: column.column_id,
        sample_count: state.sampleCount,
        force_refresh: forceRefresh,
      });
      state.results.set(column.column_id, result);
      const row = document.querySelector(`tr[data-column="${column.column_id}"]`);
      if (row) {
        const next = row.nextElementSibling;
        if (next && next.classList.contains("result-row")) next.remove();
        const [newRow, detailRow] = renderColumnRows(column);
        row.replaceWith(newRow);
        if (detailRow) {
          newRow.after(detailRow);
          detailRow.hidden = false;
          newRow.classList.add("is-open");
        }
      }
      refreshOverview();
    } catch (error) {
      toast(`${column.column_name}: ${error.message}`, "error");
    }
  });
}

function meter(result) {
  return h(
    "span",
    { class: "meter", title: "PII likelihood" },
    h("span", { class: "meter-track" }, h("span", { class: `meter-fill ${isPii(result) ? "is-pii" : ""}`, style: { width: `${result.pii_likelihood * 100}%` } })),
    `PII ${pct(result.pii_likelihood)}`
  );
}

function resultSummary(result) {
  if (!isPii(result)) {
    return h(
      "span",
      { class: "result" },
      h("span", { class: "badge badge-safe" }, "Not PII"),
      meter(result),
      result.cached ? h("span", { class: "tag", title: "Same samples and settings as an earlier run; no LLM call" }, "cached") : null
    );
  }
  const p = result.classification[result.predicted_category];
  return h(
    "span",
    { class: "result" },
    h("span", { class: "badge badge-pii" }, label(result.predicted_category)),
    h("span", { class: "small", title: "Score of the predicted category" }, pct(p)),
    meter(result),
    result.checksum_evidence.length ? h("span", { class: "tag tag-check", title: "Checksum evidence applied" }, "✓ check") : null,
    result.cached ? h("span", { class: "tag", title: "Same samples and settings as an earlier run; no LLM call" }, "cached") : null
  );
}

function resultDetail(result) {
  const fused = result.classification;
  const llm = result.llm_classification;
  const evidenceApplied = result.checksum_evidence.length > 0;
  const rows = Object.keys(fused)
    .sort((a, b) => fused[b] - fused[a] || llm[b] - llm[a])
    .map((category) =>
      h(
        "div",
        { class: `bar-row ${category === result.predicted_category ? "is-top" : ""}` },
        h("span", { class: "bar-label" }, label(category)),
        h(
          "span",
          { class: "bar-track" },
          evidenceApplied ? h("span", { class: "bar bar-llm", style: { width: `${llm[category] * 100}%` } }) : null,
          h("span", { class: "bar bar-final", style: { width: `${fused[category] * 100}%` } })
        ),
        h(
          "span",
          { class: "bar-value" },
          pct(fused[category]),
          evidenceApplied && Math.abs(fused[category] - llm[category]) >= 0.005 ? h("span", { class: "muted" }, ` (LLM ${pct(llm[category])})`) : null
        )
      )
    );

  // Bars with uniform 1/12 mass carry no information; say so instead.
  const noFit = !result.predicted_category;
  return h(
    "div",
    { class: "result-detail" },
    h(
      "div",
      { class: "result-stats" },
      stat("Prediction", noFit ? "No category fits" : label(result.predicted_category)),
      stat("PII likelihood", pct(result.pii_likelihood), evidenceApplied ? `LLM ${pct(result.llm_pii_likelihood)}` : null),
      stat("Samples", `${result.sample_count_used} / ${result.sample_count_requested}`, `masking: ${result.masking_mode}`),
      stat("Model", result.model, `${result.cached ? "cached, " : ""}${formatDate(result.classified_at)}`)
    ),
    evidenceApplied
      ? h(
          "div",
          { class: "evidence" },
          h("strong", {}, "Checksum evidence"),
          ...result.checksum_evidence.map((e) =>
            h(
              "span",
              { class: "tag tag-check" },
              `${CHECK_LABELS[e.check] || e.check}: ${e.matched}/${e.checked} samples → ${label(e.category)} (weight ${e.weight})`
            )
          )
        )
      : null,
    noFit
      ? h("p", { class: "muted small" }, "The model found no matching PII category; the distribution is uniform.")
      : h("div", { class: "bars" }, rows),
    evidenceApplied
      ? h(
          "p",
          { class: "legend small muted" },
          h("span", { class: "swatch swatch-final" }),
          "final (LLM + checks) ",
          h("span", { class: "swatch swatch-llm" }),
          "LLM only"
        )
      : null,
    result.reasoning ? h("p", { class: "reasoning" }, h("strong", {}, "LLM reasoning: "), result.reasoning) : null,
    h("p", { class: "disclaimer" }, "Scores are model confidence, not calibrated probabilities.")
  );
}

function stat(name, value, sub) {
  return h(
    "div",
    { class: "stat" },
    h("span", { class: "stat-name" }, name),
    h("span", { class: "stat-value" }, value),
    sub ? h("span", { class: "stat-sub" }, sub) : null
  );
}

// ---------- boot ----------

state.token = storage((s) => s.getItem(TOKEN_KEY));
state.user = storage((s) => s.getItem(USER_KEY));
if (state.token) showApp();
