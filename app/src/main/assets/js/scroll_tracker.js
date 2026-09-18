(function () {
  if (window.__arenaScrollHook) { return; }
  window.__arenaScrollHook = true;

  var bridge = window.ArenaAndroid;
  var lastScroller = null;
  var lastReported = null;

  function windowOffset() {
    if (typeof window.pageYOffset === 'number') { return window.pageYOffset; }
    var root = document.scrollingElement || document.documentElement;
    return root ? root.scrollTop : 0;
  }

  function computeAtTop() {
    if (windowOffset() > 2) { return false; }
    if (lastScroller && lastScroller.scrollTop > 2) { return false; }
    return true;
  }

  function report() {
    var value = computeAtTop();
    if (value === lastReported) { return; }
    lastReported = value;
    try { if (bridge) { bridge.reportScroll(value); } } catch (e) { }
  }

  function onScroll(event) {
    var target = event.target;
    if (!target || target === document || target === window ||
        target === document.documentElement || target === document.body) {
      lastScroller = null;            // прокручивается сама страница
    } else if (typeof target.scrollTop === 'number') {
      lastScroller = target;          // прокручивается внутренний контейнер
    }
    report();
  }

  document.addEventListener('scroll', onScroll, true);   // capture: ловим и вложенные
  window.addEventListener('scroll', onScroll, true);
  window.addEventListener('resize', report, true);
  window.addEventListener('orientationchange', report, true);

  report();
})();
