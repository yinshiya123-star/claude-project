const $ = (sel) => document.querySelector(sel);

const fileInput = $("#file-input");
const dropzone = $("#dropzone");
const startBtn = $("#start-btn");
const apiKeyInput = $("#api-key");
const targetSelect = $("#target");
const languageSelect = $("#language");
const player = $("#player");
const list = $("#segments");
const saveBtn = $("#save-btn");

const MODE_LABELS = { bilingual: "中英双语", zh: "中文", en: "英文", orig: "原文+中文" };
const LANG_NAMES = {
  zh: "中文", en: "英语", ja: "日语", ko: "韩语", fr: "法语", de: "德语", es: "西班牙语",
  ru: "俄语", pt: "葡萄牙语", it: "意大利语", ar: "阿拉伯语", th: "泰语", vi: "越南语",
  id: "印尼语", ms: "马来语", hi: "印地语", tr: "土耳其语", nl: "荷兰语", pl: "波兰语",
  uk: "乌克兰语", yue: "粤语",
};

let selectedFile = null;
let jobId = null;
let job = null;
let segments = [];
let mode = "bilingual";
let track = null;
let dirty = false;
let serverKey = false;

// ---- API key is remembered locally in this browser only ----
try { apiKeyInput.value = localStorage.getItem("deepseek_key") || ""; } catch {}
apiKeyInput.addEventListener("change", () => {
  try { localStorage.setItem("deepseek_key", apiKeyInput.value.trim()); } catch {}
});
fetch("/api/config").then((r) => r.json()).then((cfg) => {
  serverKey = cfg.server_key_configured;
  updateTargetUi();
}).catch(() => {});

// Chinese-only output: language is fixed to Chinese and the key only adds proofreading.
function updateTargetUi() {
  const zhOnly = targetSelect.value === "zh";
  languageSelect.disabled = zhOnly;
  if (zhOnly) languageSelect.value = "zh";
  $("#key-label").textContent = zhOnly ? "DeepSeek API Key（可选，填写后会用 DeepSeek 校对错别字和标点）" : "DeepSeek API Key";
  apiKeyInput.placeholder = serverKey ? "服务端已配置 Key，可留空" : zhOnly ? "可留空" : "sk-...";
}
targetSelect.addEventListener("change", updateTargetUi);
updateTargetUi();

// ---- File selection ----
function pickFile(file) {
  if (!file) return;
  selectedFile = file;
  $("#drop-text").textContent = `${file.name}（${(file.size / 1024 / 1024).toFixed(1)} MB）`;
  startBtn.disabled = false;
}
fileInput.addEventListener("change", () => pickFile(fileInput.files[0]));
["dragenter", "dragover"].forEach((ev) =>
  dropzone.addEventListener(ev, (e) => { e.preventDefault(); dropzone.classList.add("over"); }));
["dragleave", "drop"].forEach((ev) =>
  dropzone.addEventListener(ev, (e) => { e.preventDefault(); dropzone.classList.remove("over"); }));
dropzone.addEventListener("drop", (e) => pickFile(e.dataTransfer.files[0]));

function showError(el, msg) {
  el.textContent = msg;
  el.hidden = !msg;
}

// ---- Upload & poll ----
startBtn.addEventListener("click", () => {
  if (!selectedFile) return;
  showError($("#upload-error"), "");
  showError($("#job-error"), "");
  $("#result-card").hidden = true;
  startBtn.disabled = true;

  const form = new FormData();
  form.append("file", selectedFile);
  form.append("target", targetSelect.value);
  form.append("language", languageSelect.value);
  form.append("api_key", apiKeyInput.value.trim());

  // XHR instead of fetch so we can show upload progress.
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/jobs");
  $("#progress-card").hidden = false;
  setProgress("上传中", 0);
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) setProgress("上传中", e.loaded / e.total);
  };
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

function setProgress(stage, p) {
  $("#stage").textContent = stage;
  $("#percent").textContent = `${Math.round(p * 100)}%`;
  $("#bar-fill").style.width = `${p * 100}%`;
}

async function poll() {
  let data;
  try {
    data = await (await fetch(`/api/jobs/${jobId}`)).json();
  } catch {
    setTimeout(poll, 2000);
    return;
  }
  setProgress(data.stage, data.progress || 0);
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

// ---- Result view ----
function availableModes() {
  if (job.target === "zh") return ["zh"];
  const modes = ["bilingual", "zh", "en"];
  if (job.language && !["zh", "en"].includes(job.language)) modes.push("orig");
  return modes;
}

// Foreign-language source: show the recognised original text in the list too.
function showOriginal() {
  return job.target !== "zh" && job.language && !["zh", "en"].includes(job.language);
}

function showResult(data) {
  job = data;
  segments = data.segments;
  dirty = false;
  saveBtn.disabled = true;
  $("#result-card").hidden = false;
  const lang = LANG_NAMES[data.language] || data.language || "-";
  $("#seg-count").textContent = `共 ${segments.length} 条，识别语言：${lang}`;

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
  renderDownloads();
  renderList();
  renderCues();
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
      box.querySelectorAll("button").forEach((b) => b.classList.toggle("active", b === btn));
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
  const modes = availableModes();
  for (const f of FORMATS) {
    const tr = document.createElement("tr");
    const th = document.createElement("th");
    th.textContent = f.label;
    tr.appendChild(th);
    for (const m of modes) {
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

async function onDownload(e, a, fmt) {
  if (fmt !== "mp3" && !dirty) return; // plain link download
  e.preventDefault();
  if (dirty && !(await save())) return;
  if (fmt !== "mp3") {
    window.location.href = a.href;
    return;
  }
  // MP3 export can take a while (videos are converted first): show progress.
  const label = a.textContent;
  a.textContent = "导出中…";
  a.classList.add("busy");
  try {
    const res = await fetch(a.href);
    if (!res.ok) {
      let msg = `导出失败（${res.status}）`;
      try { msg = (await res.json()).detail || msg; } catch {}
      throw new Error(msg);
    }
    const blob = await res.blob();
    const name = /filename\*=UTF-8''([^;]+)/.exec(res.headers.get("Content-Disposition") || "");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = name ? decodeURIComponent(name[1]) : "subtitle.mp3";
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  } catch (err) {
    alert(err.message);
  } finally {
    a.textContent = label;
    a.classList.remove("busy");
  }
}

function fmtTime(t) {
  const m = Math.floor(t / 60);
  const s = (t % 60).toFixed(1).padStart(4, "0");
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
    time.textContent = `${fmtTime(s.start)} → ${fmtTime(s.end)}`;
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
  list.querySelectorAll("li.current").forEach((el) => el.classList.remove("current"));
  if (!id) return;
  const li = list.querySelector(`li[data-id="${id}"]`);
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
    saveBtn.textContent = "已保存";
    setTimeout(() => (saveBtn.textContent = "保存修改"), 1500);
    return true;
  }
  saveBtn.disabled = false;
  alert("保存失败");
  return false;
}
saveBtn.addEventListener("click", save);
