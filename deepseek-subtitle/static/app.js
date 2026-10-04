const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const MODE_LABELS = { bilingual: "中英双语", zh: "中文", en: "英文", orig: "原文+中文" };
const LANG_NAMES = {
  zh: "中文", en: "英语", ja: "日语", ko: "韩语", fr: "法语", de: "德语", es: "西班牙语",
  ru: "俄语", pt: "葡萄牙语", it: "意大利语", ar: "阿拉伯语", th: "泰语", vi: "越南语",
  id: "印尼语", ms: "马来语", hi: "印地语", tr: "土耳其语", nl: "荷兰语", pl: "波兰语",
  uk: "乌克兰语", yue: "粤语", el: "希腊语",
};
const IMAGE_TARGETS = { zh: "中文", en: "英文", orig_zh: "原文+中文", zh_en: "中英双语" };

const store = {
  get(key) { try { return localStorage.getItem(key) || ""; } catch { return ""; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch {} },
};

function showError(el, msg) {
  el.textContent = msg || "";
  el.hidden = !msg;
}

async function errorText(res, fallback) {
  try { return (await res.json()).detail || fallback; } catch { return fallback; }
}

// ---------------------------------------------------------------- shell
$$(".main-tab").forEach((tab) => tab.addEventListener("click", () => {
  $$(".main-tab").forEach((t) => t.classList.toggle("active", t === tab));
  $$(".panel").forEach((p) => (p.hidden = p.id !== tab.dataset.panel));
}));

$$(".sub-tab").forEach((tab) => tab.addEventListener("click", () => {
  $$(".sub-tab").forEach((t) => t.classList.toggle("active", t === tab));
  $$(".tab-body").forEach((b) => (b.hidden = b.id !== tab.dataset.tab));
}));

// A row of buttons acting as one choice; returns a getter for the chosen value.
function segmented(root, onChange) {
  $$("button", root).forEach((btn) => btn.addEventListener("click", () => {
    $$("button", root).forEach((b) => b.classList.toggle("active", b === btn));
    onChange(btn.dataset.value);
  }));
  return () => $("button.active", root).dataset.value;
}

// ---------------------------------------------------------------- settings
const apiKeyInput = $("#api-key");
const termsInput = $("#terms");
apiKeyInput.value = store.get("deepseek_key");
termsInput.value = store.get("terms");
apiKeyInput.addEventListener("change", () => store.set("deepseek_key", apiKeyInput.value.trim()));
termsInput.addEventListener("change", () => store.set("terms", termsInput.value));
$("#toggle-key").addEventListener("click", (e) => {
  const show = apiKeyInput.type === "password";
  apiKeyInput.type = show ? "text" : "password";
  e.target.textContent = show ? "隐藏" : "显示";
});

let serverKey = false;
let voices = {};
fetch("/api/config").then((r) => r.json()).then((cfg) => {
  serverKey = cfg.server_key_configured;
  if (serverKey) apiKeyInput.placeholder = "服务端已配置 Key，可留空";
  for (const select of [$("#ocr-lang"), $("#screen-lang")]) {
    for (const [value, label] of Object.entries(cfg.ocr_langs || {})) select.add(new Option(label, value));
  }
  updateTargetUi();
}).catch(() => {});
fetch("/api/voices").then((r) => r.json()).then((v) => (voices = v)).catch(() => {});

// ---------------------------------------------------------------- upload
const fileInput = $("#file-input");
const dropzone = $("#dropzone");
const startBtn = $("#start-btn");
const targetInput = $("#target");
const languageSelect = $("#language");
let model = store.get("model") || "small";

segmented($("#target-seg"), (value) => { targetInput.value = value; updateTargetUi(); });
$$(".model-card").forEach((card) => {
  card.classList.toggle("active", card.dataset.value === model);
  card.addEventListener("click", () => {
    model = card.dataset.value;
    store.set("model", model);
    $$(".model-card").forEach((c) => c.classList.toggle("active", c === card));
  });
});

// Chinese-only output: Chinese recognition, no translation (so no "精翻"), key optional.
function updateTargetUi() {
  const zhOnly = targetInput.value === "zh";
  languageSelect.disabled = zhOnly;
  if (zhOnly) languageSelect.value = "zh";
  $("#reflect").disabled = zhOnly;
  $("#key-label").textContent = zhOnly && !serverKey
    ? "DeepSeek API Key（仅中文模式可不填，填写后会校正识别文本）" : "DeepSeek API Key";
}

const MEDIA_RE = /\.(mp3|mp4|m4a|wav|flac|ogg|aac|webm|mov|mkv)$/i;
let selectedFiles = [];

function pickFiles(files) {
  selectedFiles = files.filter((f) => MEDIA_RE.test(f.name) || /^(audio|video)\//.test(f.type));
  if (!selectedFiles.length) return;
  const size = selectedFiles.reduce((n, f) => n + f.size, 0) / 1024 / 1024;
  $("#drop-text").textContent = selectedFiles.length === 1
    ? `${selectedFiles[0].name}（${size.toFixed(1)} MB）`
    : `已选择 ${selectedFiles.length} 个文件（共 ${size.toFixed(1)} MB），将按顺序批量处理`;
  startBtn.disabled = false;
  startBtn.textContent = selectedFiles.length > 1 ? `批量生成 ${selectedFiles.length} 个文件的字幕` : "开始生成字幕";
}
function setupDrop(zone, onFiles) {
  ["dragenter", "dragover"].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.add("over"); }));
  ["dragleave", "drop"].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.remove("over"); }));
  zone.addEventListener("drop", (e) => onFiles(Array.from(e.dataTransfer.files)));
}
fileInput.addEventListener("change", () => { pickFiles(Array.from(fileInput.files)); fileInput.value = ""; });
setupDrop(dropzone, pickFiles);

