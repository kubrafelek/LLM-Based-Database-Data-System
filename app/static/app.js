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
};
// Same cut-off the evaluation script uses to call a column PII.
const PII_THRESHOLD = 0.5;

const state = {
  token: null,
  user: null,
  snapshotId: null,
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
const pct = (p) => `${Math.round(p * 100)}%`;
const label = (category) => CATEGORY_LABELS[category] || category;

function storage(fn) {
  try {
    return fn(window.sessionStorage);
  } catch {
    return null;
  }
}

function toast(message, kind = "info") {
  const el = h("div", { class: `toast toast-${kind}` }, message);
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
  loadSnapshots();
}

function signOut() {
  state.token = null;
  state.user = null;
  state.snapshotId = null;
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
      list.append(h("li", { class: "muted small" }, "No snapshots yet."));
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
            h("span", { class: "snapshot-name" }, item.database_name, item.schema_name ? ` · ${item.schema_name}` : ""),
            h(
              "span",
              { class: "snapshot-meta" },
              `${item.host}:${item.port} · ${item.table_count} tables · ${new Date(item.created_at).toLocaleString()}`
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
  $("detail").replaceChildren(
    h(
      "div",
      { class: "empty card" },
      h("h2", {}, "No snapshot selected"),
      h("p", { class: "muted" }, "Extract metadata from a database, or pick a snapshot on the left.")
    )
  );
}

function renderDetail(detail) {
  const deleteButton = h("button", { type: "button", class: "btn btn-danger btn-sm" }, "Delete snapshot");
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
    if (!tables.childElementCount) tables.append(h("p", { class: "muted" }, "Nothing matches the filter."));
  };
  filterInput.addEventListener("input", renderTables);

  const columnCount = detail.tables.reduce((n, t) => n + t.columns.length, 0);
  $("detail").replaceChildren(
    h(
      "div",
      { class: "card detail-head" },
      h(
        "div",
        {},
        h("h1", {}, detail.database_name, detail.schema_name ? h("span", { class: "muted" }, ` · ${detail.schema_name}`) : null),
        h(
          "p",
          { class: "muted small" },
          `${detail.host}:${detail.port} · ${detail.table_count} tables · ${columnCount} columns · ${new Date(
            detail.created_at
          ).toLocaleString()}`
        ),
        h("p", { class: "mono small muted" }, `metadata_id ${detail.metadata_id}`)
      ),
      h(
        "div",
        { class: "detail-actions" },
        h("label", { class: "inline" }, "Samples", sampleInput),
        deleteButton
      )
    ),
    h("div", { class: "toolbar" }, filterInput),
    tables
  );
  renderTables();
}

function renderTable(table, columns, open) {
  const body = h("tbody");
  for (const column of columns) body.append(...renderColumnRows(column));

  const classifyAll = h("button", { type: "button", class: "btn btn-ghost btn-sm" }, "Classify all");
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
  const piiCount = done.filter((c) => isPii(state.results.get(c.column_id))).length;

  return h(
    "details",
    { class: "card table-card", open: open || done.length > 0 },
    h(
      "summary",
      {},
      h("span", { class: "table-name" }, table.table_name),
      h("span", { class: "tag" }, table.table_type),
      h("span", { class: "muted small" }, `${table.columns.length} columns`),
      done.length ? h("span", { class: "tag tag-pii" }, `${piiCount} PII`) : null,
      h("span", { class: "spacer" }),
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
  button.addEventListener("click", () => classifyColumn(column, button));

  const row = h(
    "tr",
    { class: result ? "has-result" : "" },
    h(
      "td",
      {},
      h("span", { class: "col-name" }, column.column_name),
      column.is_nullable ? null : h("span", { class: "tag tag-quiet" }, "not null")
    ),
    h("td", { class: "mono small" }, column.data_type),
    h("td", {}, result ? resultSummary(result) : h("span", { class: "muted small" }, "—")),
    h("td", { class: "right" }, button)
  );
  row.dataset.column = column.column_id;
  if (!result) return [row];

  const detailRow = h("tr", { class: "result-row" }, h("td", { colspan: "4" }, resultDetail(result)));
  detailRow.hidden = true;
  row.classList.add("is-expandable");
  row.addEventListener("click", (event) => {
    if (event.target.closest("button")) return;
    detailRow.hidden = !detailRow.hidden;
    row.classList.toggle("is-open", !detailRow.hidden);
  });
  return [row, detailRow];
}

async function classifyColumn(column, button) {
  await busy(button, async () => {
    button.textContent = "…";
    try {
      const result = await api("POST", "/classify", { column_id: column.column_id, sample_count: state.sampleCount });
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
    } catch (error) {
      toast(`${column.column_name}: ${error.message}`, "error");
    }
  });
}

function resultSummary(result) {
  if (!isPii(result)) {
    return h(
      "span",
      { class: "result" },
      h("span", { class: "badge badge-safe" }, "Not PII"),
      h("span", { class: "muted small" }, `PII ${pct(result.pii_likelihood)}`)
    );
  }
  const p = result.classification[result.predicted_category];
  return h(
    "span",
    { class: "result" },
    h("span", { class: "badge badge-pii" }, label(result.predicted_category)),
    h("span", { class: "small" }, pct(p)),
    h("span", { class: "muted small" }, `PII ${pct(result.pii_likelihood)}`),
    result.checksum_evidence.length ? h("span", { class: "tag tag-check", title: "Checksum evidence applied" }, "✓ check") : null
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
      stat("Model", result.model)
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
    result.reasoning ? h("p", { class: "reasoning" }, h("strong", {}, "LLM reasoning: "), result.reasoning) : null
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
