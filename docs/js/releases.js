const REPO = "amadeusk1/Digital-Fuel-Gauge";
const API = `https://api.github.com/repos/${REPO}/releases`;
const FALLBACK = `https://github.com/${REPO}/releases`;

const root = document.getElementById("releases");

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderMarkdown(src) {
  if (!src) return "<p>No release notes.</p>";
  const lines = src.replace(/\r\n/g, "\n").split("\n");
  let html = "";
  let inList = false;

  const flushList = () => {
    if (inList) {
      html += "</ul>";
      inList = false;
    }
  };

  const inline = (s) =>
    escapeHtml(s)
      .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
      .replace(/`([^`]+)`/g, "<code>$1</code>");

  for (const line of lines) {
    const heading = line.match(/^(#{1,3})\s+(.*)$/);
    const item = line.match(/^[-*]\s+(.*)$/);
    if (heading) {
      flushList();
      const level = Math.min(heading[1].length + 1, 3);
      html += `<h${level}>${inline(heading[2])}</h${level}>`;
    } else if (item) {
      if (!inList) {
        html += "<ul>";
        inList = true;
      }
      html += `<li>${inline(item[1])}</li>`;
    } else if (line.trim() === "") {
      flushList();
    } else {
      flushList();
      html += `<p>${inline(line)}</p>`;
    }
  }
  flushList();
  return html;
}

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleDateString("en-CA", {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function formatSize(bytes) {
  if (!bytes) return "";
  if (bytes < 1024) return `${bytes} B`;
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

function apkAsset(release) {
  const assets = release.assets || [];
  return assets.find((a) => /\.apk$/i.test(a.name)) || assets[0] || null;
}

function renderRelease(release, isLatest) {
  const apk = apkAsset(release);
  const title = escapeHtml(release.name || release.tag_name || "Release");
  const tag = escapeHtml(release.tag_name || "");
  const date = formatDate(release.published_at);
  const notes = renderMarkdown(release.body || "");
  const badge = isLatest ? '<span class="badge">Latest</span>' : "";
  let download = "";
  if (apk) {
    download = `<a class="btn btn-primary btn-sm" href="${escapeHtml(apk.browser_download_url)}">Download ${escapeHtml(apk.name)}</a>`;
  } else {
    download = `<a class="btn btn-ghost btn-sm" href="${escapeHtml(release.html_url)}">View on GitHub</a>`;
  }
  const metaBits = [tag, date];
  if (apk) metaBits.push(`${escapeHtml(apk.name)} · ${formatSize(apk.size)}`);

  return `
    <article class="release${isLatest ? " latest" : ""}">
      <div class="release-head">
        <div>
          <h2>${title}${badge}</h2>
          <p class="release-meta">${metaBits.filter(Boolean).join(" · ")}</p>
        </div>
        ${download}
      </div>
      <div class="release-notes">${notes}</div>
    </article>
  `;
}

function showError(message) {
  root.innerHTML = `
    <p class="release-error">${escapeHtml(message)}</p>
    <p><a class="btn btn-ghost" href="${FALLBACK}">Open GitHub Releases</a></p>
  `;
}

async function load() {
  try {
    const res = await fetch(API, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) throw new Error("Could not load releases.");
    const data = await res.json();
    const published = (data || []).filter((r) => !r.draft);
    if (!published.length) {
      root.innerHTML = `<p class="release-status">No published releases yet. <a href="${FALLBACK}">Check GitHub</a>.</p>`;
      return;
    }
    const firstStable = published.find((r) => !r.prerelease) || published[0];
    root.innerHTML = published
      .map((r) => renderRelease(r, r.id === firstStable.id))
      .join("");
  } catch (err) {
    showError("Releases could not be loaded from GitHub. Use the link below.");
  }
}

load();
