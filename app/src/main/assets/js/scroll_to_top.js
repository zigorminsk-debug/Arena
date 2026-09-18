(function () {
  if (window.__arenaScrollToTop) {
    window.__arenaScrollToTop();
    return;
  }
  try { window.scrollTo(0, 0); } catch (e) { }
  try {
    var root = document.scrollingElement || document.documentElement;
    if (root) { root.scrollTop = 0; }
  } catch (e) { }
})();
