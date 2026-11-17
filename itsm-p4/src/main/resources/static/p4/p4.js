/*
 * Shared helpers for the P4 pages, plus the <itsm-bell> notification bell.
 * Plain JavaScript with no build step, so it can sit next to whatever framework P2/P3 use.
 *
 * AUTH: if P1 stores the login token under a different key, or uses cookies/sessions, change ITSM.headers().
 */
(function () {
  const ITSM = (window.ITSM = {});

  ITSM.headers = function () {
    const token = localStorage.getItem("token");
    return token ? { Authorization: "Bearer " + token } : {};
  };

  ITSM.api = async function (path, options) {
    const opts = options || {};
    const res = await fetch(path, {
      ...opts,
      headers: { "Content-Type": "application/json", ...ITSM.headers(), ...(opts.headers || {}) },
    });
    if (!res.ok) {
      let msg = res.status + " " + res.statusText;
      try { const body = await res.json(); if (body.message) msg = body.message; } catch (e) { /* not JSON */ }
      throw new Error(msg);
    }
    return res.status === 204 ? null : res.json();
  };

  ITSM.esc = function (s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => (
      { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
    ));
  };

  ITSM.fmt = (iso) => (iso ? new Date(iso).toLocaleString() : "—");

  ITSM.qs = function (obj) {
    const p = new URLSearchParams();
    Object.entries(obj).forEach(([k, v]) => { if (v !== "" && v != null) p.set(k, v); });
    return p.toString();
  };

  ITSM.mountHeader = function (active) {
    const links = [
      ["notifications.html", "Notifications"],
      ["search.html", "Search"],
      ["dashboard.html", "Dashboard"],
      ["audit.html", "Audit log"],
    ];
    const el = document.getElementById("p4-header");
    el.className = "top";
    el.innerHTML =
      "<strong>ITSM</strong><nav>" +
      links.map(([href, label]) =>
        `<a href="${href}" class="${href === active ? "active" : ""}">${label}</a>`).join("") +
      "</nav><itsm-bell></itsm-bell>";
  };

  // ---- Notification bell: unread count, updated live over Server-Sent Events ----
  class ItsmBell extends HTMLElement {
    connectedCallback() {
      this.innerHTML = '<a class="bell" href="notifications.html" title="Notifications">&#128276;<span class="badge" hidden>0</span></a>';
      this.badge = this.querySelector(".badge");
      this.refresh();
      this.listen();
    }

    async refresh() {
      try {
        const r = await ITSM.api("/api/notifications/unread-count");
        this.badge.textContent = r.unread > 99 ? "99+" : r.unread;
        this.badge.hidden = r.unread === 0;
      } catch (e) { /* not logged in yet */ }
    }

    // fetch() instead of EventSource, because EventSource cannot send an Authorization header.
    async listen() {
      for (;;) {
        try {
          const res = await fetch("/api/notifications/stream", {
            headers: { ...ITSM.headers(), Accept: "text/event-stream" },
          });
          if (!res.ok || !res.body) throw new Error("stream " + res.status);
          const reader = res.body.getReader();
          const decoder = new TextDecoder();
          let buffer = "";
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let end;
            while ((end = buffer.indexOf("\n\n")) >= 0) {
              const block = buffer.slice(0, end);
              buffer = buffer.slice(end + 2);
              this.handle(block);
            }
          }
        } catch (e) { /* fall through and reconnect */ }
        await new Promise((r) => setTimeout(r, 3000));
      }
    }

    handle(block) {
      let event = "message", data = "";
      block.split("\n").forEach((line) => {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      });
      if (event !== "notification" || !data) return;
      this.refresh();
      document.dispatchEvent(new CustomEvent("itsm:notification", { detail: JSON.parse(data) }));
    }
  }
  customElements.define("itsm-bell", ItsmBell);
})();
