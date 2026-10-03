const $ = (sel) => document.querySelector(sel);

const fileInput = $("#file-input");
const dropzone = $("#dropzone");
const startBtn = $("#start-btn");
const apiKeyInput = $("#api-key");
const player = $("#player");
const list = $("#segments");
const saveBtn = $("#save-btn");

let selectedFile = null;
let jobId = null;
let segments = [];
let mode = "bilingual";
let track = null;
let dirty = false;

// ---- API key is remembered locally in this browser only ----
try { apiKeyInput.value = localStorage.getItem("deepseek_key") || ""; } catch {}
apiKeyInput.addEventListener("change", () => {
  try { localStorage.setItem("deepseek_key", apiKeyInput.value.trim()); } catch {}
});
fetch("/api/config").then((r) => r.json()).then((cfg) => {
  if (cfg.server_key_configured) apiKeyInput.placeholder = "服务端已配置 Key，可留空";
}).catch(() => {});

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
  form.append("language", $("#language").value);
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
  let job;
  try {
    job = await (await fetch(`/api/jobs/${jobId}`)).json();
  } catch {
    setTimeout(poll, 2000);
    return;
  }
  setProgress(job.stage, job.progress || 0);
  if (job.status === "done") {
    startBtn.disabled = false;
    showResult(job);
  } else if (job.status === "error") {
    startBtn.disabled = false;
    showError($("#job-error"), job.error || "处理失败");
  } else {
    setTimeout(poll, 1500);
  }
}

// ---- Result view ----
function showResult(job) {
  segments = job.segments;
  dirty = false;
  saveBtn.disabled = true;
  $("#result-card").hidden = false;
  $("#seg-count").textContent = `共 ${segments.length} 条，识别语言：${job.language || "-"}`;

  const isAudio = /\.(mp3|m4a|wav|flac|ogg|aac)$/i.test(job.filename || "");
  player.classList.toggle("audio-only", isAudio);
  player.src = `/api/jobs/${jobId}/media`;

  // Cues are built in JS so edits and mode switches show up instantly.
  if (!track) {
    track = player.addTextTrack("subtitles", "字幕", "zh");
    track.mode = "showing";
  }
  renderList();
  renderCues();
  updateDownloadLinks();
}

function cueText(s) {
  const zh = s.zh || s.text;
  const en = s.en || s.text;
  if (mode === "zh") return zh;
  if (mode === "en") return en;
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
  const s = (t % 60).toFixed(1).padStart(4, "0");
  return `${String(m).padStart(2, "0")}:${s}`;
}

function renderList() {
  list.innerHTML = "";
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
    for (const key of ["zh", "en"]) {
      const d = document.createElement("div");
      d.className = key;
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

document.querySelectorAll(".seg-toggle button").forEach((btn) =>
  btn.addEventListener("click", () => {
    mode = btn.dataset.mode;
    document.querySelectorAll(".seg-toggle button").forEach((b) => b.classList.toggle("active", b === btn));
    renderCues();
  }));

function updateDownloadLinks() {
  document.querySelectorAll(".downloads a").forEach((a) => {
    a.href = `/api/jobs/${jobId}/subtitle.${a.dataset.fmt}?mode=${a.dataset.dl}&download=true`;
  });
}

async function save() {
  saveBtn.disabled = true;
  const res = await fetch(`/api/jobs/${jobId}/segments`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ segments: segments.map(({ id, zh, en }) => ({ id, zh, en })) }),
  });
  if (res.ok) {
    dirty = false;
    saveBtn.textContent = "已保存";
    setTimeout(() => (saveBtn.textContent = "保存修改"), 1500);
  } else {
    saveBtn.disabled = false;
    alert("保存失败");
  }
}
saveBtn.addEventListener("click", save);

// Unsaved edits would be missing from downloads: save first.
document.querySelectorAll(".downloads a").forEach((a) =>
  a.addEventListener("click", async (e) => {
    if (!dirty) return;
    e.preventDefault();
    await save();
    window.location.href = a.href;
  }));