const STEPS = ["upload", "transcribe", "translate", "done"];
function setStep(step) {
  const index = STEPS.indexOf(step);
  $$("#stepper li").forEach((li, i) => {
    li.classList.toggle("done", i < index || step === "done");
    li.classList.toggle("active", i === index && step !== "done");
  });
}
function setProgress(stage, p) {
  $("#stage").textContent = stage;
  $("#percent").textContent = `${Math.round(p * 100)}%`;
  $("#bar-fill").style.width = `${p * 100}%`;
}

let jobId = null; // the job shown in the results area
let job = null;

// ---------------------------------------------------------------- queue (one or many files)
const queue = []; // {file, name, id, status, stage, progress, error, burn}
let polling = false;

function uploadOptions() {
  return {
    target: targetInput.value,
    language: languageSelect.value,
    api_key: apiKeyInput.value.trim(),
    reflect: $("#reflect").checked ? "1" : "0",
    correct: $("#correct").checked ? "1" : "0",
    terms: termsInput.value,
    model,
  };
}

function upload(item, options) {
  return new Promise((resolve) => {
    const form = new FormData();
    form.append("file", item.file);
    for (const [k, v] of Object.entries(options)) form.append(k, v);
    // XHR instead of fetch so we can show upload progress.
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/jobs");
    xhr.upload.onprogress = (e) => {
      if (!e.lengthComputable) return;
      item.stage = `上传中 ${Math.round((e.loaded / e.total) * 100)}%`;
      item.progress = 0;
      renderQueue();
    };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch {}
      if (xhr.status >= 400) Object.assign(item, { status: "error", stage: "出错", error: data.detail || `上传失败（${xhr.status}）` });
      else Object.assign(item, { id: data.id, status: "queued", stage: "排队中" });
      renderQueue();
      resolve();
    };
    xhr.onerror = () => {
      Object.assign(item, { status: "error", stage: "出错", error: "网络错误，上传失败" });
      renderQueue();
      resolve();
    };
    xhr.send(form);
  });
}

startBtn.addEventListener("click", async () => {
  if (!selectedFiles.length) return;
  showError($("#upload-error"), "");
  showError($("#job-error"), "");
  const options = uploadOptions();
  const items = selectedFiles.map((file) => ({ file, name: file.name, status: "uploading", stage: "等待上传", progress: 0 }));
  queue.push(...items);
  selectedFiles = [];
  startBtn.disabled = true;
  startBtn.textContent = "开始生成字幕";
  $("#drop-text").textContent = "点击或拖拽音频 / 视频到这里（可一次选多个，批量处理）";
  $("#progress-card").hidden = false;
  renderQueue();
  pollQueue();
  for (const item of items) await upload(item, options); // one after another
  const failed = items.find((i) => i.status === "error");
  if (items.length === 1 && failed) showError($("#upload-error"), failed.error);
});

const finished = (item) => ["done", "error", "cancelled"].includes(item.status);
const ACTIVE = ["queued", "transcribing", "translating"];

