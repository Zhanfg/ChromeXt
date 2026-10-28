(() => {
  const render = () => {
    if (document.getElementById("chromext-mv3-smoke")) return;
    const chip = document.createElement("div");
    chip.id = "chromext-mv3-smoke";
    chip.textContent = "ChromeXt content script active";
    chip.setAttribute("role", "status");
    document.body.appendChild(chip);
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", render, {once: true});
  } else {
    render();
  }
})();
