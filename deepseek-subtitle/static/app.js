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
  const ocr = $("#ocr-lang");
  for (const [value, label] of Object.entries(cfg.ocr_langs || {})) ocr.add(new Option(label, value));
  updateTargetUi();
}).catch(() => {});
fetch("/api/voices").then((r) => r.json()).then((v) => (voices = v)).catch(() => {});

// ---------------------------------------------------------------- upload
const fileInput = $("#file-input");
const dropzone = $("#dropzone");
const startBtn = $("#start-btn");
const targetInput = $("#target");
const languageSelect = $("#language");
let selectedFile = null;
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

function pickFile(file) {
  if (!file) return;
  selectedFile = file;
  $("#drop-text").textContent = `${file.name}（${(file.size / 1024 / 1024).toFixed(1)} MB）`;
  startBtn.disabled = false;
}
function setupDrop(zone, onFiles) {
  ["dragenter", "dragover"].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.add("over"); }));
  ["dragleave", "drop"].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.remove("over"); }));
  zone.addEventListener("drop", (e) => onFiles(Array.from(e.dataTransfer.files)));
}
fileInput.addEventListener("change", () => pickFile(fileInput.files[0]));
setupDrop(dropzone, (files) => pickFile(files[0]));

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

let jobId = null;
let job = null;

startBtn.addEventListener("click", () => {
  if (!selectedFile) return;
  showError($("#upload-error"), "");
  showError($("#job-error"), "");
  $("#result-card").hidden = true;
  startBtn.disabled = true;

  const form = new FormData();
  form.append("file", selectedFile);
  form.append("target", targetInput.value);
  form.append("language", languageSelect.value);
  form.append("api_key", apiKeyInput.value.trim());
  form.append("reflect", $("#reflect").checked ? "1" : "0");
  form.append("correct", $("#correct").checked ? "1" : "0");
  form.append("terms", termsInput.value);
  form.append("model", model);

  // XHR instead of fetch so we can show upload progress.
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/jobs");
  $("#progress-card").hidden = false;
  setStep("upload");
  setProgress("上传中", 0);
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) setProgress("上传中", e.loaded / e.total); };
  xhr.onload = () => {
    let data = {};
    try { data = JSON.parse(xhr.responseText); } catch {}
    if (xhr.status >= 400) {
      $("#progress-card").hidden = true;
      showError($("#upload-error"), data.detail || `上传失败（${xhr.status}）`);
      startBtn.disabled = false;
      return;
    }
    jobId = data.id;
    poll();
  };
  xhr.onerror = () => {
    $("#progress-card").hidden = true;
    showError($("#upload-error"), "网络错误，上传失败");
    startBtn.disabled = false;
  };
  xhr.send(form);
});

async function poll() {
  let data;
  try {
    data = await (await fetch(`/api/jobs/${jobId}`)).json();
  } catch {
    setTimeout(poll, 2000);
    return;
  }
  setProgress(data.stage, data.progress || 0);
  setStep({ translating: "translate", done: "done" }[data.status] || "transcribe");
  if (data.status === "done") {
    startBtn.disabled = false;
    showResult(data);
  } else if (data.status === "error") {
    startBtn.disabled = false;
    showError($("#job-error"), data.error || "处理失败");
  } else {
    setTimeout(poll, 1500);
  }
}

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
  renderSummary();
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

async function fetchDownload(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(await errorText(res, `下载失败（${res.status}）`));
  const blob = await res.blob();
  const name = /filename\*=UTF-8''([^;]+)/.exec(res.headers.get("Content-Disposition") || "");
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
const showBurn = (state) => showTask("burn", state, BURN_LABELS);

function renderBurn() {
  fillModes($("#burn-mode"));
  showBurn(job.burn);
  if (job.burn && job.burn.status === "running") pollTask("burn", showBurn);
}

$("#burn-btn").addEventListener("click", async () => {
  if (dirty && !(await save())) return; // burn what the user sees
  $("#burn-btn").disabled = true;
  const burnMode = $("#burn-mode").value;
  const res = await fetch(`/api/jobs/${jobId}/burn`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ mode: burnMode }),
  });
  if (!res.ok) {
    showError($("#burn-error"), await errorText(res, `烧录失败（${res.status}）`));
    $("#burn-btn").disabled = false;
    return;
  }
  showBurn({ status: "running", mode: burnMode, progress: 0 });
  pollTask("burn", showBurn);
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