async function cancelItem(item) {
  if (!item.id) return;
  const res = await fetch(`/api/jobs/${item.id}/cancel`, { method: "POST" });
  if (res.ok && item.status === "queued") Object.assign(item, { status: "cancelled", stage: "已取消" });
  else if (res.ok) item.stage = "正在取消…";
  renderQueue();
}
const busy = (item) => !finished(item) || (item.burn && item.burn.status === "running");

async function pollQueue() {
  if (polling) return;
  polling = true;
  while (queue.some(busy)) {
    const ids = queue.filter((i) => i.id && busy(i)).map((i) => i.id);
    if (ids.length) {
      try {
        const list = await (await fetch(`/api/jobs?ids=${ids.join(",")}`)).json();
        for (const brief of list) {
          const item = queue.find((i) => i.id === brief.id);
          const wasDone = item.status === "done";
          Object.assign(item, brief);
          if (!wasDone && item.status === "done" && !jobId) openJob(item.id); // show the first result
        }
      } catch {}
    }
    renderQueue();
    await new Promise((r) => setTimeout(r, 1500));
  }
  renderQueue();
  polling = false;
}

// The progress card follows the first unfinished file (or the last one).
function renderProgress() {
  const focus = queue.find((i) => !finished(i)) || queue[queue.length - 1];
  if (!focus) return;
  const n = queue.length > 1 ? `（${queue.indexOf(focus) + 1}/${queue.length}）${focus.name} · ` : "";
  setProgress(n + focus.stage, focus.status === "uploading" ? 0 : focus.progress || 0);
  setStep(focus.status === "uploading" ? "upload"
    : ({ translating: "translate", done: "done" }[focus.status] || "transcribe"));
  showError($("#job-error"), queue.length === 1 && focus.status === "error" ? focus.error : "");
  const cancel = $("#cancel-btn");
  cancel.hidden = !(focus.id && ACTIVE.includes(focus.status));
  cancel.textContent = queue.length > 1 ? "取消当前文件" : "取消";
  cancel.onclick = () => cancelItem(focus);
}

function renderQueue() {
  renderProgress();
  $("#queue-card").hidden = queue.length < 2;
  const done = queue.filter((i) => i.status === "done").length;
  $("#queue-count").textContent = `${done} / ${queue.length} 个已完成`;
  const list = $("#queue-list");
  list.innerHTML = "";
  for (const item of queue) {
    const li = document.createElement("li");
    li.className = "queue-item" + (item.id && item.id === jobId ? " selected" : "");
    const info = document.createElement("div");
    const name = document.createElement("div");
    name.className = "queue-name";
    name.textContent = item.name;
    name.title = item.name;
    const stage = document.createElement("div");
    stage.className = "queue-stage";
    stage.textContent = item.status === "error" ? `出错：${item.error}` : item.stage;
    info.append(name, stage);

    const bar = document.createElement("div");
    bar.className = "bar";
    const fill = document.createElement("div");
    fill.style.width = `${(item.status === "done" ? 1 : item.progress || 0) * 100}%`;
    bar.appendChild(fill);

    const btns = document.createElement("div");
    btns.className = "queue-btns";
    if (item.burn) {
      const b = item.burn;
      const el = document.createElement(b.status === "done" ? "a" : "span");
      el.className = "badge" + (b.status === "done" ? " done" : b.status === "error" ? " error" : "");
      el.textContent = b.status === "done" ? "⬇ 烧录视频" : b.status === "error" ? "烧录失败" : `烧录 ${Math.round((b.progress || 0) * 100)}%`;
      if (b.status === "done") el.href = `/api/jobs/${item.id}/burned.mp4`;
      if (b.status === "error") el.title = b.error;
      btns.appendChild(el);
    }
    if (item.id && ACTIVE.includes(item.status)) {
      const cancel = document.createElement("button");
      cancel.className = "ghost small";
      cancel.textContent = "取消";
      cancel.onclick = () => cancelItem(item);
      btns.appendChild(cancel);
    }
    if (item.status === "done") {
      const view = document.createElement("button");
      view.className = "ghost small";
      view.textContent = item.id === jobId ? "正在查看" : "查看";
      view.disabled = item.id === jobId;
      view.onclick = () => openJob(item.id);
      btns.appendChild(view);
    }
    li.append(info, bar, btns);
    list.appendChild(li);
  }
}

async function openJob(id) {
  try {
    const data = await (await fetch(`/api/jobs/${id}`)).json();
    if (dirty && jobId && jobId !== id && !confirm("当前字幕有未保存的修改，切换后会丢失，继续吗？")) return;
    jobId = id;
    showResult(data);
    renderQueue();
  } catch {}
}

