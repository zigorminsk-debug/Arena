/*
 * Определяет, под каким логином GitHub открыта страница, и сообщает его
 * приложению — логин показывается в карточке профиля и в шапке.
 * Работает по нескольким признакам: мета-теги (их отдаёт и мобильная, и
 * десктопная версия) и атрибут data-login у аватара в шапке.
 */
(function () {
  try {
    var bridge = window.ArenaAndroid;
    if (!bridge) { return; }

    var login = '';

    var selectors = [
      'meta[name="user-login"]',
      'meta[name="octolytics-dimension-user_login"]'
    ];
    for (var i = 0; i < selectors.length && !login; i++) {
      var meta = document.querySelector(selectors[i]);
      if (meta && meta.content) { login = meta.content.trim(); }
    }

    if (!login) {
      var node = document.querySelector('[data-login]');
      if (node) { login = (node.getAttribute('data-login') || '').trim(); }
    }

    // Страница входа/регистрации: сообщать нечего
    if (!login) { return; }

    if (/^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$/.test(login)) {
      bridge.reportGithub(login);
    }
  } catch (e) { }
})();
