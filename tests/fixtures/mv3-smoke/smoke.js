(() => {
  const start = async () => {
    if (document.getElementById("chromext-mv3-smoke")) return;
    const node = document.createElement("div");
    node.id = "chromext-mv3-smoke";
    node.setAttribute("role", "status");
    document.body.appendChild(node);
    try {
      const old = await chrome.storage.local.get("visits");
      const count = Number(old.visits || 0) + 1;
      await chrome.storage.local.set({visits: count});
      node.textContent = "ChromeXt isolated MV3 + storage: " + count;
      node.dataset.extensionId = chrome.runtime.id;
    } catch (error) {
      node.textContent = "ChromeXt MV3 API error: " + error.message;
      node.style.background = "#ad3a21";
    }
  };
  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", start, {once:true});
  else start();
})();