function batchBody() {
  return JSON.stringify({ ids: queue.filter((i) => i.id).map((i) => i.id), mode: $("#batch-mode").value, fmt: $("#batch-fmt").value });
}

$("#batch-zip").addEventListener("click", async () => {
  showError($("#queue-error"), "");
  if (dirty && !(await save())) return;
  try {
    await fetchDownload("/api/batch/subtitles.zip", { method: "POST", headers: { "Content-Type": "application/json" }, body: batchBody() });
  } catch (err) {
    showError($("#queue-error"), err.message);
  }
});

$("#batch-burn").addEventListener("click", async () => {
  showError($("#queue-error"), "");
  if (dirty && !(await save())) return;
  const res = await fetch("/api/batch/burn", { method: "POST", headers: { "Content-Type": "application/json" }, body: batchBody() });
  if (!res.ok) return showError($("#queue-error"), await errorText(res, `烧录失败（${res.status}）`));
  const { started } = await res.json();
  if (!started.length) return showError($("#queue-error"), "没有可以烧录的任务（需要先处理完成）");
  for (const item of queue) if (started.includes(item.id)) item.burn = { status: "running", progress: 0 };
  renderQueue();
  pollQueue();
});

// ---------------------------------------------------------------- results
const player = $("#player");
const list = $("#segments");
const saveBtn = $("#save-btn");
let segments = [];
let mode = "bilingual";
let track = null;
let dirty = false;

function availableModes() {
  if (job.target === "zh") return ["zh"];
  const modes = ["bilingual", "zh", "en"];
  if (job.language && !["zh", "en"].includes(job.language)) modes.push("orig");
  return modes;
}
function showOriginal() {
  return job.target !== "zh" && job.language && !["zh", "en"].includes(job.language);
}
function fillModes(select, extra) {
  select.innerHTML = "";
  if (extra) select.add(new Option(extra[1], extra[0]));
  for (const m of availableModes()) select.add(new Option(MODE_LABELS[m], m));
}

function showResult(data) {
  job = data;
  segments = data.segments;
  dirty = false;
  saveBtn.disabled = true;
  $("#result-card").hidden = false;
  const lang = LANG_NAMES[data.language] || data.language || "-";
  $("#seg-count").textContent = `${segments.length} 条字幕 · 识别语言：${lang}`;

  const isAudio = /\.(mp3|m4a|wav|flac|ogg|aac)$/i.test(data.filename || "");
  player.classList.toggle("audio-only", isAudio);
  player.src = `/api/jobs/${jobId}/media`;
  // Cues are built in JS so edits and mode switches show up instantly.
  if (!track) {
    track = player.addTextTrack("subtitles", "字幕", "zh");
    track.mode = "showing";
  }
  mode = availableModes()[0];
  renderModeToggle();
  renderList();
  renderCues();
  renderDownloads();
  renderBurn();
  renderDub();
  renderScreen();
  renderSummary();
  $("#no-speech").hidden = segments.length > 0;
  $("#result-card").scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderModeToggle() {
  const box = $("#mode-toggle");
  box.innerHTML = "";
  const modes = availableModes();
  box.hidden = modes.length < 2;
  for (const m of modes) {
    const btn = document.createElement("button");
    btn.textContent = MODE_LABELS[m];
    btn.classList.toggle("active", m === mode);
    btn.onclick = () => {
      mode = m;
      $$("button", box).forEach((b) => b.classList.toggle("active", b === btn));
      renderCues();
    };
    box.appendChild(btn);
  }
}

// Mirrors cue_lines() in app/subtitles.py.
function cueText(s) {
  const zh = s.zh || s.text;
  const en = s.en || s.text;
  if (mode === "zh") return zh;
  if (mode === "en") return en;
  if (mode === "orig") return s.text === zh ? zh : `${s.text}\n${zh}`;
  return zh === en ? zh : `${zh}\n${en}`;
}

function renderCues() {
  if (!track) return;
  Array.from(track.cues || []).forEach((c) => track.removeCue(c));
  for (const s of segments) {
    const cue = new VTTCue(s.start, s.end, cueText(s));
    cue.line = -2;
    track.addCue(cue);
  }
}

function fmtTime(t) {
  const m = Math.floor(t / 60);
  const s = (t % 60).toFixed(2).padStart(5, "0");
  return `${String(m).padStart(2, "0")}:${s}`;
}

function renderList() {
  list.innerHTML = "";
  const keys = job.target === "zh" ? ["zh"] : showOriginal() ? ["text", "zh", "en"] : ["zh", "en"];
  for (const s of segments) {
    const li = document.createElement("li");
    li.dataset.id = s.id;
    const time = document.createElement("span");
    time.className = "time";
    time.textContent = `${fmtTime(s.start)}\n${fmtTime(s.end)}`;
    time.style.whiteSpace = "pre";
    time.title = "跳转到此处";
    time.onclick = () => { player.currentTime = s.start; player.play(); };
    const texts = document.createElement("div");
    texts.className = "texts";
    for (const key of keys) {
      const d = document.createElement("div");
      d.className = key === "text" ? "orig" : key;
      d.contentEditable = "plaintext-only";
      d.spellcheck = false;
      d.textContent = s[key] || s.text;
      d.addEventListener("input", () => {
        s[key] = d.textContent.trim();
        dirty = true;
        saveBtn.disabled = false;
        renderCues();
      });
      texts.appendChild(d);
    }
    li.append(time, texts);
    list.appendChild(li);
  }
}

// Highlight & scroll to the current line while playing.
let currentId = null;
player.addEventListener("timeupdate", () => {
  const t = player.currentTime;
  const seg = segments.find((s) => t >= s.start && t < s.end);
  const id = seg ? String(seg.id) : null;
  if (id === currentId) return;
  currentId = id;
  $$("li.current", list).forEach((el) => el.classList.remove("current"));
  if (!id) return;
  const li = $(`li[data-id="${id}"]`, list);
  if (li) {
    li.classList.add("current");
    if (!li.contains(document.activeElement)) li.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
});

async function save() {
  saveBtn.disabled = true;
  const res = await fetch(`/api/jobs/${jobId}/segments`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ segments: segments.map(({ id, text, zh, en }) => ({ id, text, zh, en })) }),
  });
  if (res.ok) {
    dirty = false;
    saveBtn.textContent = "已保存 ✓";
    setTimeout(() => (saveBtn.textContent = "保存修改"), 1500);
    return true;
  }
  saveBtn.disabled = false;
  alert("保存失败");
  return false;
}
saveBtn.addEventListener("click", save);

