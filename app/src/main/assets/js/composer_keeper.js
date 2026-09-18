/*
 * Поворот экрана не должен стоить черновика.
 *
 * Проблема: при смене размеров страницы сайт arena.ai переключает вёрстку
 * (мобильную на десктопную) и пересоздаёт поле ввода. Введённый текст и
 * прикреплённые файлы живут только в состоянии страницы, поэтому при
 * перерисовке пропадают.
 *
 * Что делает скрипт:
 *   1. непрерывно помнит последнее непустое содержимое поля ввода и файлы,
 *      которые пользователь прикрепил;
 *   2. при изменении ширины окна (поворот, смена размера) снимает «снимок»
 *      и переносит текст в localStorage — он переживёт даже перезагрузку;
 *   3. если поле ввода опустело из-за перерисовки в течение 8 секунд после
 *      поворота, возвращает туда текст и файлы;
 *   4. если пользователь сам отправил сообщение или стёр текст — снимок
 *      отбрасывается, ничего не «возвращается само».
 *
 * Результат сообщается приложению через window.ArenaAndroid.reportDraftRestored.
 */
(function () {
  if (window.__arenaComposerKeeper) { return; }
  window.__arenaComposerKeeper = true;

  var RESTORE_WINDOW_MS = 8000;   // сколько ждём перерисовку после поворота
  var SETTLE_MS = 450;            // пауза, чтобы сайт успел перерисоваться
  var TICK_MS = 250;
  var USER_ACTION_GRACE_MS = 3000;
  var CACHE_TTL_MS = 2000;        // насколько свежим должен быть кэш текста
  var WIDTH_EPSILON = 80;         // поворот меняет ширину, клавиатура — нет
  var STORAGE_KEY = '__arena_draft_v1';
  var MAX_FILES = 3;
  var MAX_FILE_BYTES = 6 * 1024 * 1024;
  var MAX_TEXT_LENGTH = 20000;

  var snapshot = null;      // { text, files, at }
  var cachedText = '';      // последнее увиденное содержимое поля
  var cachedTextAt = 0;
  var cachedFiles = [];     // последние прикреплённые файлы
  var settleTimer = null;
  var tickTimer = null;
  var userActedAt = 0;
  var lastArmAt = 0;
  var lastWidth = window.innerWidth || 0;

  // ------------------------------------------------------------- утилиты DOM

  function now() { return Date.now(); }

  function composerField() {
    var el = document.querySelector('textarea:not([readonly]):not([disabled])');
    if (el) { return el; }
    return document.querySelector('[contenteditable="true"][role="textbox"], [contenteditable="true"]');
  }

  function valueOf(el) {
    if (!el) { return ''; }
    if (el.isContentEditable) { return (el.innerText || el.textContent || ''); }
    return el.value || '';
  }

  function setNativeValue(element, value) {
    try {
      var isArea = (element.tagName === 'TEXTAREA');
      var proto = isArea ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
      if (descriptor && descriptor.set) { descriptor.set.call(element, value); } else { element.value = value; }
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
      return valueOf(element) === value;
    } catch (e) {
      return false;
    }
  }

  function insertIntoEditable(element, value) {
    try {
      element.focus();
      var done = false;
      try { done = document.execCommand('insertText', false, value); } catch (e) { done = false; }
      if (!done) {
        element.textContent = value;
        element.dispatchEvent(new Event('input', { bubbles: true }));
        done = true;
      }
      return done;
    } catch (e) {
      return false;
    }
  }

  function fileInputs() {
    return Array.prototype.slice.call(document.querySelectorAll('input[type="file"]'));
  }

  function attachedFiles() {
    var result = [];
    fileInputs().forEach(function (input) {
      if (!input.files || !input.files.length) { return; }
      for (var i = 0; i < input.files.length && result.length < MAX_FILES; i++) {
        var file = input.files[i];
        if (file && file.size <= MAX_FILE_BYTES) { result.push(file); }
      }
    });
    return result;
  }

  function restoreFiles(files) {
    if (!files || !files.length) { return 0; }
    var inputs = fileInputs();
    if (!inputs.length) { return 0; }
    // предпочитаем пустое поле выбора файла
    var target = inputs.filter(function (input) { return !input.files || !input.files.length; })[0] || inputs[0];
    try {
      var transfer = new DataTransfer();
      files.forEach(function (file) { transfer.items.add(file); });
      target.files = transfer.files;
      target.dispatchEvent(new Event('change', { bubbles: true }));
      return files.length;
    } catch (e) {
      return 0;
    }
  }

  function report(textRestored, filesRestored) {
    try {
      var bridge = window.ArenaAndroid;
      if (bridge && bridge.reportDraftRestored) {
        bridge.reportDraftRestored(!!textRestored, filesRestored | 0);
      }
    } catch (e) { }
  }

  // -------------------------------------------------------- снимок и возврат

  function stopTimers() {
    if (tickTimer) { clearInterval(tickTimer); tickTimer = null; }
    if (settleTimer) { clearTimeout(settleTimer); settleTimer = null; }
  }

  function forget() {
    snapshot = null;
    stopTimers();
    try { window.localStorage.removeItem(STORAGE_KEY); } catch (e) { }
  }

  function remember(text, at) {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ text: text, at: at }));
    } catch (e) { }
  }

  function arm() {
    var el = composerField();
    var domText = el ? valueOf(el) : '';
    var text = domText;
    // если сайт успел очистить поле до нас — берём текст из кэша
    if (!text && cachedText && (now() - cachedTextAt) < CACHE_TTL_MS) { text = cachedText; }
    text = String(text || '').slice(0, MAX_TEXT_LENGTH);

    var files = attachedFiles();
    if (!files.length && cachedFiles.length) { files = cachedFiles.slice(0, MAX_FILES); }

    if (!text && !files.length) { return; }

    snapshot = { text: text, files: files, at: now() };
    if (text) { remember(text, snapshot.at); }
    if (!tickTimer) { tickTimer = setInterval(tick, TICK_MS); }
  }

  function tick() {
    if (!snapshot) { stopTimers(); return; }
    // пользователь сам что-то нажал (отправка, вложение) — не вмешиваемся
    if (userActedAt && (now() - userActedAt) < USER_ACTION_GRACE_MS) { forget(); return; }
    if ((now() - snapshot.at) > RESTORE_WINDOW_MS) { forget(); return; }

    var el = composerField();
    if (!el) { return; }                      // поле ещё не отрисовано — ждём

    if (valueOf(el)) {
      // содержимое на месте: если перерисовка уже прошла, снимок больше не нужен
      if ((now() - snapshot.at) > SETTLE_MS * 2) { forget(); }
      return;
    }

    if (settleTimer) { return; }
    settleTimer = setTimeout(function () {
      settleTimer = null;
      if (!snapshot) { return; }
      var field = composerField();
      if (!field || valueOf(field)) { return; }
      apply(field);
    }, SETTLE_MS);
  }

  function apply(field) {
    var text = snapshot ? snapshot.text : '';
    var files = snapshot ? snapshot.files : [];

    var textRestored = false;
    if (text) {
      textRestored = field.isContentEditable
        ? insertIntoEditable(field, text)
        : setNativeValue(field, text);
    }
    var filesRestored = restoreFiles(files);

    cachedText = text;
    cachedTextAt = now();
    if (filesRestored) { cachedFiles = files.slice(0, MAX_FILES); }

    forget();
    if (textRestored || filesRestored) { report(textRestored, filesRestored); }
  }

  // ----------------------------------------------------------- отслеживание

  function onResize() {
    var width = window.innerWidth || 0;
    var changed = Math.abs(width - lastWidth) > WIDTH_EPSILON;
    lastWidth = width;
    // клавиатура меняет только высоту — поворот меняет ширину
    if (changed && (now() - lastArmAt) > 1000) {
      lastArmAt = now();
      arm();
    }
  }

  function onInput(event) {
    var target = event.target;
    if (!target) { return; }
    if (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT' ||
        target.isContentEditable) {
      cachedText = valueOf(target);
      cachedTextAt = now();
    }
    if (target.tagName === 'INPUT' && target.type === 'file') {
      var files = attachedFiles();
      if (files.length) { cachedFiles = files.slice(0, MAX_FILES); }
    }
  }

  function onKeyDown(event) {
    // Enter без Shift — отправка сообщения
    if (event.key === 'Enter' && !event.shiftKey) { userActedAt = now(); }
  }

  function onClick() {
    userActedAt = now();
  }

  window.addEventListener('orientationchange', function () { lastArmAt = now(); arm(); }, true);
  window.addEventListener('resize', onResize, true);
  document.addEventListener('input', onInput, true);
  document.addEventListener('change', onInput, true);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('click', onClick, true);

  // Страница перезагрузилась сразу после поворота — поднимаем текст из хранилища
  try {
    var raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      var saved = JSON.parse(raw);
      if (saved && saved.text && (now() - (saved.at || 0)) < RESTORE_WINDOW_MS) {
        snapshot = { text: String(saved.text).slice(0, MAX_TEXT_LENGTH), files: [], at: saved.at };
        tickTimer = setInterval(tick, TICK_MS);
      } else {
        window.localStorage.removeItem(STORAGE_KEY);
      }
    }
  } catch (e) { }
})();
