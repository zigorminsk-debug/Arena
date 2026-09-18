/*
 * Подставляет текст, присланный через «Поделиться», в поле ввода Arena.
 * Определяет window.__arenaInjectText(text); приложение вызывает её со
 * строкой в виде JS-литерала.
 *
 * Поле ввода появляется не сразу (страница — SPA), поэтому функция повторяет
 * попытки и сообщает результат в приложение через window.ArenaAndroid.
 */
(function () {
  if (window.__arenaInjectText) { return; }

  var MAX_ATTEMPTS = 20;
  var RETRY_DELAY_MS = 700;

  function report(injected) {
    try {
      var bridge = window.ArenaAndroid;
      if (bridge) { bridge.reportSharedText(!!injected); }
    } catch (e) { }
  }

  function setNativeValue(element, value) {
    try {
      var isArea = (element.tagName === 'TEXTAREA');
      var proto = isArea ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
      if (descriptor && descriptor.set) { descriptor.set.call(element, value); } else { element.value = value; }
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
      return element.value === value;
    } catch (e) {
      return false;
    }
  }

  function insertIntoEditable(element, value) {
    try {
      element.focus();
      var inserted = false;
      try { inserted = document.execCommand('insertText', false, value); } catch (e) { inserted = false; }
      if (!inserted) {
        element.textContent = value;
        element.dispatchEvent(new Event('input', { bubbles: true }));
        inserted = true;
      }
      return inserted;
    } catch (e) {
      return false;
    }
  }

  window.__arenaInjectText = function (text) {
    if (!text) { return; }
    var attempts = 0;
    var finished = false;

    function finish(injected) {
      if (finished) { return true; }
      finished = true;
      report(injected);
      return true;
    }

    function attempt() {
      attempts++;
      try {
        var field = document.querySelector('textarea:not([readonly]):not([disabled])');
        if (field && setNativeValue(field, text)) {
          field.focus();
          return finish(true);
        }
        var editable = document.querySelector('[contenteditable="true"]');
        if (editable && insertIntoEditable(editable, text)) {
          return finish(true);
        }
      } catch (e) { }

      if (attempts >= MAX_ATTEMPTS) { return finish(false); }
      return false;
    }

    if (!attempt()) {
      var timer = setInterval(function () {
        if (attempt()) { clearInterval(timer); }
      }, RETRY_DELAY_MS);
    }
  };
})();