// ---------------------------------------------------------------- export
const FORMATS = [
  { key: "srt", label: "SRT 字幕" },
  { key: "vtt", label: "VTT 字幕" },
  { key: "lrc", label: "LRC 歌词" },
  { key: "mp3", label: "带字幕 MP3" },
];

function downloadUrl(fmt, m) {
  return fmt === "mp3"
    ? `/api/jobs/${jobId}/export.mp3?mode=${m}`
    : `/api/jobs/${jobId}/subtitle.${fmt}?mode=${m}&download=true`;
}

function renderDownloads() {
  const table = $("#dl-table");
  table.innerHTML = "";
  for (const f of FORMATS) {
    const tr = document.createElement("tr");
    const th = document.createElement("th");
    th.textContent = f.label;
    tr.appendChild(th);
    for (const m of availableModes()) {
      const td = document.createElement("td");
      const a = document.createElement("a");
      a.textContent = MODE_LABELS[m];
      a.href = downloadUrl(f.key, m);
      a.addEventListener("click", (e) => onDownload(e, a, f.key));
      td.appendChild(a);
      tr.appendChild(td);
    }
    table.appendChild(tr);
  }
}

async function fetchDownload(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) throw new Error(await errorText(res, `下载失败（${res.status}）`));
  const blob = await res.blob();
  const header = res.headers.get("Content-Disposition") || "";
  const match = /filename\*=UTF-8''([^;]+)/.exec(header) || /filename="?([^";]+)"?/.exec(header);
  const name = match && [null, match[1]];
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = name ? decodeURIComponent(name[1]) : "download";
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 10000);
}

async function onDownload(e, a, fmt) {
  if (fmt !== "mp3" && !dirty) return; // plain link download
  e.preventDefault();
  if (dirty && !(await save())) return; // unsaved edits would be missing from the file
  if (fmt !== "mp3") {
    window.location.href = a.href;
    return;
  }
  // MP3 export can take a while (videos are converted first): show progress.
  const label = a.textContent;
  a.textContent = "导出中…";
  a.classList.add("busy");
  try { await fetchDownload(a.href); } catch (err) { alert(err.message); }
  a.textContent = label;
  a.classList.remove("busy");
}

// ---------------------------------------------------------------- background tasks (burn / dub)
const timers = {};
function pollTask(field, show) {
  clearTimeout(timers[field]);
  const current = jobId;
  fetch(`/api/jobs/${current}`).then((r) => r.json()).then((data) => {
    if (current !== jobId) return; // a new file was uploaded meanwhile
    job[field] = data[field];
    show(data[field]);
    if (data[field] && data[field].status === "running") timers[field] = setTimeout(() => pollTask(field, show), 1500);
  }).catch(() => { timers[field] = setTimeout(() => pollTask(field, show), 3000); });
}

function showTask(prefix, state, labels) {
  const status = state ? state.status : "idle";
  $(`#${prefix}-btn`).disabled = status === "running";
  $(`#${prefix}-progress`).hidden = status !== "running";
  if (status === "running") {
    const p = state.progress || 0;
    $(`#${prefix}-stage`).textContent = labels.running(state);
    $(`#${prefix}-percent`).textContent = `${Math.round(p * 100)}%`;
    $(`#${prefix}-fill`).style.width = `${p * 100}%`;
  }
  const link = $(`#${prefix}-download`);
  link.hidden = status !== "done";
  if (status === "done") {
    link.href = labels.url(state);
    link.textContent = labels.done(state);
  }
  showError($(`#${prefix}-error`), status === "error" ? `失败：${state.error}` : "");
}

const BURN_LABELS = {
  running: (s) => `烧录中（${MODE_LABELS[s.mode]}），视频越长越慢`,
  done: (s) => `下载烧录好的视频（${MODE_LABELS[s.mode]}）`,
  url: () => `/api/jobs/${jobId}/burned.mp4`,
};
const showBurn = (state) => {
  showTask("burn", state, BURN_LABELS);
  if (job) updateBurnScreen(); // also depends on subtitles / on-screen text being there
};

function renderBurn() {
  fillModes($("#burn-mode"));
  if (!segments.length) {
    $("#burn-mode").innerHTML = "";
    $("#burn-mode").add(new Option("没有对白字幕", "zh"));
  }
  updateBurnScreen();
  showBurn(job.burn);
  if (job.burn && job.burn.status === "running") pollTask("burn", showBurn);
}

$("#burn-btn").addEventListener("click", async () => {
  if (dirty && !(await save())) return; // burn what the user sees
  $("#burn-btn").disabled = true;
  const burnMode = $("#burn-mode").value;
  const screen = $("#burn-screen").value || null;
  const res = await fetch(`/api/jobs/${jobId}/burn`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mode: burnMode, screen, subtitles: segments.length > 0 }),
  });
  if (!res.ok) {
    showError($("#burn-error"), await errorText(res, `烧录失败（${res.status}）`));
    $("#burn-btn").disabled = false;
    return;
  }
  showBurn({ status: "running", mode: burnMode, progress: 0 });
  pollTask("burn", showBurn);
});

// ---------------------------------------------------------------- text in the video picture
const SCREEN_LABELS = {
  running: (s) => s.stage || "识别画面文字中",
  done: () => "下载画面文字字幕（SRT）",
  url: () => `/api/jobs/${jobId}/screen.srt`,
};
let screenEvents = [];
let screenSize = null;
let screenTarget = "orig_zh";

function showScreen(state) {
  showTask("screen", state, SCREEN_LABELS);
  if (state && state.status === "done") {
    screenEvents = state.events || [];
    screenSize = state.size;
    screenTarget = state.target;
    renderScreenList();
    if (!screenEvents.length) showError($("#screen-error"), "画面里没有识别到文字");
  }
  updateBurnScreen();
  drawOverlay();
}

function renderScreen() {
  screenEvents = [];
  screenSize = null;
  $("#screen-list").innerHTML = "";
  showScreen(job.screen);
  if (job.screen && job.screen.status === "running") pollTask("screen", showScreen);
}

// The burn options for on-screen text only make sense once it has been recognised.
function updateBurnScreen() {
  const ready = job && job.screen && job.screen.status === "done" && (job.screen.events || []).length > 0;
  $("#burn-screen").disabled = !ready;
  if (!ready) $("#burn-screen").value = "";
  $("#burn-screen-hint").hidden = ready;
  $("#burn-btn").disabled = (!segments.length && !ready) || (job.burn && job.burn.status === "running");
}

function screenText(e) {
  if (screenTarget === "zh") return e.zh || e.text;
  if (screenTarget === "en") return e.en || e.text;
  return [e.zh, e.en].filter((t) => t && t !== e.text).join(" / ") || e.text;
}

function renderScreenList() {
  const ol = $("#screen-list");
  ol.innerHTML = "";
  for (const e of screenEvents) {
    const li = document.createElement("li");
    const time = document.createElement("span");
    time.className = "time";
    time.style.whiteSpace = "pre";
    time.textContent = `${fmtTime(e.start)}
${fmtTime(e.end)}`;
    time.onclick = () => { player.currentTime = e.start + 0.05; player.play(); };
    const texts = document.createElement("div");
    const orig = document.createElement("div");
    orig.className = "orig";
    orig.textContent = e.text;
    const tr = document.createElement("div");
    tr.textContent = screenText(e);
    texts.append(orig, tr);
    li.append(time, texts);
    ol.appendChild(li);
  }
}

// Translations drawn over the picture, where the original text is.
function drawOverlay() {
  const layer = $("#screen-overlay");
  layer.innerHTML = "";
  if (!$("#screen-show").checked || !screenEvents.length || !(screenSize || player.videoWidth)) return;
  const t = player.currentTime;
  const w = player.clientWidth, h = player.clientHeight;
  const vw = screenSize ? screenSize[0] : player.videoWidth, vh = screenSize ? screenSize[1] : player.videoHeight;
  const scale = Math.min(w / vw, h / vh); // object-fit: contain
  const ox = (w - vw * scale) / 2, oy = (h - vh * scale) / 2;
  const below = screenTarget === "orig_zh" || screenTarget === "zh_en";
  for (const e of screenEvents) {
    if (t < e.start || t >= e.end) continue;
    const xs = e.box.map((p) => p[0]), ys = e.box.map((p) => p[1]);
    const x0 = Math.min(...xs), y0 = Math.min(...ys), x1 = Math.max(...xs), y1 = Math.max(...ys);
    const boxH = (y1 - y0) * scale;
    const div = document.createElement("div");
    div.textContent = screenText(e);
    div.style.left = `${ox + x0 * scale}px`;
    div.style.maxWidth = `${Math.max((x1 - x0) * scale, w * 0.5)}px`;
    div.style.fontSize = `${Math.max(11, boxH * (below ? 0.5 : 0.7))}px`;
    div.style.top = `${oy + (below ? y1 * scale + 2 : y0 * scale)}px`;
    if (!below) div.style.minHeight = `${boxH}px`;
    layer.appendChild(div);
  }
}
player.addEventListener("timeupdate", drawOverlay);
player.addEventListener("seeked", drawOverlay);
window.addEventListener("resize", drawOverlay);
$("#screen-show").addEventListener("change", drawOverlay);

$("#screen-btn").addEventListener("click", async () => {
  $("#screen-btn").disabled = true;
  showError($("#screen-error"), "");
  const body = {
    target: $("#screen-target").value,
    ocr_lang: $("#screen-lang").value || "auto",
    interval: Number($("#screen-interval").value),
    api_key: apiKeyInput.value.trim(),
    terms: termsInput.value,
  };
  const res = await fetch(`/api/jobs/${jobId}/screen`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  if (!res.ok) {
    showError($("#screen-error"), await errorText(res, `识别失败（${res.status}）`));
    $("#screen-btn").disabled = false;
    return;
  }
  showScreen({ status: "running", progress: 0, stage: "排队中" });
  pollTask("screen", showScreen);
});

// ---------------------------------------------------------------- AI dubbing
const DUB_LABELS = {
  running: (s) => `${s.progress < 0.7 ? "合成配音中" : "混音、合成视频中"}（${s.lang === "zh" ? "中文" : "英文"}）`,
  done: (s) => `下载配音结果（${s.file && s.file.endsWith(".mp3") ? "MP3" : "MP4"}）`,
  url: () => `/api/jobs/${jobId}/dubbed`,
};
const showDub = (state) => showTask("dub", state, DUB_LABELS);

function fillVoices() {
  const select = $("#dub-voice");
  select.innerHTML = "";
  for (const [id, label] of voices[$("#dub-lang").value] || []) select.add(new Option(label, id));
}

function renderDub() {
  const langSelect = $("#dub-lang");
  langSelect.innerHTML = "";
  langSelect.add(new Option("中文配音", "zh"));
  if (job.target !== "zh") langSelect.add(new Option("英文配音", "en"));
  fillVoices();
  fillModes($("#dub-burn"), ["", "不烧录"]);
  showDub(job.dub);
  if (job.dub && job.dub.status === "running") pollTask("dub", showDub);
}

$("#dub-lang").addEventListener("change", fillVoices);
$("#dub-bg").addEventListener("input", (e) => ($("#bg-value").textContent = `${e.target.value}%`));
$("#dub-btn").addEventListener("click", async () => {
  if (dirty && !(await save())) return;
  $("#dub-btn").disabled = true;
  const body = {
    lang: $("#dub-lang").value,
    voice: $("#dub-voice").value,
    bg_volume: Number($("#dub-bg").value) / 100,
    burn_mode: $("#dub-burn").value || null,
  };
  const res = await fetch(`/api/jobs/${jobId}/dub`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  if (!res.ok) {
    showError($("#dub-error"), await errorText(res, `配音失败（${res.status}）`));
    $("#dub-btn").disabled = false;
    return;
  }
  showDub({ status: "running", lang: body.lang, progress: 0 });
  pollTask("dub", showDub);
});

// ---------------------------------------------------------------- topic & terms
function renderSummary() {
  const terms = job.terms || [];
  $("#theme").textContent = job.theme || "";
  $("#no-terms").hidden = Boolean(job.theme || terms.length);
  const table = $("#terms-table");
  table.innerHTML = "";
  for (const t of terms) {
    const tr = document.createElement("tr");
    const cells = job.target === "zh" ? [t.src, t.zh, t.note] : [t.src, t.zh, t.en, t.note];
    for (const text of cells) {
      const td = document.createElement("td");
      td.textContent = text || "";
      tr.appendChild(td);
    }
    table.appendChild(tr);
  }
}

// ---------------------------------------------------------------- images
const getImageTarget = segmented($("#img-target-seg"), () => {});
const imgInput = $("#img-input");
imgInput.addEventListener("change", () => { uploadImages(Array.from(imgInput.files)); imgInput.value = ""; });
setupDrop($("#img-dropzone"), (files) => uploadImages(files.filter((f) => f.type.startsWith("image/") || /\.(png|jpe?g|webp|bmp)$/i.test(f.name))));

async function uploadImages(files) {
  showError($("#img-error"), "");
  for (const file of files) {
    const card = $("#img-card-tpl").content.firstElementChild.cloneNode(true);
    $(".img-name", card).textContent = file.name;
    $("#img-results").prepend(card);
    const form = new FormData();
    form.append("file", file);
    form.append("target", getImageTarget());
    form.append("ocr_lang", $("#ocr-lang").value || "auto");
    form.append("api_key", apiKeyInput.value.trim());
    form.append("terms", termsInput.value);
    try {
      const res = await fetch("/api/images", { method: "POST", body: form });
      if (!res.ok) throw new Error(await errorText(res, `上传失败（${res.status}）`));
      pollImage((await res.json()).id, card);
    } catch (err) {
      imageFailed(card, err.message);
    }
  }
}

function imageFailed(card, message) {
  const badge = $(".img-status", card);
  badge.textContent = "出错";
  badge.className = "badge img-status error";
  showError($(".img-error", card), message);
}

async function pollImage(id, card) {
  let item;
  try {
    item = await (await fetch(`/api/images/${id}`)).json();
  } catch {
    setTimeout(() => pollImage(id, card), 2000);
    return;
  }
  $(".img-status", card).textContent = item.stage;
  $(".img-fill", card).style.width = `${(item.progress || 0) * 100}%`;
  if (item.status === "error") return imageFailed(card, item.error);
  if (item.status !== "done") return setTimeout(() => pollImage(id, card), 1200);

  const badge = $(".img-status", card);
  badge.textContent = `完成 · ${LANG_NAMES[item.language] || "外语"} → ${IMAGE_TARGETS[item.target]}`;
  badge.className = "badge img-status done";
  $(".bar", card).hidden = true;
  $(".img-body", card).hidden = false;

  const preview = $(".img-preview", card);
  const translated = `/api/images/${id}/translated.png?t=${Date.now()}`;
  preview.src = translated;
  segmented($(".img-view", card), (view) => (preview.src = view === "source" ? `/api/images/${id}/source` : translated));

  const ol = $(".img-lines", card);
  for (const line of item.lines) {
    const li = document.createElement("li");
    const orig = document.createElement("div");
    orig.className = "orig";
    orig.textContent = line.text;
    li.appendChild(orig);
    for (const t of [line.zh, line.en].filter((x) => x && x !== line.text)) {
      const d = document.createElement("div");
      d.textContent = t;
      li.appendChild(d);
    }
    ol.appendChild(li);
  }
  $(".img-dl-png", card).href = `/api/images/${id}/translated.png?download=true`;
  $(".img-dl-txt", card).href = `/api/images/${id}/text.txt`;
}
